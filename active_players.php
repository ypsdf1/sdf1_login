<?php
require_once __DIR__ . '/core.php';
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }
if (session_status() === PHP_SESSION_NONE) session_start();

$isApi = (getParam('action') === 'data');

function getActivePlayers() {
    $online = []; $count = 0;
    try {
        $db = getDB();
        // 确保表存在
        $db->exec("CREATE TABLE IF NOT EXISTS hourly_active_players (player_name TEXT PRIMARY KEY, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, hour_key TEXT NOT NULL)");
        $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER DEFAULT 0)");
        $currentHourKey = date('Y-m-d H');
        // ★ 查询本小时活跃过的所有玩家（包括已离线但本小时曾上线的）
        $res = $db->query("SELECT player_name, first_seen, last_seen FROM hourly_active_players WHERE hour_key = '" . SQLite3::escapeString($currentHourKey) . "' ORDER BY first_seen ASC");
        $now = time();
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            // 检查玩家当前是否在线（从online_players表判断）
            $checkStmt = $db->prepare("SELECT 1 FROM online_players WHERE player_name = :name");
            $checkStmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
            $checkRes = $checkStmt->execute();
            $isOnline = $checkRes->fetchArray() !== false;

            $online[] = [
                'name' => $row['player_name'],
                'login_time' => (int)$row['first_seen'],
                'login_time_fmt' => $row['first_seen'] > 0 ? date('H:i:s', (int)$row['first_seen']) : '-',
                'duration' => $row['first_seen'] > 0 ? $now - (int)$row['first_seen'] : 0,
                'is_online' => $isOnline,
                'last_seen' => (int)$row['last_seen'],
            ];
        }
        $count = count($online);
    } catch (\Throwable $e) {
        debugLog('[active_players] 查询异常: ' . $e->getMessage());
    }
    return ['online_list' => $online, 'online_count' => $count];
}

if ($isApi) {
    success(getActivePlayers());
    exit;
}

$initialState = json_encode(getActivePlayers(), JSON_UNESCAPED_UNICODE);
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
<title>SDF1 - 在线活跃</title>
<style>
:root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --dim:#8b949e; --accent:#58a6ff; --green:#3fb950; --red:#f85149; --yellow:#d29922; --purple:#bc8cff; }
* { margin:0; padding:0; box-sizing:border-box; }
body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; min-height:100vh; }
.header { background:linear-gradient(135deg,#1a1e2e,#0d1117); border-bottom:1px solid var(--border); padding:14px 24px; display:flex; justify-content:space-between; align-items:center; position:sticky; top:0; z-index:50; }
.header h1 { font-size:18px; color:var(--accent); }
.header .info { font-size:13px; color:var(--dim); }
.container { max-width:900px; margin:20px auto; padding:0 16px; }

/* 在线玩家卡片网格 */
.grid-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; flex-wrap:wrap; gap:8px; }
.grid-header h2 { font-size:15px; color:var(--accent); }
.grid-header .badge { background:var(--green); color:#000; padding:4px 12px; border-radius:20px; font-size:13px; font-weight:700; }

.player-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:12px; }
.player-card { background:var(--card); border:1px solid var(--border); border-radius:10px; padding:14px 16px; transition:border-color .2s; }
.player-card:hover { border-color:var(--accent); }
.player-card.offline { opacity:0.55; border-style:dashed; }
.player-card .name { font-size:15px; font-weight:700; color:var(--text); margin-bottom:4px; display:flex; align-items:center; gap:6px; }
.player-card .name .status-dot { width:8px; height:8px; border-radius:50%; display:inline-block; flex-shrink:0; }
.player-card .name .status-dot.on { background:var(--green); box-shadow:0 0 6px var(--green); }
.player-card .name .status-dot.off { background:var(--dim); }
.player-card .meta { font-size:12px; color:var(--dim); display:flex; justify-content:space-between; }
.player-card .meta .online-tag { color:var(--green); }
.player-card .meta .offline-tag { color:var(--yellow); }
.player-card .time-bar { margin-top:8px; height:4px; background:var(--bg); border-radius:4px; overflow:hidden; }
.player-card .time-bar .fill { height:100%; background:var(--green); border-radius:4px; transition:width 1s; }
.empty { grid-column:1/-1; text-align:center; padding:40px; color:var(--dim); }
.refresh-hint { text-align:center; font-size:12px; color:var(--dim); margin-top:16px; }
#loading { text-align:center; padding:40px; color:var(--dim); }
@media(max-width:500px) {
    .player-grid { grid-template-columns:1fr; }
}
</style>
</head>
<body>
<div class="header">
    <h1>🟢 本小时在线活跃</h1>
    <div class="info">每10秒刷新</div>
</div>
<div class="container">
    <div id="loading">加载中...</div>
    <div id="content" style="display:none">
        <div class="grid-header">
            <h2>👤 当前在线玩家</h2>
            <span class="badge" id="countBadge">0 人</span>
        </div>
        <div class="player-grid" id="playerGrid"></div>
        <div class="refresh-hint" id="refreshHint"></div>
    </div>
</div>
<script>
const state = <?= $initialState ?>;
function fmtDuration(sec) {
    if (sec < 60) return sec + '秒';
    if (sec < 3600) return Math.floor(sec/60) + '分' + (sec%60) + '秒';
    return Math.floor(sec/3600) + '时' + Math.floor((sec%3600)/60) + '分';
}
function render(data) {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('content').style.display = 'block';
    document.getElementById('countBadge').textContent = data.online_count + ' 人';
    const grid = document.getElementById('playerGrid');
    if (data.online_list.length === 0) {
        grid.innerHTML = '<div class="empty">本小时暂无玩家活跃</div>';
    } else {
        // 在线玩家排前面，离线玩家排后面
        const sorted = [...data.online_list].sort((a, b) => (b.is_online ? 1 : 0) - (a.is_online ? 1 : 0));
        grid.innerHTML = sorted.map(p => {
            const mx = 6 * 3600;
            const pct = Math.min(100, Math.round(p.duration / mx * 100));
            const cls = p.is_online ? 'player-card' : 'player-card offline';
            const dot = p.is_online ? '<span class="status-dot on"></span>' : '<span class="status-dot off"></span>';
            const tag = p.is_online
                ? `<span class="online-tag">在线 ${fmtDuration(p.duration)}</span>`
                : `<span class="offline-tag">已离线</span>`;
            return `<div class="${cls}"><div class="name">${dot}${esc(p.name)}</div><div class="meta"><span>首次：${p.login_time_fmt}</span>${tag}</div><div class="time-bar"><div class="fill" style="width:${pct}%;background:${p.is_online ? 'var(--green)' : 'var(--dim)'}"></div></div></div>`;
        }).join('');
    }
    document.getElementById('refreshHint').textContent = '更新：' + new Date().toLocaleTimeString('zh-CN', {hour12:false});
}
function esc(s) { const d=document.createElement('div'); d.textContent=s||''; return d.innerHTML; }
render(state);
setInterval(() => {
    fetch('active_players.php?action=data&_t=' + Date.now()).then(r=>r.json()).then(d => {
        if (d.success) render(d.data);
    }).catch(()=>{});
}, 10000);
</script>
</body>
</html>
