<?php
/**
 * 充值订单记录页面（管理员对账专用）
 * 显示充值订单，支持手动对账、查看支付状态
 */

error_reporting(E_ALL);
ini_set('display_errors', '0');
set_error_handler(function($errno, $errstr, $errfile, $errline) {
    $types = [E_WARNING=>'Warning',E_NOTICE=>'Notice',E_USER_ERROR=>'UserError',E_USER_WARNING=>'UserWarning',E_USER_NOTICE=>'UserNotice',E_STRICT=>'Strict',E_DEPRECATED=>'Deprecated',E_RECOVERABLE_ERROR=>'Recoverable'];
    $type = isset($types[$errno]) ? $types[$errno] : "Error[$errno]";
    if (file_exists(__DIR__ . '/core.php')) {
        if (!function_exists('debugLog')) {
            function debugLog($msg) {
                @file_put_contents(__DIR__ . '/db/debug.log', "[" . date('Y-m-d H:i:s') . "] [recharge_orders.php] $msg\n", FILE_APPEND | LOCK_EX);
            }
        }
        debugLog("[recharge_orders.php] PHP {$type}: {$errstr} in {$errfile}:{$errline}");
    }
    return true;
});

require_once __DIR__ . '/core.php';

if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }

if (session_status() === PHP_SESSION_NONE) session_start();

// 检查管理员登录
if (!isAdminLoggedIn()) {
    header('Location: admin.php');
    exit;
}

$initialState = json_encode([
    'logged_in' => true,
    'role' => 'admin',
    'username' => 'admin',
], JSON_UNESCAPED_UNICODE);
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate, proxy-revalidate">
<meta http-equiv="Pragma" content="no-cache">
<meta http-equiv="Expires" content="0">
<title>SDF1 - 充值订单对账</title>
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
    .container { max-width:1200px; margin:20px auto; padding:0 16px; }
    .panel { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:18px; margin-bottom:16px; }
    .panel h3 { font-size:15px; color:var(--accent); margin-bottom:12px; display:flex; align-items:center; gap:8px; }
    /* 搜索栏 */
    .search-bar { display:flex; gap:10px; flex-wrap:wrap; margin-bottom:16px; }
    .search-bar input, .search-bar select { padding:9px 12px; background:var(--bg); border:1px solid var(--border); border-radius:7px; color:var(--text); font-size:13px; outline:none; flex:1; min-width:160px; }
    .search-bar input:focus { border-color:var(--accent); }
    /* 统计卡片 */
    .stats { display:grid; grid-template-columns:repeat(auto-fit, minmax(200px, 1fr)); gap:12px; margin-bottom:20px; }
    .stat-card { background:var(--card); border:1px solid var(--border); border-radius:10px; padding:16px; text-align:center; }
    .stat-card .stat-value { font-size:28px; font-weight:700; margin-bottom:4px; }
    .stat-card .stat-label { font-size:12px; color:var(--dim); }
    .stat-card.green .stat-value { color:var(--green); }
    .stat-card.yellow .stat-value { color:var(--yellow); }
    .stat-card.red .stat-value { color:var(--red); }
    .stat-card.blue .stat-value { color:var(--accent); }
    /* 订单列表 */
    .order-row { font-size:13px; padding:14px; background:var(--bg); border:1px solid var(--border); border-radius:10px; margin-bottom:10px; transition:border-color .2s; cursor:pointer; }
    .order-row:hover { border-color:var(--accent); }
    .order-row .ot { display:flex; justify-content:space-between; align-items:center; margin-bottom:6px; }
    .order-row .ono { color:var(--accent); font-weight:700; font-size:14px; }
    .order-row .od { color:var(--dim); font-size:12px; margin-top:4px; line-height:1.5; }
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
    .tag-created { background:rgba(210,153,34,.15); color:var(--yellow); }
    .tag-paid { background:rgba(63,185,80,.15); color:var(--green); }
    .tag-failed { background:rgba(248,81,73,.15); color:var(--red); }
    .empty-state { text-align:center; color:var(--dim); padding:40px; font-size:14px; }
    /* 滚动条 */
    *::-webkit-scrollbar { width:8px; }
    *::-webkit-scrollbar-track { background:transparent; }
    *::-webkit-scrollbar-thumb { background:var(--border); border-radius:4px; }
    @media(max-width:600px){ .detail-card { width:100%; padding:16px; } .container{margin:10px auto;padding:0 10px;} .stats{grid-template-columns:1fr 1fr;} }
</style>
</head>
<body>

<div class="header">
    <div style="display:flex;align-items:center;gap:12px">
        <h1>💳 充值订单对账</h1>
        <a href="admin.php" class="btn btn-ghost" style="text-decoration:none;font-size:12px">← 返回管理后台</a>
    </div>
    <div style="display:flex;align-items:center;gap:10px">
        <span class="tag tag-paid">管理员</span>
        <button class="btn btn-green" onclick="manualReconcile()">🔄 立即对账</button>
    </div>
</div>

<div class="container">
    <!-- 统计卡片 -->
    <div class="stats">
        <div class="stat-card blue">
            <div class="stat-value" id="statTotal">-</div>
            <div class="stat-label">总订单数</div>
        </div>
        <div class="stat-card green">
            <div class="stat-value" id="statPaid">-</div>
            <div class="stat-label">已支付</div>
        </div>
        <div class="stat-card yellow">
            <div class="stat-value" id="statCreated">-</div>
            <div class="stat-label">待支付</div>
        </div>
        <div class="stat-card red">
            <div class="stat-value" id="statTotalMoney">-</div>
            <div class="stat-label">总金额(元)</div>
        </div>
    </div>

    <!-- 搜索/筛选 -->
    <div class="panel">
        <h3>🔍 筛选与搜索</h3>
        <div class="search-bar">
            <input type="text" id="searchPlayer" placeholder="玩家名..." oninput="loadOrders()">
            <input type="text" id="searchOrderNo" placeholder="订单号..." oninput="loadOrders()">
            <select id="filterStatus" onchange="loadOrders()">
                <option value="">全部状态</option>
                <option value="paid">已支付</option>
                <option value="created">待支付</option>
            </select>
            <select id="limit" onchange="loadOrders()">
                <option value="30">最近 30 条</option>
                <option value="50" selected>最近 50 条</option>
                <option value="100">最近 100 条</option>
                <option value="200">最近 200 条</option>
            </select>
        </div>
    </div>

    <!-- 订单列表 -->
    <div class="panel">
        <h3>📋 充值订单列表</h3>
        <div id="orderList"><div class="empty-state">加载中...</div></div>
    </div>
</div>

<!-- 订单详情弹窗 -->
<div id="detailOverlay" onclick="if(event.target===this)closeDetail()">
    <div class="detail-card">
        <h2>订单详情</h2>
        <div id="detailContent"></div>
        <div class="detail-close">
            <button class="btn btn-ghost" onclick="closeDetail()">关闭</button>
        </div>
    </div>
</div>

<script>
const A = 'api/recharge_orders_api.php';
let orders = [];

// 加载订单列表
async function loadOrders() {
    const player = document.getElementById('searchPlayer').value.trim();
    const orderNo = document.getElementById('searchOrderNo').value.trim();
    const status = document.getElementById('filterStatus').value;
    const limit = document.getElementById('limit').value;
    
    try {
        const params = new URLSearchParams({ action: 'list', limit });
        if (player) params.append('player', player);
        if (orderNo) params.append('order_no', orderNo);
        if (status) params.append('status', status);
        
        const res = await fetch(A + '?' + params.toString());
        const data = await res.json();
        
        if (data.error) {
            document.getElementById('orderList').innerHTML = '<div class="empty-state">加载失败: ' + data.error.message + '</div>';
            return;
        }
        
        orders = data.orders || [];
        updateStats(data.stats || {});
        renderOrders();
    } catch (e) {
        console.error('加载失败:', e);
        document.getElementById('orderList').innerHTML = '<div class="empty-state">网络错误，请重试</div>';
    }
}

// 更新统计
function updateStats(stats) {
    document.getElementById('statTotal').textContent = stats.total || 0;
    document.getElementById('statPaid').textContent = stats.paid || 0;
    document.getElementById('statCreated').textContent = stats.created || 0;
    document.getElementById('statTotalMoney').textContent = stats.totalMoney || '0.00';
}

// 渲染订单列表
function renderOrders() {
    const container = document.getElementById('orderList');
    if (!orders.length) {
        container.innerHTML = '<div class="empty-state">暂无订单记录</div>';
        return;
    }
    
    container.innerHTML = orders.map(o => {
        const statusTag = o.status === 'paid' 
            ? '<span class="tag tag-paid">已支付</span>' 
            : '<span class="tag tag-created">待支付</span>';
        const createdTime = new Date(o.created_at * 1000).toLocaleString('zh-CN');
        const paidTime = o.paid_at ? new Date(o.paid_at * 1000).toLocaleString('zh-CN') : '-';
        
        return `
            <div class="order-row" onclick="showDetail('${o.out_trade_no}')">
                <div class="ot">
                    <span class="ono">${o.out_trade_no}</span>
                    ${statusTag}
                </div>
                <div class="od">
                    玩家: ${o.player_name} | 金额: ¥${o.money} | 债券: ${o.bond_amount}
                </div>
                <div class="op">
                    创建: ${createdTime} | 支付: ${paidTime}
                </div>
            </div>
        `;
    }).join('');
}

// 显示订单详情
function showDetail(outTradeNo) {
    const order = orders.find(o => o.out_trade_no === outTradeNo);
    if (!order) return;
    
    const createdTime = new Date(order.created_at * 1000).toLocaleString('zh-CN');
    const paidTime = order.paid_at ? new Date(order.paid_at * 1000).toLocaleString('zh-CN') : '-';
    
    const content = `
        <div class="d-line"><span>订单号</span><span style="color:var(--accent)">${order.out_trade_no}</span></div>
        <div class="d-line"><span>玩家名</span><span>${order.player_name}</span></div>
        <div class="d-line"><span>支付金额</span><span style="color:var(--green)">¥${order.money}</span></div>
        <div class="d-line"><span>债券数量</span><span style="color:var(--yellow)">${order.bond_amount}</span></div>
        <div class="d-line"><span>订单状态</span><span>${order.status === 'paid' ? '✅ 已支付' : '⏳ 待支付'}</span></div>
        <div class="d-line"><span>平台交易号</span><span>${order.trade_no || '-'}</span></div>
        <div class="d-line"><span>创建时间</span><span>${createdTime}</span></div>
        <div class="d-line"><span>支付时间</span><span>${paidTime}</span></div>
        <div class="d-line"><span>商品名称</span><span>${order.name || '-'}</span></div>
        <div class="d-line"><span>签名</span><span style="font-size:11px;color:var(--dim);word-break:break-all">${order.platform_sign || '-'}</span></div>
    `;
    
    document.getElementById('detailContent').innerHTML = content;
    document.getElementById('detailOverlay').classList.add('show');
}

// 关闭详情弹窗
function closeDetail() {
    document.getElementById('detailOverlay').classList.remove('show');
}

// 手动对账
async function manualReconcile() {
    if (!confirm('立即对账将查询支付平台已支付订单并补写流水，是否继续？')) return;
    
    try {
        const res = await fetch('api/poller_online.php');
        const data = await res.json();
        
        if (data.error) {
            alert('对账失败: ' + data.error);
        } else if (data.result === 'already_running') {
            alert('另一个对账进程正在运行，请稍后重试');
        } else if (data.result === 'ok') {
            alert(`对账完成！处理 ${data.processed} 笔，跳过 ${data.skipped} 笔`);
            loadOrders(); // 刷新列表
        } else {
            alert('对账结果: ' + (data.detail || data.result));
        }
    } catch (e) {
        console.error('对账失败:', e);
        alert('对账请求失败，请重试');
    }
}

// 页面加载
document.addEventListener('DOMContentLoaded', loadOrders);

// 键盘快捷键
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeDetail();
});
</script>
</body>
</html>
