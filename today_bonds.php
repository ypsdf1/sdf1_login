<?php
require_once __DIR__ . '/core.php';
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }
if (session_status() === PHP_SESSION_NONE) session_start();

// 今日零时时间戳（北京时间）
$tz = new DateTimeZone('Asia/Shanghai');
$now = new DateTime('now', $tz);
$todayStart = (clone $now)->setTime(0, 0, 0)->getTimestamp();
$todayEnd = $todayStart + 86400;
$todayLabel = $now->format('Y-m-d');

// JSON API 模式：?action=data
$isApi = (getParam('action') === 'data');

function getTodayBondData() {
    global $todayStart, $todayEnd;
    $spendData = [];
    $incomeData = [];
    try {
        $db = getDB();
        // ==========================================
        // 单一数据源：game_transactions（Java同步的完整交易记录）
        // 每笔交易都有 balance_before / balance_after，
        // 用余额变化方向判断收入/支出，100% 准确无重复。
        //
        // 不使用 web_transactions 的原因：
        // 同一笔 Web 商城购买会同时出现在 web_transactions（PHP端）
        // 和 game_transactions（Java同步端），导致消费重复计算。
        // ==========================================
        // 收入：余额增加
        $stmt = $db->prepare("SELECT player_name, SUM(balance_after - balance_before) AS total_income FROM game_transactions WHERE balance_after > balance_before AND tx_time >= :s1 AND tx_time < :e1 GROUP BY player_name");
        $stmt->bindValue(':s1', $todayStart, SQLITE3_INTEGER);
        $stmt->bindValue(':e1', $todayEnd, SQLITE3_INTEGER);
        $res = $stmt->execute();
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $incomeData[$row['player_name']] = ($incomeData[$row['player_name']] ?? 0) + (int)$row['total_income'];
        }
        // 消费：余额减少
        $stmt2 = $db->prepare("SELECT player_name, SUM(balance_before - balance_after) AS total_spend FROM game_transactions WHERE balance_after < balance_before AND tx_time >= :s2 AND tx_time < :e2 GROUP BY player_name");
        $stmt2->bindValue(':s2', $todayStart, SQLITE3_INTEGER);
        $stmt2->bindValue(':e2', $todayEnd, SQLITE3_INTEGER);
        $res2 = $stmt2->execute();
        while ($row = $res2->fetchArray(SQLITE3_ASSOC)) {
            $spendData[$row['player_name']] = ($spendData[$row['player_name']] ?? 0) + (int)$row['total_spend'];
        }
    } catch (\Throwable $e) {
        debugLog('[today_bonds] 查询异常: ' . $e->getMessage());
    }
    $allPlayers = array_unique(array_merge(array_keys($spendData), array_keys($incomeData)));
    $rows = [];
    $totalSpend = 0; $totalIncome = 0;
    foreach ($allPlayers as $p) {
        $spend = $spendData[$p] ?? 0;
        $income = $incomeData[$p] ?? 0;
        $net = $income - $spend;
        $totalSpend += $spend; $totalIncome += $income;
        $rows[] = ['name' => (string)$p, 'spend' => $spend, 'income' => $income, 'net' => $net];
    }
    usort($rows, fn($a,$b) => $b['net'] - $a['net']);
    return [
        'rows' => $rows,
        'total_spend' => $totalSpend,
        'total_income' => $totalIncome,
        'net_total' => $totalIncome - $totalSpend,
        'player_count' => count($rows),
        'date_label' => $GLOBALS['todayLabel'],
    ];
}

if ($isApi) {
    success(getTodayBondData());
    exit;
}

$initialState = json_encode(getTodayBondData(), JSON_UNESCAPED_UNICODE);
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
<title>SDF1 - 今日债券变动</title>
<style>
:root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --dim:#8b949e; --accent:#58a6ff; --green:#3fb950; --red:#f85149; --yellow:#d29922; --purple:#bc8cff; }
* { margin:0; padding:0; box-sizing:border-box; }
body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; min-height:100vh; }
.header { background:linear-gradient(135deg,#1a1e2e,#0d1117); border-bottom:1px solid var(--border); padding:14px 24px; display:flex; justify-content:space-between; align-items:center; position:sticky; top:0; z-index:50; }
.header h1 { font-size:18px; color:var(--accent); }
.header .info { font-size:13px; color:var(--dim); }
.container { max-width:1100px; margin:20px auto; padding:0 16px; }
.stat-row { display:flex; gap:14px; margin-bottom:20px; flex-wrap:wrap; }
.stat-card { flex:1; min-width:150px; background:var(--card); border:1px solid var(--border); border-radius:12px; padding:18px; text-align:center; transition:border-color .2s; }
.stat-card:hover { border-color:var(--accent); }
.stat-card .val { font-size:28px; font-weight:700; }
.stat-card .val.green { color:var(--green); }
.stat-card .val.red { color:var(--red); }
.stat-card .val.blue { color:var(--accent); }
.stat-card .val.yellow { color:var(--yellow); }
.stat-card .lbl { font-size:12px; color:var(--dim); margin-top:4px; }
.panel { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:18px; margin-bottom:16px; }
.panel h3 { font-size:15px; color:var(--accent); margin-bottom:12px; display:flex; align-items:center; gap:8px; }
.table-wrap { overflow-x:auto; }
table { width:100%; border-collapse:collapse; font-size:13px; }
th { text-align:left; padding:10px 12px; color:var(--dim); border-bottom:1px solid var(--border); font-weight:600; white-space:nowrap; }
td { padding:10px 12px; border-bottom:1px solid var(--border); }
tr:hover td { background:rgba(88,166,255,0.04); }
.num { font-variant-numeric:tabular-nums; text-align:right; }
.positive { color:var(--green); }
.negative { color:var(--red); }
.zero { color:var(--dim); }
.empty { text-align:center; color:var(--dim); padding:40px; }
.refresh-hint { text-align:center; font-size:12px; color:var(--dim); margin-top:12px; }
#loading { text-align:center; padding:40px; color:var(--dim); }
@media(max-width:640px) {
    .stat-row { gap:8px; }
    .stat-card { min-width:calc(50% - 4px); padding:12px; }
    .stat-card .val { font-size:22px; }
    th,td { padding:8px 6px; font-size:12px; }
}
</style>
</head>
<body>
<div class="header">
    <h1>📊 今日债券变动</h1>
    <div class="info"><span id="dateLabel"></span> · 每30秒刷新</div>
</div>
<div class="container" id="app">
    <div id="loading">加载中...</div>
    <div id="content" style="display:none">
        <div class="stat-row">
            <div class="stat-card"><div class="val red" id="statSpend">0</div><div class="lbl">总消费</div></div>
            <div class="stat-card"><div class="val green" id="statIncome">0</div><div class="lbl">总收入</div></div>
            <div class="stat-card"><div class="val blue" id="statNet">0</div><div class="lbl">净变动</div></div>
            <div class="stat-card"><div class="val yellow" id="statPlayers">0</div><div class="lbl">活跃玩家</div></div>
        </div>
        <div class="panel">
            <h3>👤 玩家明细</h3>
            <div class="table-wrap">
                <table><thead><tr>
                    <th>玩家</th><th class="num">消费 ↓</th><th class="num">收入 ↓</th><th class="num">净变动</th>
                </tr></thead><tbody id="tbody"></tbody></table>
            </div>
        </div>
        <div class="refresh-hint" id="refreshHint"></div>
    </div>
</div>
<script>
const state = <?= $initialState ?>;
function fmt(n) { return n.toLocaleString('zh-CN'); }
function render(data) {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('content').style.display = 'block';
    document.getElementById('dateLabel').textContent = data.date_label;
    document.getElementById('statSpend').textContent = fmt(data.total_spend);
    document.getElementById('statIncome').textContent = fmt(data.total_income);
    const netEl = document.getElementById('statNet');
    netEl.textContent = (data.net_total >= 0 ? '+' : '') + fmt(data.net_total);
    netEl.style.color = data.net_total > 0 ? 'var(--green)' : data.net_total < 0 ? 'var(--red)' : 'var(--dim)';
    document.getElementById('statPlayers').textContent = data.player_count;
    const tbody = document.getElementById('tbody');
    if (data.rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="empty">今日暂无数据</td></tr>';
    } else {
        tbody.innerHTML = data.rows.map(r => {
            const netCls = r.net > 0 ? 'positive' : r.net < 0 ? 'negative' : 'zero';
            const netSign = r.net >= 0 ? '+' : '';
            return `<tr><td>${esc(r.name)}</td><td class="num negative">${fmt(r.spend)}</td><td class="num positive">${fmt(r.income)}</td><td class="num ${netCls}">${netSign}${fmt(r.net)}</td></tr>`;
        }).join('');
    }
    document.getElementById('refreshHint').textContent = '上次更新：' + new Date().toLocaleTimeString('zh-CN', {hour12:false});
}
function esc(s) { const d=document.createElement('div'); d.textContent=s||''; return d.innerHTML; }
render(state);
// 每30秒刷新
setInterval(() => {
    fetch('today_bonds.php?action=data&_t=' + Date.now()).then(r=>r.json()).then(d => {
        if (d.success) render(d);
    }).catch(()=>{});
}, 30000);
</script>
</body>
</html>
