<?php
require_once __DIR__ . '/core.php';
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }
if (session_status() === PHP_SESSION_NONE) session_start();

$action = getParam('action', 'page');

// ===== 初始化快照表 =====
function ensureSnapshotTable() {
    try {
        $db = getDB();
        $db->exec("CREATE TABLE IF NOT EXISTS online_snapshots (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            snapshot_time INTEGER NOT NULL,
            online_count INTEGER NOT NULL DEFAULT 0
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_snap_time ON online_snapshots(snapshot_time)");
    } catch (\Throwable $e) {
        debugLog('[online_curve] 建表异常: ' . $e->getMessage());
    }
}

// ===== 记录快照 =====
function recordSnapshot() {
    ensureSnapshotTable();
    $count = 0;
    try {
        $db = getDB();
        $res = $db->query("SELECT COUNT(*) AS cnt FROM online_players");
        $row = $res->fetchArray(SQLITE3_ASSOC);
        $count = (int)($row['cnt'] ?? 0);
        $now = time();
        $stmt = $db->prepare("INSERT INTO online_snapshots (snapshot_time, online_count) VALUES (:t, :c)");
        $stmt->bindValue(':t', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':c', $count, SQLITE3_INTEGER);
        $stmt->execute();
    } catch (\Throwable $e) {
        debugLog('[online_curve] 记录快照异常: ' . $e->getMessage());
    }
    return $count;
}

// ===== 获取24小时聚合数据（按小时） =====
function getHourlyData() {
    ensureSnapshotTable();
    // 24小时前的时间戳
    $since = time() - 86400;
    // 按自然小时聚合：分桶到 h0~h23，取平均值
    $buckets = [];
    for ($i = 0; $i < 24; $i++) $buckets[$i] = ['hour' => $i, 'count' => 0, 'samples' => 0];
    try {
        $db = getDB();
        $stmt = $db->prepare("SELECT snapshot_time, online_count FROM online_snapshots WHERE snapshot_time >= :since ORDER BY snapshot_time ASC");
        $stmt->bindValue(':since', $since, SQLITE3_INTEGER);
        $res = $stmt->execute();
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $h = (int)date('G', (int)$row['snapshot_time']); // 0-23 无前导零
            $buckets[$h]['count'] += (int)$row['online_count'];
            $buckets[$h]['samples']++;
        }
    } catch (\Throwable $e) {
        debugLog('[online_curve] 查询异常: ' . $e->getMessage());
    }
    $result = [];
    foreach ($buckets as $b) {
        $avg = $b['samples'] > 0 ? round($b['count'] / $b['samples'], 1) : 0;
        $result[] = [
            'hour' => sprintf('%02d:00', $b['hour']),
            'avg' => $avg,
            'max' => $b['samples'] > 0 ? round($b['count'] / max(1,$b['samples']), 1) : 0, // same as avg for now
            'samples' => $b['samples'],
        ];
    }
    return ['buckets' => $result, 'total_snapshots' => array_sum(array_column($result, 'samples'))];
}

// ===== 路由 =====
switch ($action) {
    case 'record':
        $c = recordSnapshot();
        success(['recorded' => true, 'online_count' => $c]);
        exit;

    case 'data':
        success(getHourlyData());
        exit;

    case 'page':
    default:
        // 页面加载时也记录一次快照
        $currentOnline = recordSnapshot();
        $initialState = json_encode(array_merge(
            ['current_online' => $currentOnline],
            getHourlyData()
        ), JSON_UNESCAPED_UNICODE);
        break;
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
<title>SDF1 - 24小时在线曲线</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"></script>
<style>
:root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --dim:#8b949e; --accent:#58a6ff; --green:#3fb950; --red:#f85149; --yellow:#d29922; --purple:#bc8cff; --chart-grid:#30363d; }
* { margin:0; padding:0; box-sizing:border-box; }
body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; min-height:100vh; }
.header { background:linear-gradient(135deg,#1a1e2e,#0d1117); border-bottom:1px solid var(--border); padding:14px 24px; display:flex; justify-content:space-between; align-items:center; position:sticky; top:0; z-index:50; }
.header h1 { font-size:18px; color:var(--accent); }
.header .info { font-size:13px; color:var(--dim); }
.container { max-width:960px; margin:24px auto; padding:0 16px; }
.stat-row { display:flex; gap:14px; margin-bottom:20px; flex-wrap:wrap; }
.stat-card { flex:1; min-width:140px; background:var(--card); border:1px solid var(--border); border-radius:12px; padding:16px; text-align:center; }
.stat-card .val { font-size:26px; font-weight:700; color:var(--accent); }
.stat-card .lbl { font-size:12px; color:var(--dim); margin-top:4px; }
.chart-wrap { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:20px; position:relative; }
.chart-wrap h3 { font-size:15px; color:var(--accent); margin-bottom:14px; }
.chart-wrap canvas { max-height:360px; }
.empty-chart { text-align:center; padding:60px 20px; color:var(--dim); }
.empty-chart .msg { font-size:16px; margin-bottom:8px; }
.empty-chart .sub { font-size:13px; }
.refresh-hint { text-align:center; font-size:12px; color:var(--dim); margin-top:12px; }
#loading { text-align:center; padding:40px; color:var(--dim); }
@media(max-width:600px) {
    .stat-card { min-width:calc(50% - 7px); }
}
</style>
</head>
<body>
<div class="header">
    <h1>📈 24小时在线曲线</h1>
    <div class="info">每60秒刷新 · 自动记录快照</div>
</div>
<div class="container">
    <div id="loading">加载中...</div>
    <div id="content" style="display:none">
        <div class="stat-row">
            <div class="stat-card"><div class="val" id="curOnline">0</div><div class="lbl">当前在线</div></div>
            <div class="stat-card"><div class="val" id="totalSnap">0</div><div class="lbl">快照样本数</div></div>
            <div class="stat-card"><div class="val" id="peakHour">-</div><div class="lbl">高峰时段</div></div>
            <div class="stat-card"><div class="val" id="peakAvg">0</div><div class="lbl">高峰均值</div></div>
        </div>
        <div class="chart-wrap">
            <h3>各时段平均在线人数</h3>
            <div id="chartContainer">
                <canvas id="curveChart"></canvas>
            </div>
            <div id="emptyHint" class="empty-chart" style="display:none">
                <div class="msg">📊 数据收集中</div>
                <div class="sub">页面每60秒记录一次快照，积累数据后将自动生成曲线</div>
            </div>
        </div>
        <div class="refresh-hint" id="refreshHint"></div>
    </div>
</div>
<script>
const state = <?= $initialState ?>;
let chart = null;

function renderChart(buckets) {
    const hasData = buckets.some(b => b.samples > 0);
    const ctx = document.getElementById('curveChart').getContext('2d');
    const labels = buckets.map(b => b.hour);
    const values = buckets.map(b => b.avg);

    document.getElementById('emptyHint').style.display = hasData ? 'none' : 'block';

    if (chart) { chart.destroy(); chart = null; }

    const isDark = getComputedStyle(document.documentElement).getPropertyValue('--bg').trim() === '#0d1117';
    const textColor = isDark ? '#e6edf3' : '#1f2328';
    const gridColor = isDark ? '#30363d' : '#d0d7de';

    if (!hasData) {
        document.getElementById('curveChart').style.display = 'none';
        return;
    }
    document.getElementById('curveChart').style.display = 'block';

    chart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{
                label: '平均在线',
                data: values,
                backgroundColor: 'rgba(88,166,255,0.55)',
                borderColor: '#58a6ff',
                borderWidth: 1,
                borderRadius: 3,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { labels: { color: textColor, font: { size: 12 } } },
                tooltip: {
                    backgroundColor: isDark ? '#161b22' : '#fff',
                    titleColor: textColor,
                    bodyColor: textColor,
                    borderColor: isDark ? '#30363d' : '#d0d7de',
                    borderWidth: 1,
                    callbacks: {
                        afterBody: function(ctx) {
                            const i = ctx[0].dataIndex;
                            const s = buckets[i].samples;
                            return '采样数: ' + s;
                        }
                    }
                }
            },
            scales: {
                x: {
                    ticks: { color: textColor, font: { size: 10 }, maxRotation: 45 },
                    grid: { color: gridColor }
                },
                y: {
                    beginAtZero: true,
                    ticks: { color: textColor, font: { size: 11 }, stepSize: 1 },
                    grid: { color: gridColor }
                }
            }
        }
    });
}

function render(data) {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('content').style.display = 'block';

    document.getElementById('curOnline').textContent = data.current_online;
    document.getElementById('totalSnap').textContent = data.total_snapshots;

    // 找高峰时段
    let peakIdx = 0, peakVal = 0;
    data.buckets.forEach((b, i) => {
        if (b.avg > peakVal) { peakVal = b.avg; peakIdx = i; }
    });
    document.getElementById('peakHour').textContent = peakVal > 0 ? data.buckets[peakIdx].hour : '-';
    document.getElementById('peakAvg').textContent = peakVal > 0 ? peakVal.toFixed(1) : '0';

    renderChart(data.buckets);
    document.getElementById('refreshHint').textContent = '更新：' + new Date().toLocaleTimeString('zh-CN', {hour12:false});
}

render(state);

// 每60秒刷新 + 记录快照
setInterval(() => {
    fetch('online_curve.php?action=record&_t=' + Date.now()).then(r=>r.json()).then(d => {
        if (d.success) {
            document.getElementById('curOnline').textContent = d.data.online_count;
        }
    }).catch(()=>{});
    fetch('online_curve.php?action=data&_t=' + Date.now()).then(r=>r.json()).then(d => {
        if (d.success) render(Object.assign(d.data, {current_online: parseInt(document.getElementById('curOnline').textContent) || 0}));
    }).catch(()=>{});
}, 60000);
</script>
</body>
</html>
