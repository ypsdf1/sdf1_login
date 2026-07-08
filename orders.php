<?php
/**
 * 独立订单记录页面
 * 从收银台拆分出来，支持搜索、筛选、完整订单详情查看。
 */

// ★ 错误报告增强：捕获所有PHP错误写入debug.log（包括warning/notice）
//   用户反馈"全新无扩展浏览器仍报错"，需排查任何可能破坏前端输出的PHP问题
error_reporting(E_ALL);
ini_set('display_errors', '0');
set_error_handler(function($errno, $errstr, $errfile, $errline) {
    $types = [E_WARNING=>'Warning',E_NOTICE=>'Notice',E_USER_ERROR=>'UserError',E_USER_WARNING=>'UserWarning',E_USER_NOTICE=>'UserNotice',E_STRICT=>'Strict',E_DEPRECATED=>'Deprecated',E_RECOVERABLE_ERROR:'Recoverable'];
    $type = isset($types[$errno]) ? $types[$errno] : "Error[$errno]";
    if (file_exists(__DIR__ . '/core.php')) {
        // core.php may not be loaded yet, define minimal debugLog
        if (!function_exists('debugLog')) {
            function debugLog($msg) {
                @file_put_contents(__DIR__ . '/db/debug.log', "[" . date('Y-m-d H:i:s') . "] [orders.php] $msg\n", FILE_APPEND | LOCK_EX);
            }
        }
        debugLog("[orders.php] PHP {$type}: {$errstr} in {$errfile}:{$errline}");
    }
    return true; // 不阻止默认错误处理
});

require_once __DIR__ . '/core.php';

// ★ 强制使 opcache 失效，确保部署后的新版（含请求超时/重试）立即生效，
//   避免 Web 服务端 opcache 持续服务旧版 orders.php（无超时→永久"加载中"）
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }

if (session_status() === PHP_SESSION_NONE) session_start();

// ★ 输出缓冲：捕获任何前置输出（PHP warning/notice/BOM等），防止破坏前端JSON解析
$preOutput = '';
if (ob_get_level() > 0) {
    $preOutput = ob_get_clean();
}
if (!empty($preOutput)) {
    // 记录但不阻断：有前置输出说明某处产生了warning/notice/BOM
    debugLog('[orders.php] 检测到前置输出(' . strlen($preOutput) . ' bytes): ' . substr(trim($preOutput), 0, 500));
}

try {
    $loggedIn = isAdminLoggedIn() || isCashierLoggedIn();
    $role = 'guest';
    $name = '';
    if (isAdminLoggedIn()) { $role = 'admin'; $name = 'admin'; }
    elseif (isCashierLoggedIn()) { $c = getCurrentCashier() ?: []; $role = 'cashier'; $name = $c['username'] ?? ''; }
} catch (\Throwable $e) {
    debugLog('[orders.php] 会话状态判定异常: ' . $e->getMessage());
    $loggedIn = false; $role = 'guest'; $name = '';
}

$initialState = json_encode([
    'logged_in' => $loggedIn,
    'role' => $role,
    'username' => $name,
], JSON_UNESCAPED_UNICODE);
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
<title>SDF1 - 订单记录</title>
<style>
    :root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --dim:#8b949e; --accent:#58a6ff; --green:#3fb950; --red:#f85149; --yellow:#d29922; --purple:#bc8cff; }
    * { margin:0; padding:0; box-sizing:border-box; }
    body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; min-height:100vh; }
    .header { background:linear-gradient(135deg,#1a1e2e,#0d1117); border-bottom:1px solid var(--border); padding:14px 24px; display:flex; justify-content:space-between; align-items:center; position:sticky; top:0; z-index:50; }
    .header h1 { font-size:18px; color:var(--accent); }
    .btn { padding:8px 16px; border:none; border-radius:6px; cursor:pointer; font-size:13px; font-weight:600; transition:all .2s cubic-bezier(.16,1,.3,1); }
    .btn-blue { background:var(--accent); color:#fff; }
    .btn-green { background:var(--green); color:#fff; }
    .btn-red { background:var(--red); color:#fff; }
    .btn-ghost { background:transparent; border:1px solid var(--border); color:var(--dim); }
    .btn:hover { opacity:.88; transform:translateY(-1px); }
    .container { max-width:1100px; margin:20px auto; padding:0 16px; }
    .panel { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:18px; margin-bottom:16px; }
    .panel h3 { font-size:15px; color:var(--accent); margin-bottom:12px; display:flex; align-items:center; gap:8px; }
    /* 搜索栏 */
    .search-bar { display:flex; gap:10px; flex-wrap:wrap; margin-bottom:16px; }
    .search-bar input, .search-bar select { padding:9px 12px; background:var(--bg); border:1px solid var(--border); border-radius:7px; color:var(--text); font-size:13px; outline:none; flex:1; min-width:160px; }
    .search-bar input:focus { border-color:var(--accent); }
    /* 订单列表 */
    .order-row { font-size:13px; padding:14px; background:var(--bg); border:1px solid var(--border); border-radius:10px; margin-bottom:10px; transition:border-color .2s; cursor:pointer; }
    .order-row:hover { border-color:var(--accent); }
    .order-row .ot { display:flex; justify-content:space-between; align-items:center; margin-bottom:6px; }
    .order-row .ono { color:var(--accent); font-weight:700; font-size:14px; }
    .order-row .od { color:var(--dim); font-size:12px; margin-top:4px; line-height:1.5; }
    .order-row .od .disc { color:var(--yellow); }
    .order-row .op { font-size:11px; color:var(--purple); }
    /* 订单详情弹窗 */
    #detailOverlay { display:none; position:fixed; top:0;left:0;right:0;bottom:0; background:rgba(0,0,0,.55); z-index:6000; justify-content:center; align-items:flex-start; padding:24px 12px; overflow-y:auto; }
    #detailOverlay.show { display:flex; }
    .detail-card { background:var(--card); border:1px solid var(--border); border-radius:14px; padding:24px; width:520px; max-width:100%; box-shadow:0 16px 48px rgba(0,0,0,.5); }
    .detail-card h2 { color:var(--accent); font-size:17px; margin-bottom:14px; }
    .detail-card .d-line { display:flex; justify-content:space-between; padding:7px 0; border-bottom:1px solid var(--border); font-size:13px; }
    .detail-card .d-line.total { border-bottom:none; font-size:17px; font-weight:700; margin-top:10px; padding-top:12px; border-top:2px solid var(--accent); }
    .detail-card .d-total-val { color:var(--green); }
    .detail-close { margin-top:16px; text-align:right; }
    .tag { display:inline-block; padding:2px 8px; border-radius:999px; font-size:11px; font-weight:600; }
    .tag-admin { background:rgba(188,140,255,.15); color:var(--purple); }
    .tag-cashier { background:rgba(88,166,255,.15); color:var(--accent); }
    .empty-state { text-align:center; color:var(--dim); padding:40px; font-size:14px; }
    /* 滚动条 */
    *::-webkit-scrollbar { width:8px; }
    *::-webkit-scrollbar-track { background:transparent; }
    *::-webkit-scrollbar-thumb { background:var(--border); border-radius:4px; }
    @media(max-width:600px){ .detail-card { width:100%; padding:16px; } .container{margin:10px auto;padding:0 10px;} }
</style>
</head>
<body>

<div class="header">
    <div style="display:flex;align-items:center;gap:12px">
        <h1>📜 订单记录</h1>
        <a href="cashier.php" class="btn btn-ghost" style="text-decoration:none;font-size:12px">← 返回收银台</a>
    </div>
    <div style="display:flex;align-items:center;gap:10px">
        <?php if ($loggedIn): ?>
        <span class="tag <?= $role==='admin'?'tag-admin':'tag-cashier' ?>"><?= $role==='admin'?'管理员':'收银员' ?> · <?= esc($name) ?></span>
        <a href="cashier.php?logout=1" class="btn btn-red" style="text-decoration:none;font-size:12px">退出</a>
        <?php else: ?>
        <a href="cashier.php" class="btn btn-blue" style="text-decoration:none;font-size:12px">登录收银台</a>
        <?php endif; ?>
    </div>
</div>

<div class="container">
    <!-- 搜索/筛选 -->
    <div class="panel">
        <h3>🔍 筛选与搜索</h3>
        <div class="search-bar">
            <input type="text" id="searchPlayer" placeholder="玩家名..." oninput="loadOrders()">
            <input type="text" id="searchOrderNo" placeholder="订单号..." oninput="loadOrders()">
            <select id="filterPayMode" onchange="loadOrders()">
                <option value="">全部收款方式</option>
                <option value="bond">债券扣款</option>
                <option value="cash">现金记账</option>
            </select>
            <select id="limit" onchange="loadOrders()">
                <option value="30">最近 30 条</option>
                <option value="50" selected>最近 50 条</option>
                <option value="100">最近 100 条</option>
                <option value="200">最近 200 条</option>
            </select>
            <button class="btn btn-blue" onclick="loadOrders()">刷新</button>
        </div>
    </div>

    <!-- 订单列表 -->
    <div class="panel">
        <h3>📋 订单列表 <span id="orderCount" style="font-weight:normal;color:var(--dim);font-size:12px"></span></h3>
        <div id="orderList"><div class="empty-state">加载中…</div></div>
    </div>
</div>

<!-- 订单详情弹窗 -->
<div id="detailOverlay">
    <div class="detail-card" id="detailBody"><!-- JS填充 --></div>
</div>

<script>
const STATE = <?php echo $initialState; ?>;
function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;}[c])); }
async function api(url, opts, timeoutMs = 20000) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
        const r = await fetch(url, Object.assign(
            {headers: {'X-Requested-With': 'orders'}, credentials: 'same-origin'},
            opts || {},
            {signal: ctrl.signal}
        ));
        return await r.json();
    } finally {
        clearTimeout(timer);
    }
}

async function loadOrders() {
    const el = document.getElementById('orderList');
    el.innerHTML = '<div class="empty-state">加载中…</div>';
    try {
        const params = new URLSearchParams();
        params.set('action', 'order_list');
        params.set('limit', document.getElementById('limit').value || '50');
        const p = document.getElementById('searchPlayer').value.trim();
        const o = document.getElementById('searchOrderNo').value.trim();
        const pm = document.getElementById('filterPayMode').value;
        if (p) params.set('player', p);
        if (o) params.set('order_no', o);
        if (pm) params.set('pay_mode', pm);

        const d = await api('api/cashier.php?' + params.toString());
        document.getElementById('orderCount').textContent = '';

        if (!d.success) {
            el.innerHTML = '<div class="empty-state">加载失败：' + esc(d.message || '未知错误') + '，<a href="javascript:loadOrders()" style="color:var(--accent)">点击重试</a></div>';
            return;
        }
        if (!d.data || !d.data.length) {
            el.innerHTML = '<div class="empty-state">暂无符合条件的订单</div>';
            return;
        }

        document.getElementById('orderCount').textContent = '(共 ' + d.data.length + ' 条)';
        el.innerHTML = d.data.map(o => {
            const items = (o.items_detail && Array.isArray(o.items_detail)) ? o.items_detail.map(i=>esc(i.name)+'x'+i.amount).join('、') : '';
            const disc = (parseInt(o.discount_percent)>0) ? ` <span class="disc">(${o.discount_percent}% off -${o.discount_amount})</span>` : '';
            const pmBadge = o.payment_method === 'cash' ? '<span style="color:var(--yellow)">[现金]</span>' : '';
            return `<div class="order-row" data-order='${escapeHtmlAttr(JSON.stringify(o))}'>
                <div class="ot">
                    <span class="ono">${esc(o.order_no)} ${pmBadge}</span>
                    <span>${o.operator_type==='admin'?'管理员':'收银员'}:${esc(o.operator_name)}</span>
                </div>
                <div class="od">玩家 <b>${esc(o.player_name)}</b> · ${items}${disc}</div>
                <div class="od">实收 <b style="color:var(--green)">${o.total_price}</b> 债券 · ${new Date((o.created_at||0)*1000).toLocaleString('zh-CN')}</div>
            </div>`;
        }).join('');
    } catch(e) {
        const msg = (e && e.name === 'AbortError') ? '加载超时（服务器繁忙），' : '加载失败，';
        el.innerHTML = '<div class="empty-state">' + msg + '<a href="javascript:loadOrders()" style="color:var(--accent)">点击重试</a></div>';
    }
}

function escapeHtmlAttr(str) {
    // 安全转义JSON用于HTML属性（data-order）：先转 & 再转引号，
    // 避免数据中的 & 被浏览器当成实体解码而破坏 JSON，且对单/双引号均做转义
    return String(str)
        .replace(/&/g, "&amp;")
        .replace(/'/g, "&#39;")
        .replace(/"/g, "&quot;");
}

function showDetail(orderData) {
    const items = (orderData.items_detail && Array.isArray(orderData.items_detail)) ? orderData.items_detail : [];
    const itemsHtml = items.map(it =>
        `<div class="d-line"><span>${esc(it.name)} × ${it.amount}</span><span>${it.line_total} 债券</span></div>`
    ).join('');

    const discLine = parseInt(orderData.discount_percent)>0
        ? `<div class="d-line" style="color:var(--yellow)"><span>手动折扣 ${orderData.discount_percent}%</span><span>-${orderData.discount_amount} 债券</span></div>` : '';

    const payModeText = orderData.payment_method === 'cash' ? '现金（仅记账）' : '债券扣款';
    const settlementName = orderData.settlement === 'shulker' ? '潜影盒打包' : '塞背包（环保单）';

    const card = document.getElementById('detailBody');
    card.innerHTML = `
        <h2>📋 订单详情</h2>
        <div class="d-line"><span>订单号</span><span style="color:var(--accent);font-weight:700">${esc(orderData.order_no)}</span></div>
        <div class="d-line"><span>时间</span><span>${new Date((orderData.created_at||0)*1000).toLocaleString('zh-CN')}</span></div>
        <div class="d-line"><span>玩家</span><span><b>${esc(orderData.player_name)}</b></span></div>
        <div class="d-line"><span>操作员</span><span>${orderData.operator_type==='admin'?'管理员':esc(orderData.operator_name)}</span></div>
        <div class="d-line"><span>结算方式</span><span>${settlementName}</span></div>
        <div class="d-line"><span>收款方式</span><span>${payModeText}</span></div>
        <div class="d-line" style="border-top:1px dashed var(--border);margin:8px 0"></div>
        ${itemsHtml}
        <div class="d-line" style="border-top:1px dashed var(--border);margin:8px 0"><span>商品小计</span><span>${orderData.subtotal || 0} 债券</span></div>
        ${discLine}
        <div class="d-line total"><span>实收合计</span><span class="d-total-val">${orderData.total_price} 债券</span></div>
        <div class="detail-close">
            <button class="btn btn-ghost" onclick="closeDetail()">关闭</button>
        </div>`;
    document.getElementById('detailOverlay').classList.add('show');
}

function closeDetail() { document.getElementById('detailOverlay').classList.remove('show'); }
document.getElementById('detailOverlay').addEventListener('click', e => { if(e.target.id === 'detailOverlay') closeDetail(); });

// ★ 订单行点击事件委托（替代 onclick 内联 JSON，避免引号冲突导致 SyntaxError）
document.getElementById('orderList').addEventListener('click', e => {
    const row = e.target.closest('.order-row');
    if (!row) return;
    try { showDetail(JSON.parse(row.dataset.order)); } catch(err) { console.error('订单数据解析失败:', err); }
});

// 启动
if (STATE.logged_in) loadOrders();
else document.getElementById('orderList').innerHTML = '<div class="empty-state">请先<a href="cashier.php" style="color:var(--accent)">登录收银台</a>后查看订单</div>';

// ★ 全局错误捕获：捕获任何未处理的JS错误并显示在页面上（便于排查"全新浏览器仍报错"）
window.onerror = function(msg, url, line, col, err) {
    var el = document.getElementById('orderList');
    if (el) {
        var info = 'JS Error: ' + String(msg);
        if (url) info += '\n文件: ' + url + (line ? ':' + line : '') + (col ? ':' + col : '');
        if (err && err.stack) info += '\n堆栈: ' + err.stack.substring(0, 500);
        el.innerHTML = '<div class="empty-state" style="color:var(--red);word-break:break-all;text-align:left;padding:20px;border:1px solid var(--red);border-radius:8px;margin-top:10px">⚠️ 页面脚本错误:\n<pre style="white-space:pre-wrap;color:var(--red)">' + esc(info) + '</pre>\n<a href="javascript:location.reload()" style="color:var(--accent)">[刷新页面]</a></div>';
    }
    return false;
};
// ★ Promise未捕获 rejection 捕获
window.onunhandledrejection = function(e) {
    var el = document.getElementById('orderList');
    if (el) {
        el.innerHTML = '<div class="empty-state" style="color:var(--yellow)">⚠️ 异步请求失败: ' + esc(String(e.reason || 'unknown')) + ' <a href="javascript:loadOrders()" style="color:var(--accent)">[重试]</a></div>';
    }
};
</script>
</body>
</html>
