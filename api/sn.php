<?php
/**
 * SN 防刷系统 API
 *
 * Java -> PHP（secret 校验）：
 *   push_held      手持/背包 SN 快照（每 10~30 秒）
 *   push_events    事件流（拾取归属不符 / 销毁 / 非法处理）
 *   push_catalog   目录快照（物品全量 + 日志 + 出入库尾部）
 *   pull_commands  拉取待执行命令（注销 / 补发 / 报失核查 / 永久绑定）
 *   ack_commands   回传命令执行结果
 *
 * 玩家端（token 校验）：
 *   my_list / query / report_lost / reissue / cancel
 *
 * 管理端（admin session）：
 *   admin_list / admin_detail / admin_cmd / admin_stats
 *   admin_lost_list / admin_lost_handle
 */

while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store, no-cache, must-revalidate');

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');

if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }

register_shutdown_function(function () {
    while (ob_get_level() > 0) { @ob_end_flush(); }
});

require_once __DIR__ . '/../core.php';

if (session_status() === PHP_SESSION_NONE) { session_start(); }

// ★ POST body 只读一次（PHP 流不可重复读取）
$SN_BODY = null;
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    $raw = @file_get_contents('php://input');
    if ($raw) { $SN_BODY = json_decode($raw, true); }
}

$action = getParam('action', '');

try {
    switch ($action) {
        // ===== Java -> PHP =====
        case 'push_held':      snPushHeld(); break;
        case 'push_events':    snPushEvents(); break;
        case 'push_catalog':   snPushCatalog(); break;
        case 'pull_commands':  snPullCommands(); break;
        case 'ack_commands':   snAckCommands(); break;

        // ===== 玩家端 =====
        case 'my_list':        snMyList(); break;
        case 'query':          snQuery(); break;
        case 'report_lost':    snReportLost(); break;
        case 'reissue':        snReissue(); break;
        case 'cancel':         snCancel(); break;

        // ===== 管理端 =====
        case 'admin_list':        snAdminList(); break;
        case 'admin_detail':      snAdminDetail(); break;
        case 'admin_cmd':         snAdminCmd(); break;
        case 'admin_stats':       snAdminStats(); break;
        case 'admin_lost_list':   snAdminLostList(); break;
        case 'admin_lost_handle': snAdminLostHandle(); break;

        default:
            error('未知操作: ' . $action);
    }
    try { walCheckpoint(); } catch (\Throwable $ignored) {}
} catch (\Throwable $e) {
    debugLog('[sn.php] ' . $action . ' 异常: ' . $e->getMessage());
    error('服务器错误: ' . $e->getMessage());
}

// ============================================================
// 通用工具
// ============================================================

/** Java 侧通信校验 */
function snRequireSecret() {
    $secret = getParam('secret', '');
    if ($secret === '' || $secret !== SECRET_KEY) {
        error('密钥错误', 403);
    }
}

/** 玩家端 token 校验，返回玩家名 */
function snRequirePlayer() {
    $token = getParam('token', '');
    $info = validateTokenSilent($token);
    if (!$info) error('登录状态失效，请重新登录', 401);
    $player = isset($info['player']) ? $info['player'] : (isset($info['player_name']) ? $info['player_name'] : '');
    if ($player === '') error('无法识别玩家身份', 401);
    return $player;
}

function snRequireAdmin() {
    if (!isAdminLoggedIn()) error('未登录管理后台', 401);
}

function snBodyArray() {
    global $SN_BODY;
    if (!is_array($SN_BODY)) return [];
    // 兼容 ["a","b"] 与 {"items":[...]}
    return $SN_BODY;
}

function snStr($v, $def = '') {
    if ($v === null) return $def;
    if (is_bool($v)) return $v ? '1' : '0';
    return (string)$v;
}

function snInt($v, $def = 0) {
    if ($v === null || $v === '') return $def;
    return (int)$v;
}

function snNow() { return time(); }

/**
 * Java 侧时间戳是毫秒（System.currentTimeMillis），PHP 统一按秒存储与展示。
 * 只在 Java 推送入口归一：数值 >= 1e11 视为毫秒。
 */
function snTs($v) {
    $n = (int)$v;
    if ($n >= 100000000000) $n = (int)floor($n / 1000);
    return $n;
}

/** 物品类型中文名 */
function snTypeName($t) {
    $m = [
        'menu_snowball' => '雪球菜单',
        'land_wand'     => '区域选择工具',
        'echo_shard'    => '回声碎片',
        'pvp_tool'      => 'PVP圈地棒',
    ];
    return isset($m[$t]) ? $m[$t] : $t;
}

/** 状态中文名 */
function snStatusCn($s) {
    $m = [
        'active'    => '有效',
        'destroyed' => '已销毁解绑',
        'cancelled' => '已注销',
        'lost'      => '报失处理中',
        'illegal'   => '非法永久绑定',
        'reissued'  => '已补发作废',
    ];
    return isset($m[$s]) ? $m[$s] : $s;
}

/**
 * web_sn_commands.is_force 列懒迁移（管理员强制操作标记）。
 * 老库没有这列，写入/读取前各补一次；每个请求最多一次 PRAGMA。
 */
function snEnsureCmdCols($db) {
    static $checked = false;
    if ($checked) return;
    $has = false;
    try {
        $r = $db->query("PRAGMA table_info(web_sn_commands)");
        if ($r) {
            while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
                if (isset($row['name']) && $row['name'] === 'is_force') {
                    $has = true;
                    break;
                }
            }
        }
    } catch (\Throwable $e) {}
    if (!$has) {
        try {
            $db->exec("ALTER TABLE web_sn_commands ADD COLUMN is_force INTEGER DEFAULT 0");
        } catch (\Throwable $e) {}
    }
    $checked = true;
}

/**
 * 下发一条命令给 Java。
 * $isForce = 1 → 管理员强制操作，Java 侧仅本次绕过冷静期（冷静期记录保留）。
 */
function snEnqueueCmd($db, $cmd, $sn, $player, $itemType, $reason,
                      $linkType = '', $linkId = 0, $isForce = 0) {
    snEnsureCmdCols($db);
    $stmt = $db->prepare(
        "INSERT INTO web_sn_commands
         (cmd, sn, player, item_type, reason, link_type, link_id, is_force, status, created_at)
         VALUES (:cmd, :sn, :player, :type, :reason, :lt, :li, :f, 'pending', :ts)");
    $stmt->bindValue(':cmd', $cmd, SQLITE3_TEXT);
    $stmt->bindValue(':sn', $sn, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':type', $itemType, SQLITE3_TEXT);
    $stmt->bindValue(':reason', $reason, SQLITE3_TEXT);
    $stmt->bindValue(':lt', $linkType, SQLITE3_TEXT);
    $stmt->bindValue(':li', (int)$linkId, SQLITE3_INTEGER);
    $stmt->bindValue(':f', (int)$isForce, SQLITE3_INTEGER);
    $stmt->bindValue(':ts', snNow(), SQLITE3_INTEGER);
    $stmt->execute();
    return (int)$db->lastInsertRowID();
}

/**
 * Java 侧命令执行结论是否算成功（据以决定要不要改 web 状态）。
 * 失败样例：SN不存在 / 注销失败 / 玩家不在线，待其上线后再补发 /
 *          补发申领被拒（见玩家提示） / 补发登记成功但发放失败 / 执行异常: ...
 */
function snCmdOk($result) {
    $r = trim((string)$result);
    if ($r === '') return false;
    $bad0 = array('SN不存在', '注销失败', '玩家不在线', '补发申领被拒',
                  '补发登记成功', '执行异常', '未知命令');
    foreach ($bad0 as $b) {
        if (mb_strpos($r, $b) === 0) return false;
    }
    if (mb_strpos($r, '失败') !== false) return false;
    if (mb_strpos($r, '拒绝') === 0) return false;
    return true;
}

// ============================================================
// Java -> PHP
// ============================================================

/** 手持/背包 SN 快照（任务6：每 10~30 秒上报） */
function snPushHeld() {
    snRequireSecret();
    $db = getDB();
    $rows = snBodyArray();
    $n = 0;
    $now = snNow();
    foreach ($rows as $r) {
        if (!is_array($r)) continue;
        $sn = snStr(isset($r['sn']) ? $r['sn'] : '');
        if ($sn === '') continue;
        $holder = snStr(isset($r['holder']) ? $r['holder'] : '');
        $stmt = $db->prepare(
            "INSERT INTO web_sn_held (sn, player, holder, owner, item_type, held, time)
             VALUES (:sn, :player, :holder, :owner, :type, :held, :time)
             ON CONFLICT(sn) DO UPDATE SET
               player=excluded.player, holder=excluded.holder,
               owner=excluded.owner, item_type=excluded.item_type,
               held=excluded.held, time=excluded.time");
        $stmt->bindValue(':sn', $sn, SQLITE3_TEXT);
        $stmt->bindValue(':player', snStr(isset($r['player']) ? $r['player'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':holder', $holder, SQLITE3_TEXT);
        $stmt->bindValue(':owner', snStr(isset($r['owner']) ? $r['owner'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':type', snStr(isset($r['item_type']) ? $r['item_type'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':held', snInt(isset($r['held']) ? $r['held'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':time', snTs(isset($r['time']) ? $r['time'] : 0) ?: $now, SQLITE3_INTEGER);
        $stmt->execute();

        // 手持 → 立刻刷新位置镜像，报失核查更准
        $held = snInt(isset($r['held']) ? $r['held'] : 0);
        $type = snStr(isset($r['item_type']) ? $r['item_type'] : '');
        if ($held) {
            $u = $db->prepare(
                "UPDATE web_item_sn SET loc_type='player', loc_player=:p,
                   last_seen=:t, updated_at=:t2 WHERE sn=:sn");
            $u->bindValue(':p', $holder, SQLITE3_TEXT);
            $u->bindValue(':t', $now, SQLITE3_INTEGER);
            $u->bindValue(':t2', $now, SQLITE3_INTEGER);
            $u->bindValue(':sn', $sn, SQLITE3_TEXT);
            $u->execute();
        }
        $n++;
    }
    jsonResponse(['ok' => true, 'n' => $n]);
}

/** 事件流（拾取归属不符 / 销毁 / 非法处理） */
function snPushEvents() {
    snRequireSecret();
    $db = getDB();
    $rows = snBodyArray();
    $n = 0;
    $now = snNow();
    foreach ($rows as $r) {
        if (!is_array($r)) continue;
        $act = snStr(isset($r['action']) ? $r['action'] : '');
        $sn  = snStr(isset($r['sn']) ? $r['sn'] : '');
        if ($act === '' || $sn === '') continue;
        $stmt = $db->prepare(
            "INSERT INTO web_sn_event (action, sn, player, detail, created_at)
             VALUES (:a, :sn, :p, :d, :t)");
        $stmt->bindValue(':a', $act, SQLITE3_TEXT);
        $stmt->bindValue(':sn', $sn, SQLITE3_TEXT);
        $stmt->bindValue(':p', snStr(isset($r['player']) ? $r['player'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':d', snStr(isset($r['detail']) ? $r['detail'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':t', snTs(isset($r['created_at']) ? $r['created_at'] : 0) ?: $now, SQLITE3_INTEGER);
        $stmt->execute();

        // 状态即时反映（目录快照到达前先给前端一个正确状态）
        $newStatus = null;
        if ($act === 'destroyed') $newStatus = 'destroyed';
        elseif (in_array($act, ['pickup_mismatch', 'illegal_craft', 'illegal_burn'], true)) $newStatus = 'illegal';
        if ($newStatus !== null) {
            $u = $db->prepare("UPDATE web_item_sn SET status=:s, updated_at=:t WHERE sn=:sn");
            $u->bindValue(':s', $newStatus, SQLITE3_TEXT);
            $u->bindValue(':t', $now, SQLITE3_INTEGER);
            $u->bindValue(':sn', $sn, SQLITE3_TEXT);
            $u->execute();
        }
        $n++;
    }
    jsonResponse(['ok' => true, 'n' => $n]);
}

/** 目录快照：物品全量 + 日志 + 出入库尾部 */
function snPushCatalog() {
    snRequireSecret();
    $db = getDB();
    $body = snBodyArray();
    $now = snNow();
    $n = 0;

    $items = isset($body['items']) && is_array($body['items']) ? $body['items'] : [];
    foreach ($items as $it) {
        if (!is_array($it)) continue;
        $sn = snStr(isset($it['sn']) ? $it['sn'] : '');
        if ($sn === '') continue;
        $stmt = $db->prepare(
            "INSERT INTO web_item_sn
             (sn, item_type, owner, status, issue_time, loc_type, loc_player,
              loc_world, loc_x, loc_y, loc_z, container_type, in_land, land_name,
              last_seen, cancel_time, lost_count, lost_state, remark,
              bind_reason, bind_time, detached_at, own_chest, updated_at)
             VALUES (:sn,:type,:owner,:status,:issue,:lt,:lp,:lw,:lx,:ly,:lz,
                     :ct,:il,:ln,:ls,:ctm,:lc,:lss,:remark,:br,:bt,:da,:oc,:ua)
             ON CONFLICT(sn) DO UPDATE SET
               item_type=excluded.item_type, owner=excluded.owner,
               status=excluded.status, issue_time=excluded.issue_time,
               loc_type=excluded.loc_type, loc_player=excluded.loc_player,
               loc_world=excluded.loc_world, loc_x=excluded.loc_x,
               loc_y=excluded.loc_y, loc_z=excluded.loc_z,
               container_type=excluded.container_type, in_land=excluded.in_land,
               land_name=excluded.land_name, last_seen=excluded.last_seen,
               cancel_time=CASE WHEN excluded.cancel_time > 0
                                THEN excluded.cancel_time ELSE cancel_time END,
               lost_count=excluded.lost_count,
               lost_state=excluded.lost_state, remark=excluded.remark,
               bind_reason=excluded.bind_reason, bind_time=excluded.bind_time,
               detached_at=excluded.detached_at, own_chest=excluded.own_chest,
               updated_at=excluded.updated_at");
        $stmt->bindValue(':sn', $sn, SQLITE3_TEXT);
        $stmt->bindValue(':type', snStr(isset($it['item_type']) ? $it['item_type'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':owner', snStr(isset($it['owner']) ? $it['owner'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':status', snStr(isset($it['status']) ? $it['status'] : 'active', 'active'), SQLITE3_TEXT);
        $stmt->bindValue(':issue', snTs(isset($it['issue_time']) ? $it['issue_time'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':lt', snStr(isset($it['loc_type']) ? $it['loc_type'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':lp', snStr(isset($it['loc_player']) ? $it['loc_player'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':lw', snStr(isset($it['loc_world']) ? $it['loc_world'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':lx', snInt(isset($it['loc_x']) ? $it['loc_x'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ly', snInt(isset($it['loc_y']) ? $it['loc_y'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':lz', snInt(isset($it['loc_z']) ? $it['loc_z'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ct', snStr(isset($it['container_type']) ? $it['container_type'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':il', snInt(isset($it['in_land']) ? $it['in_land'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ln', snStr(isset($it['land_name']) ? $it['land_name'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':ls', snTs(isset($it['last_seen']) ? $it['last_seen'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ctm', snTs(isset($it['cancel_time']) ? $it['cancel_time'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':lc', snInt(isset($it['lost_count']) ? $it['lost_count'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':lss', snStr(isset($it['lost_state']) ? $it['lost_state'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':remark', snStr(isset($it['remark']) ? $it['remark'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':br', snStr(isset($it['bind_reason']) ? $it['bind_reason'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':bt', snTs(isset($it['bind_time']) ? $it['bind_time'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':da', snTs(isset($it['detached_at']) ? $it['detached_at'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':oc', snInt(isset($it['own_chest']) ? $it['own_chest'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ua', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $n++;
    }

    $logs = isset($body['logs']) && is_array($body['logs']) ? $body['logs'] : [];
    foreach ($logs as $l) {
        if (!is_array($l)) continue;
        $id = snInt(isset($l['id']) ? $l['id'] : 0);
        if ($id <= 0) continue;
        $stmt = $db->prepare(
            "INSERT INTO web_sn_log (id, sn, item_type, action, player, detail, time)
             VALUES (:id,:sn,:type,:act,:p,:d,:t)
             ON CONFLICT(id) DO UPDATE SET
               sn=excluded.sn, item_type=excluded.item_type, action=excluded.action,
               player=excluded.player, detail=excluded.detail, time=excluded.time");
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->bindValue(':sn', snStr(isset($l['sn']) ? $l['sn'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':type', snStr(isset($l['item_type']) ? $l['item_type'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':act', snStr(isset($l['action']) ? $l['action'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':p', snStr(isset($l['player']) ? $l['player'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':d', snStr(isset($l['detail']) ? $l['detail'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':t', snTs(isset($l['time']) ? $l['time'] : 0), SQLITE3_INTEGER);
        $stmt->execute();
    }

    $stock = isset($body['stock']) && is_array($body['stock']) ? $body['stock'] : [];
    foreach ($stock as $s) {
        if (!is_array($s)) continue;
        $id = snInt(isset($s['id']) ? $s['id'] : 0);
        if ($id <= 0) continue;
        $stmt = $db->prepare(
            "INSERT INTO web_sn_stock (id, sn, item_type, action, world, x, y, z,
                                       container_type, in_land, land_name, player, time)
             VALUES (:id,:sn,:type,:act,:w,:x,:y,:z,:ct,:il,:ln,:p,:t)
             ON CONFLICT(id) DO UPDATE SET
               sn=excluded.sn, item_type=excluded.item_type, action=excluded.action,
               world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
               container_type=excluded.container_type, in_land=excluded.in_land,
               land_name=excluded.land_name, player=excluded.player, time=excluded.time");
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->bindValue(':sn', snStr(isset($s['sn']) ? $s['sn'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':type', snStr(isset($s['item_type']) ? $s['item_type'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':act', snStr(isset($s['action']) ? $s['action'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':w', snStr(isset($s['world']) ? $s['world'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':x', snInt(isset($s['x']) ? $s['x'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':y', snInt(isset($s['y']) ? $s['y'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':z', snInt(isset($s['z']) ? $s['z'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ct', snStr(isset($s['container_type']) ? $s['container_type'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':il', snInt(isset($s['in_land']) ? $s['in_land'] : 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ln', snStr(isset($s['land_name']) ? $s['land_name'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':p', snStr(isset($s['player']) ? $s['player'] : ''), SQLITE3_TEXT);
        $stmt->bindValue(':t', snTs(isset($s['time']) ? $s['time'] : 0), SQLITE3_INTEGER);
        $stmt->execute();
    }

    jsonResponse(['ok' => true, 'items' => $n,
                  'logs' => count($logs), 'stock' => count($stock)]);
}

/** 拉取待执行命令（返回裸 JSON 数组，Java 侧直接 parseJsonArray） */
function snPullCommands() {
    snRequireSecret();
    $db = getDB();
    $now = snNow();

    // 超时未回执的命令标记为超时（不自动重放，避免重复补发）
    try {
        $db->exec("UPDATE web_sn_commands SET status='timeout'
                   WHERE status='sent' AND sent_at < " . ($now - 180));
    } catch (\Throwable $e) {}

    snEnsureCmdCols($db);
    $out = [];
    $res = $db->query("SELECT id, cmd, sn, player, item_type, reason, is_force
                       FROM web_sn_commands WHERE status='pending'
                       ORDER BY id ASC LIMIT 20");
    if ($res) {
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $out[] = [
                'id'        => (int)$row['id'],
                'cmd'       => (string)$row['cmd'],
                'sn'        => (string)$row['sn'],
                'player'    => (string)$row['player'],
                'item_type' => (string)$row['item_type'],
                'reason'    => (string)$row['reason'],
                'force'     => (int)$row['is_force'],
            ];
            $u = $db->prepare("UPDATE web_sn_commands SET status='sent', sent_at=:t WHERE id=:id");
            $u->bindValue(':t', $now, SQLITE3_INTEGER);
            $u->bindValue(':id', (int)$row['id'], SQLITE3_INTEGER);
            $u->execute();
        }
    }

    while (ob_get_level() > 0) { @ob_end_clean(); }
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($out, JSON_UNESCAPED_UNICODE);
    exit;
}

/** 回传命令执行结果 */
function snAckCommands() {
    snRequireSecret();
    $db = getDB();
    $rows = snBodyArray();
    $now = snNow();
    $done = 0;

    foreach ($rows as $r) {
        if (!is_array($r)) continue;
        $id = snInt(isset($r['id']) ? $r['id'] : 0);
        $result = snStr(isset($r['result']) ? $r['result'] : '');
        if ($id <= 0) continue;

        $cmd = '';
        $sn = '';
        $player = '';
        $itemType = '';
        $linkType = '';
        $linkId = 0;
        $alreadyDone = false;
        $res = $db->prepare("SELECT cmd, sn, player, item_type, link_type, link_id, status
                              FROM web_sn_commands WHERE id = :id");
        $res->bindValue(':id', $id, SQLITE3_INTEGER);
        $rr = $res->execute();
        if ($row = $rr->fetchArray(SQLITE3_ASSOC)) {
            $cmd = (string)$row['cmd'];
            $sn = (string)$row['sn'];
            $player = (string)$row['player'];
            $itemType = (string)$row['item_type'];
            $linkType = (string)$row['link_type'];
            $linkId = (int)$row['link_id'];
            $alreadyDone = ((string)$row['status'] === 'done');
        }

        // ★ 幂等：Java 侧只有确认收到才从本地回执缓冲移除，网络抖动会原样重发。
        //   已处理过的直接跳过，否则报失核查通过会再入队一次补发、注销会重置冷静期。
        if ($alreadyDone) continue;

        $u = $db->prepare("UPDATE web_sn_commands SET status='done', result=:r, done_at=:t
                           WHERE id=:id");
        $u->bindValue(':r', $result, SQLITE3_TEXT);
        $u->bindValue(':t', $now, SQLITE3_INTEGER);
        $u->bindValue(':id', $id, SQLITE3_INTEGER);
        $u->execute();
        $done++;

        // ★ 业务处理与"标 done"分开包 try：任何一条回执的业务逻辑抛异常，
        //   只废这一条，不能连坐把整批回执打断（早先一条 SQL 报错就让
        //   后面所有命令永远停在 sent，玩家端十分钟等不到回调）。
        try {
            // 注销成功 → 记录冷静期（展示用）
            // ★ Java 侧失败就不改状态，避免"后台显示已注销、游戏里实物还能用"
            if ($cmd === 'cancel' && $player !== '' && snCmdOk($result)) {
                $c = $db->prepare("INSERT INTO web_sn_cooldown (player, until) VALUES (:p, :u)
                                   ON CONFLICT(player) DO UPDATE SET until=excluded.until");
                $c->bindValue(':p', $player, SQLITE3_TEXT);
                $c->bindValue(':u', $now + 3600, SQLITE3_INTEGER);
                $c->execute();
                $s = $db->prepare("UPDATE web_item_sn SET status='cancelled', cancel_time=:t,
                                   updated_at=:t2 WHERE sn=:sn");
                $s->bindValue(':t', $now, SQLITE3_INTEGER);
                $s->bindValue(':t2', $now, SQLITE3_INTEGER);
                $s->bindValue(':sn', $sn, SQLITE3_TEXT);
                $s->execute();
            }

            // 补发成功 → 记录旧 SN 已作废（失败则保持原状态）
            if ($cmd === 'reissue' && snCmdOk($result)) {
                $s = $db->prepare("UPDATE web_item_sn SET status='reissued', updated_at=:t WHERE sn=:sn");
                $s->bindValue(':t', $now, SQLITE3_INTEGER);
                $s->bindValue(':sn', $sn, SQLITE3_TEXT);
                $s->execute();
            }

            // 报失核查结果 → 决定拒绝还是自动补发
            if ($cmd === 'report_check' && $linkType === 'lost' && $linkId > 0) {
                snHandleLostCheck($db, $linkId, $result, $now);
            }
            if ($cmd === 'reissue' && $linkType === 'lost' && $linkId > 0) {
                // 成功 → 结单；失败 → 转人工，别假装已补发
                $lostStatus = snCmdOk($result) ? 'done' : 'manual';
                $h = $db->prepare("UPDATE web_sn_lost SET status=:s, result=:r, handled_at=:t
                                   WHERE id=:id");
                $h->bindValue(':s', $lostStatus, SQLITE3_TEXT);
                $h->bindValue(':r', $result, SQLITE3_TEXT);
                $h->bindValue(':t', $now, SQLITE3_INTEGER);
                $h->bindValue(':id', $linkId, SQLITE3_INTEGER);
                $h->execute();
            }
        } catch (\Throwable $e) {
            // 这一条的业务没落地 → 把关联报失单转人工，别让它永远挂着"检查中"
            if ($linkType === 'lost' && $linkId > 0) {
                try {
                    $h = $db->prepare("UPDATE web_sn_lost SET status='manual', result=:r,
                                       handled_at=:t WHERE id=:id AND status='checking'");
                    $h->bindValue(':r', '回执处理异常，已转人工：' . $e->getMessage(), SQLITE3_TEXT);
                    $h->bindValue(':t', $now, SQLITE3_INTEGER);
                    $h->bindValue(':id', $linkId, SQLITE3_INTEGER);
                    $h->execute();
                } catch (\Throwable $e2) {}
            }
        }
    }

    // ★ 自愈：把"命令早已回执、报失单却还停在 checking"的历史卡单补齐。
    //   老版本一次 SQL 报错留下的存量数据靠它追平，玩家端刷新即可看到结论。
    snHealStuckLost($db, $now);

    jsonResponse(['ok' => true, 'n' => $done]);
}

/**
 * 自愈停摆的报失单：
 *  - report_check 已回执 → 按结论推进（拒绝 / 自动补发）；
 *  - report_check 超时或命令缺失 → 转人工，不能让玩家永远等"检查中"。
 * 幂等：只处理 status='checking' 的行，snHandleLostCheck 内部不会重复入队。
 */
function snHealStuckLost($db, $now) {
    try {
        $sql = "SELECT l.id AS lid, l.sn AS lsn, c.status AS cstatus, c.result AS cresult
                FROM web_sn_lost l
                LEFT JOIN web_sn_commands c
                  ON c.link_type='lost' AND c.link_id=l.id AND c.cmd='report_check'
                WHERE l.status='checking'
                ORDER BY l.id DESC LIMIT 50";
        $res = $db->query($sql);
        if (!$res) return;
        $rows = [];
        while ($x = $res->fetchArray(SQLITE3_ASSOC)) $rows[] = $x;
        foreach ($rows as $x) {
            $lid = (int)$x['lid'];
            $st = (string)$x['cstatus'];
            if ($st === 'done') {
                snHandleLostCheck($db, $lid, (string)$x['cresult'], $now);
                continue;
            }
            if ($st === 'timeout' || $st === '') {
                $h = $db->prepare("UPDATE web_sn_lost SET status='manual', result=:r,
                                   handled_at=:t WHERE id=:id AND status='checking'");
                $h->bindValue(':r', $st === 'timeout'
                    ? '游戏服超时未回执，已转人工核查'
                    : '未找到对应核查命令，已转人工核查', SQLITE3_TEXT);
                $h->bindValue(':t', $now, SQLITE3_INTEGER);
                $h->bindValue(':id', $lid, SQLITE3_INTEGER);
                $h->execute();
            }
            // pending / sent → 仍在等待游戏服，保持 checking，下轮再看
        }
    } catch (\Throwable $e) {}
}

/** 报失核查回执：拒绝 / 放行自动补发 */
function snHandleLostCheck($db, $lostId, $result, $now) {
    // ★ web_sn_lost 表没有 item_type 列（早先误写进 SELECT），SQLite 直接抛
    //   "no such column" → 整批回执在写完 status='done' 之后中断 → 报失单
    //   永久停在 checking，玩家端十分钟都等不到回调。列名必须与建表语句一致。
    $res = $db->prepare("SELECT sn, player, status FROM web_sn_lost WHERE id=:id");
    $res->bindValue(':id', $lostId, SQLITE3_INTEGER);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) return;
    // ★ 幂等闸门：主路径与 snHealStuckLost 自愈都可能调到这里。
    //   只有仍是 checking 才允许推进，否则会重复入队一次补发（刷物品）。
    if ((string)$row['status'] !== 'checking') return;

    $sn = (string)$row['sn'];
    $player = (string)$row['player'];

    $itemType = '';
    $s2 = $db->prepare("SELECT item_type FROM web_item_sn WHERE sn=:sn");
    $s2->bindValue(':sn', $sn, SQLITE3_TEXT);
    $r2 = $s2->execute();
    if ($row2 = $r2->fetchArray(SQLITE3_ASSOC)) $itemType = (string)$row2['item_type'];

    if (mb_strpos($result, '拒绝') === 0) {
        // UPDATE 带 status='checking' 条件，抢不到说明已被别处结单 → 直接返回
        $h = $db->prepare("UPDATE web_sn_lost SET status='rejected', result=:r, handled_at=:t
                           WHERE id=:id AND status='checking'");
        $h->bindValue(':r', $result, SQLITE3_TEXT);
        $h->bindValue(':t', $now, SQLITE3_INTEGER);
        $h->bindValue(':id', $lostId, SQLITE3_INTEGER);
        $h->execute();
        if ($db->changes() <= 0) return;
        $u = $db->prepare("UPDATE web_item_sn SET lost_state='rejected', updated_at=:t WHERE sn=:sn");
        $u->bindValue(':t', $now, SQLITE3_INTEGER);
        $u->bindValue(':sn', $sn, SQLITE3_TEXT);
        $u->execute();
        return;
    }

    // 先抢状态再入队：抢到手才补发，防止并发/自愈路径下重复入队
    $h = $db->prepare("UPDATE web_sn_lost SET status='auto_reissue', result=:r, handled_at=:t
                       WHERE id=:id AND status='checking'");
    $h->bindValue(':r', $result, SQLITE3_TEXT);
    $h->bindValue(':t', $now, SQLITE3_INTEGER);
    $h->bindValue(':id', $lostId, SQLITE3_INTEGER);
    $h->execute();
    if ($db->changes() <= 0) return;

    $u = $db->prepare("UPDATE web_item_sn SET lost_state='reissuing', updated_at=:t WHERE sn=:sn");
    $u->bindValue(':t', $now, SQLITE3_INTEGER);
    $u->bindValue(':sn', $sn, SQLITE3_TEXT);
    $u->execute();

    snEnqueueCmd($db, 'reissue', $sn, $player, $itemType,
                 '报失核查通过，自动补发', 'lost', $lostId);
}

// ============================================================
// 玩家端
// ============================================================

/** 我的 SN 列表 */
function snMyList() {
    $player = snRequirePlayer();
    $db = getDB();
    $out = [];
    $res = $db->prepare("SELECT * FROM web_item_sn WHERE owner = :p
                          ORDER BY issue_time DESC");
    $res->bindValue(':p', $player, SQLITE3_TEXT);
    $rr = $res->execute();
    while ($row = $rr->fetchArray(SQLITE3_ASSOC)) {
        $out[] = snDecorate($db, $row, $player);
    }

    $cd = snCooldownLeft($db, $player);
    success(['list' => $out, 'cooldown_until' => $cd,
             'cooldown_left' => max(0, $cd - snNow())]);
}

function snCooldownLeft($db, $player) {
    try {
        $res = $db->prepare("SELECT until FROM web_sn_cooldown WHERE player=:p");
        $res->bindValue(':p', $player, SQLITE3_TEXT);
        $rr = $res->execute();
        if ($row = $rr->fetchArray(SQLITE3_ASSOC)) return (int)$row['until'];
    } catch (\Throwable $e) {}
    return 0;
}

/** 给一行 SN 补充展示字段 */
function snDecorate($db, $row, $viewer) {
    $row['type_cn'] = snTypeName($row['item_type']);
    $row['status_cn'] = snStatusCn($row['status']);
    $row['issue_time_str'] = $row['issue_time'] ? date('Y-m-d H:i:s', (int)$row['issue_time']) : '';
    $row['last_seen_str'] = $row['last_seen'] ? date('Y-m-d H:i:s', (int)$row['last_seen']) : '';
    $row['is_owner'] = ($viewer !== null
        && strcasecmp($row['owner'], $viewer) === 0);
    $row['loc_desc'] = snLocDesc($row);
    // 三条件的原始数据位（脱离管控起始时刻 / 是否在本人领地箱子）只对管理端
    // 开放——玩家端若拿到 detached_at 就能反推出 12 小时这条硬阈值。
    unset($row['detached_at'], $row['own_chest']);
    return $row;
}

function snLocDesc($row) {
    $lt = isset($row['loc_type']) ? $row['loc_type'] : '';
    $lp = isset($row['loc_player']) ? $row['loc_player'] : '';
    if ($lt === 'container') {
        $d = '容器内(' . (isset($row['container_type']) ? $row['container_type'] : '') . ') @ '
             . (isset($row['loc_world']) ? $row['loc_world'] : '') . ' '
             . (isset($row['loc_x']) ? $row['loc_x'] : 0) . ','
             . (isset($row['loc_y']) ? $row['loc_y'] : 0) . ','
             . (isset($row['loc_z']) ? $row['loc_z'] : 0);
        if (!empty($row['in_land'])) $d .= '（领地 ' . (isset($row['land_name']) ? $row['land_name'] : '') . '）';
        return $d;
    }
    if ($lt === 'player') {
        return $lp !== '' ? '玩家 ' . $lp . ' 身上' : '玩家身上';
    }
    if ($lt === 'dropped') return '已丢弃在地面';
    return $lt !== '' ? $lt : '未知';
}

/** 单个 SN 查询（玩家端） */
function snQuery() {
    $player = snRequirePlayer();
    $sn = trim(getParam('sn', ''));
    if ($sn === '') error('请输入SN码');
    $db = getDB();
    snHealStuckLost($db, snNow());

    $res = $db->prepare("SELECT * FROM web_item_sn WHERE sn = :sn");
    $res->bindValue(':sn', $sn, SQLITE3_TEXT);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('SN不存在，请核对后重试');

    $isOwner = (strcasecmp($row['owner'], $player) === 0);

    $detail = snDecorate($db, $row, $player);
    // 非本人只返回脱敏信息
    if (!$isOwner) {
        $detail = [
            'sn' => $row['sn'],
            'type_cn' => snTypeName($row['item_type']),
            'status' => $row['status'],
            'status_cn' => snStatusCn($row['status']),
            'is_owner' => false,
            'owner_mask' => snMaskName($row['owner']),
            'loc_desc' => '非本人物品，位置不展示',
        ];
    } else {
        $logs = [];
        $lr = $db->prepare("SELECT * FROM web_sn_log WHERE sn=:sn ORDER BY time DESC LIMIT 50");
        $lr->bindValue(':sn', $sn, SQLITE3_TEXT);
        $lrr = $lr->execute();
        while ($x = $lrr->fetchArray(SQLITE3_ASSOC)) $logs[] = $x;

        $stock = [];
        $sr = $db->prepare("SELECT * FROM web_sn_stock WHERE sn=:sn ORDER BY time DESC LIMIT 50");
        $sr->bindValue(':sn', $sn, SQLITE3_TEXT);
        $srr = $sr->execute();
        while ($x = $srr->fetchArray(SQLITE3_ASSOC)) $stock[] = $x;

        $losts = [];
        $hr = $db->prepare("SELECT * FROM web_sn_lost WHERE sn=:sn ORDER BY id DESC LIMIT 20");
        $hr->bindValue(':sn', $sn, SQLITE3_TEXT);
        $hrr = $hr->execute();
        while ($x = $hrr->fetchArray(SQLITE3_ASSOC)) $losts[] = $x;

        $detail['logs'] = $logs;
        $detail['stock'] = $stock;
        $detail['losts'] = $losts;
        $detail['cooldown_left'] = max(0, snCooldownLeft($db, $player) - snNow());
    }

    success($detail);
}

function snMaskName($name) {
    $n = (string)$name;
    if (mb_strlen($n) <= 2) return mb_substr($n, 0, 1) . '*';
    return mb_substr($n, 0, 1) . str_repeat('*', min(6, mb_strlen($n) - 2)) . mb_substr($n, -1);
}

/**
 * 注销 / 报失 / 补办统一门槛的三条件判定（与 Java snCustodyDetail 同口径）：
 *   ① 物品不在本人身上；
 *   ② 物品不在本人名下领地内的箱子里（只认领地归属）；
 *   ③ 物品脱离自身管控已超过 12 小时（回到管辖内清零，再次脱离重新起算）。
 * 返回逐条判定结果——仅供【管理端】展示，对玩家一律只回通用回执。
 */
function snCustodyEval($db, $row, $player) {
    $lt = isset($row['loc_type']) ? (string)$row['loc_type'] : '';
    $lp = isset($row['loc_player']) ? (string)$row['loc_player'] : '';

    // ① 在本人身上：登记位置 + 手持快照（每 10~30 秒上报）双重佐证
    $inBody = ($lt === 'player' && $lp !== ''
               && strcasecmp($lp, $player) === 0);
    if (!$inBody) {
        try {
            $h = $db->prepare("SELECT holder FROM web_sn_held WHERE sn=:sn");
            $h->bindValue(':sn', $row['sn'], SQLITE3_TEXT);
            $hr = $h->execute();
            if ($x = $hr->fetchArray(SQLITE3_ASSOC)) {
                $holder = (string)$x['holder'];
                if ($holder !== '' && strcasecmp($holder, $player) === 0) {
                    $inBody = true;
                }
            }
        } catch (\Throwable $e) {}
    }

    // ② 在本人领地的箱子里：own_chest 由 Java 写入；为防同步延迟，坐标现算兜底
    //   （web_area_lands 与游戏服 area_lands 双向同步，判定规则与 Java getArea 一致）
    $inOwnChest = ($lt === 'container') && !empty($row['own_chest']);
    if ($lt === 'container' && !$inOwnChest) {
        try {
            $w = (string)(isset($row['loc_world']) ? $row['loc_world'] : '');
            $x = (int)(isset($row['loc_x']) ? $row['loc_x'] : 0);
            $y = (int)(isset($row['loc_y']) ? $row['loc_y'] : 0);
            $z = (int)(isset($row['loc_z']) ? $row['loc_z'] : 0);
            // SELECT * ：core.php 建表用 y1/y2、sync.php 用 y_min/y_max，
            // 两边历史不一致，不能写死列名
            $lq = $db->prepare("SELECT * FROM web_area_lands
                                WHERE world = :w AND owner = :o");
            $lq->bindValue(':w', $w, SQLITE3_TEXT);
            $lq->bindValue(':o', $player, SQLITE3_TEXT);
            $lqr = $lq->execute();
            while ($l = $lqr->fetchArray(SQLITE3_ASSOC)) {
                $minX = min((int)$l['x1'], (int)$l['x2']);
                $maxX = max((int)$l['x1'], (int)$l['x2']);
                $minZ = min((int)$l['z1'], (int)$l['z2']);
                $maxZ = max((int)$l['z1'], (int)$l['z2']);
                if ($x < $minX || $x > $maxX || $z < $minZ || $z > $maxZ) continue;
                // Y 区间（两套列名都试；区间无效则不按 Y 过滤）
                if (array_key_exists('y1', $l)) {
                    $yLo = (int)$l['y1']; $yHi = (int)$l['y2'];
                } elseif (array_key_exists('y_min', $l)) {
                    $yLo = (int)$l['y_min']; $yHi = (int)$l['y_max'];
                } else {
                    $yLo = null; $yHi = null;
                }
                if ($yLo !== null && $yHi > $yLo
                        && ($y < $yLo || $y > $yHi)) continue;
                $inOwnChest = true;
                break;
            }
        } catch (\Throwable $e) {}
    }

    // ③ 脱离自身管控时长（秒；detached_at=0 表示此刻仍在自身管控内）
    $det = isset($row['detached_at']) ? (int)$row['detached_at'] : 0;
    if ($det <= 0) $det = isset($row['last_seen']) ? (int)$row['last_seen'] : 0;
    $since = ($inBody || $inOwnChest) ? 0 : $det;
    $held = $since > 0 ? max(0, snNow() - $since) : 0;
    $detachedOk = (!$inBody && !$inOwnChest) && $since > 0 && $held > 43200;
    $pass = (!$inBody && !$inOwnChest && $detachedOk);

    $heldText = ($inBody || $inOwnChest)
        ? '0（仍在自身管控内）'
        : (floor($held / 3600) > 0
            ? floor($held / 3600) . ' 小时 ' . floor(($held % 3600) / 60) . ' 分'
            : floor($held / 60) . ' 分');

    return [
        'pass' => $pass,
        'in_body' => $inBody,
        'in_own_chest' => $inOwnChest,
        'detached_ok' => $detachedOk,
        'detached_since' => $since,
        'detached_hours' => $since > 0 ? round($held / 3600, 2) : 0,
        'detached_text' => $heldText,
        'text' => '在本人身上=' . ($inBody ? '是' : '否')
                . ' / 在本人领地箱子=' . ($inOwnChest ? '是' : '否')
                . ' / 脱离自身管控=' . $heldText . '（阈值 12 小时）',
    ];
}

/**
 * 办理前置门槛（报失 / 补办 / 注销共用）。
 * 达标 → block=false；未达标 → block=true，msg 是给玩家看的【通用】回执，
 * 不透露具体是哪一条没达标（最小化信息透露），逐条结果放 custody 供管理端展示。
 */
function snCustodyGate($db, $sn, $player) {
    $res = $db->prepare("SELECT * FROM web_item_sn WHERE sn=:sn");
    $res->bindValue(':sn', $sn, SQLITE3_TEXT);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        return ['block' => true, 'msg' => 'SN不存在', 'row' => null, 'custody' => null];
    }

    $custody = snCustodyEval($db, $row, $player);
    if ($custody['pass']) {
        return ['block' => false, 'msg' => '', 'row' => $row, 'custody' => $custody];
    }
    return ['block' => true, 'msg' => '未达到办理条件，请稍后再试',
            'row' => $row, 'custody' => $custody];
}

/** 报失（挂失） */
function snReportLost() {
    $player = snRequirePlayer();
    $sn = trim(getParam('sn', ''));
    $reason = trim(getParam('reason', ''));
    if ($sn === '') error('请输入SN码');
    $db = getDB();

    $res = $db->prepare("SELECT * FROM web_item_sn WHERE sn=:sn");
    $res->bindValue(':sn', $sn, SQLITE3_TEXT);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('SN不存在');
    if (strcasecmp($row['owner'], $player) !== 0) error('只能对自己名下的物品报失');
    if (!in_array($row['status'], ['active', 'lost'], true)) {
        error('该SN当前状态（' . snStatusCn($row['status']) . '）不允许报失');
    }

    // ★ 统一门槛：不在本人身上 ∩ 不在本人领地箱子 ∩ 脱离自身管控 > 12 小时。
    //   不达标只回通用回执（不说是哪一条），逐条判定只进管理端展示。
    $chk = snCustodyGate($db, $sn, $player);
    if ($chk['block']) {
        $db->exec("INSERT INTO web_sn_lost (sn, player, reason, status, result,
                    report_count, created_at, handled_at)
                   VALUES ('" . SQLite3::escapeString($sn) . "',
                           '" . SQLite3::escapeString($player) . "',
                           '" . SQLite3::escapeString($reason) . "',
                           'rejected',
                           '" . SQLite3::escapeString($chk['msg']) . "',
                           1, " . snNow() . ", " . snNow() . ")");
        error($chk['msg']);
    }

    // ★ 短时间多次报失 → 转人工核查
    $since = snNow() - 1800;
    $cnt = 0;
    $cr = $db->prepare("SELECT COUNT(*) AS c FROM web_sn_lost
                        WHERE sn=:sn AND created_at > :t AND status IN ('checking','auto_reissue')");
    $cr->bindValue(':sn', $sn, SQLITE3_TEXT);
    $cr->bindValue(':t', $since, SQLITE3_INTEGER);
    $crr = $cr->execute();
    if ($row2 = $crr->fetchArray(SQLITE3_ASSOC)) $cnt = (int)$row2['c'];
    $total = $cnt + 1;

    if ($total >= 3) {
        $stmt = $db->prepare(
            "INSERT INTO web_sn_lost (sn, player, reason, status, result, report_count, created_at)
             VALUES (:sn, :p, :r, 'manual', '短时间内多次报失，已转人工核查', :c, :t)");
        $stmt->bindValue(':sn', $sn, SQLITE3_TEXT);
        $stmt->bindValue(':p', $player, SQLITE3_TEXT);
        $stmt->bindValue(':r', $reason, SQLITE3_TEXT);
        $stmt->bindValue(':c', $total, SQLITE3_INTEGER);
        $stmt->bindValue(':t', snNow(), SQLITE3_INTEGER);
        $stmt->execute();
        success(['status' => 'manual', 'count' => $total],
                '30分钟内已报失 ' . $total . ' 次，已转人工核查，请联系管理员');
    }

    // 正常流程：登记 + 下发位置核查命令
    $stmt = $db->prepare(
        "INSERT INTO web_sn_lost (sn, player, reason, status, result, report_count, created_at)
         VALUES (:sn, :p, :r, 'checking', '', :c, :t)");
    $stmt->bindValue(':sn', $sn, SQLITE3_TEXT);
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $stmt->bindValue(':r', $reason, SQLITE3_TEXT);
    $stmt->bindValue(':c', $total, SQLITE3_INTEGER);
    $stmt->bindValue(':t', snNow(), SQLITE3_INTEGER);
    $stmt->execute();
    $lostId = (int)$db->lastInsertRowID();

    snEnqueueCmd($db, 'report_check', $sn, $player, $row['item_type'],
                 '玩家报失核查', 'lost', $lostId);

    success(['status' => 'checking', 'id' => $lostId, 'count' => $total],
            '报失已提交，系统正在核对物品位置，核查通过后会自动补发新SN');
}

/** 申请补发（本人确认确实丢失） */
function snReissue() {
    $player = snRequirePlayer();
    $sn = trim(getParam('sn', ''));
    if ($sn === '') error('请输入SN码');
    $db = getDB();

    $res = $db->prepare("SELECT * FROM web_item_sn WHERE sn=:sn");
    $res->bindValue(':sn', $sn, SQLITE3_TEXT);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('SN不存在');
    if (strcasecmp($row['owner'], $player) !== 0) error('只能操作自己名下的物品');
    if (!in_array($row['status'], ['active', 'lost'], true)) {
        error('该SN状态为' . snStatusCn($row['status']) . '，不可补发');
    }

    // 补办与报失 / 注销同口径：三条件统一门槛，不达标只给通用回执
    $chk = snCustodyGate($db, $sn, $player);
    if ($chk['block']) error($chk['msg']);

    // 已有待执行的补发命令 → 避免重复
    $pr = $db->prepare("SELECT COUNT(*) AS c FROM web_sn_commands
                        WHERE sn=:sn AND cmd='reissue' AND status IN ('pending','sent')");
    $pr->bindValue(':sn', $sn, SQLITE3_TEXT);
    $prr = $pr->execute();
    if ($r2 = $prr->fetchArray(SQLITE3_ASSOC)) {
        if ((int)$r2['c'] > 0) error('已有补发请求正在处理中，请稍候');
    }

    snEnqueueCmd($db, 'reissue', $sn, $player, $row['item_type'], '玩家申请补发');
    success(null, '补发申请已提交，玩家在线时将立即签发新SN并发放新物品');
}

/** 注销：销毁对应物品 + 1 小时冷静期 */
function snCancel() {
    $player = snRequirePlayer();
    $sn = trim(getParam('sn', ''));
    $reason = trim(getParam('reason', ''));
    if ($sn === '') error('请输入SN码');
    $db = getDB();

    $res = $db->prepare("SELECT * FROM web_item_sn WHERE sn=:sn");
    $res->bindValue(':sn', $sn, SQLITE3_TEXT);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('SN不存在');
    if (strcasecmp($row['owner'], $player) !== 0) error('只能操作自己名下的物品');
    if ($row['status'] === 'cancelled') error('该SN已注销');
    if ($row['status'] === 'destroyed') error('该SN已销毁解绑，无需注销');
    if ($row['status'] === 'reissued') error('该SN已被新SN替代，无需注销');

    // ★ 统一门槛：物品脱离自身管控（不在本人身上、不在本人领地箱子、>12 小时）
    //   才允许注销，否则玩家可拿身上/箱子里的东西反复注销换新。
    //   未达标只回通用回执，逐条判定只在管理端 SN 详情里看。
    $chk = snCustodyGate($db, $sn, $player);
    if ($chk['block']) error($chk['msg']);

    $pr = $db->prepare("SELECT COUNT(*) AS c FROM web_sn_commands
                        WHERE sn=:sn AND cmd='cancel' AND status IN ('pending','sent')");
    $pr->bindValue(':sn', $sn, SQLITE3_TEXT);
    $prr = $pr->execute();
    if ($r2 = $prr->fetchArray(SQLITE3_ASSOC)) {
        if ((int)$r2['c'] > 0) error('注销请求正在处理中');
    }

    $cdLeft = max(0, snCooldownLeft($db, $player) - snNow());
    if ($cdLeft > 0) {
        error('处于注销冷静期，还剩 ' . ceil($cdLeft / 60) . ' 分钟');
    }

    snEnqueueCmd($db, 'cancel', $sn, $player, $row['item_type'],
                 $reason !== '' ? $reason : '玩家注销');
    success(['cooldown_seconds' => 3600],
            '注销申请已提交：将立即销毁对应物品，并进入 1 小时冷静期，期间禁止申领新设备');
}

// ============================================================
// 管理端
// ============================================================

/** SN 列表（分页 + 搜索） */
function snAdminList() {
    snRequireAdmin();
    $db = getDB();
    $kw = trim(getParam('kw', ''));
    $status = trim(getParam('status', ''));
    $type = trim(getParam('type', ''));
    $page = max(1, (int)getParam('page', 1));
    $size = min(100, max(1, (int)getParam('size', 20)));

    $where = [];
    $bind = [];
    if ($kw !== '') {
        $where[] = '(sn LIKE :kw OR owner LIKE :kw OR loc_player LIKE :kw OR land_name LIKE :kw)';
        $bind[':kw'] = '%' . $kw . '%';
    }
    if ($status !== '') { $where[] = 'status = :st'; $bind[':st'] = $status; }
    if ($type !== '') { $where[] = 'item_type = :ty'; $bind[':ty'] = $type; }
    $sql = 'FROM web_item_sn' . (count($where) ? ' WHERE ' . implode(' AND ', $where) : '');

    $cnt = $db->prepare('SELECT COUNT(*) AS c ' . $sql);
    foreach ($bind as $k => $v) $cnt->bindValue($k, $v, SQLITE3_TEXT);
    $cr = $cnt->execute();
    $total = 0;
    if ($r = $cr->fetchArray(SQLITE3_ASSOC)) $total = (int)$r['c'];

    $offset = ($page - 1) * $size;
    $q = $db->prepare('SELECT * ' . $sql . ' ORDER BY issue_time DESC LIMIT ' . (int)$size
                       . ' OFFSET ' . (int)$offset);
    foreach ($bind as $k => $v) $q->bindValue($k, $v, SQLITE3_TEXT);
    $rr = $q->execute();

    $list = [];
    while ($row = $rr->fetchArray(SQLITE3_ASSOC)) {
        $row['type_cn'] = snTypeName($row['item_type']);
        $row['status_cn'] = snStatusCn($row['status']);
        $row['issue_time_str'] = $row['issue_time'] ? date('Y-m-d H:i:s', (int)$row['issue_time']) : '';
        $row['loc_desc'] = snLocDesc($row);
        $list[] = $row;
    }
    success(['list' => $list, 'total' => $total, 'page' => $page, 'size' => $size]);
}

/** SN 详情（含日志 / 出入库 / 报失） */
function snAdminDetail() {
    snRequireAdmin();
    $sn = trim(getParam('sn', ''));
    if ($sn === '') error('缺少sn');
    $db = getDB();

    $res = $db->prepare("SELECT * FROM web_item_sn WHERE sn=:sn");
    $res->bindValue(':sn', $sn, SQLITE3_TEXT);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('SN不存在');
    $row['type_cn'] = snTypeName($row['item_type']);
    $row['status_cn'] = snStatusCn($row['status']);
    $row['issue_time_str'] = $row['issue_time'] ? date('Y-m-d H:i:s', (int)$row['issue_time']) : '';
    $row['loc_desc'] = snLocDesc($row);
    // 管理端专属：注销 / 报失 / 补办三条件逐条判定（玩家端拿不到这些字段）
    $row['custody'] = snCustodyEval($db, $row, $row['owner']);

    $logs = [];
    $lr = $db->prepare("SELECT * FROM web_sn_log WHERE sn=:sn ORDER BY time DESC LIMIT 100");
    $lr->bindValue(':sn', $sn, SQLITE3_TEXT);
    $lrr = $lr->execute();
    while ($x = $lrr->fetchArray(SQLITE3_ASSOC)) $logs[] = $x;

    $stock = [];
    $sr = $db->prepare("SELECT * FROM web_sn_stock WHERE sn=:sn ORDER BY time DESC LIMIT 100");
    $sr->bindValue(':sn', $sn, SQLITE3_TEXT);
    $srr = $sr->execute();
    while ($x = $srr->fetchArray(SQLITE3_ASSOC)) $stock[] = $x;

    $losts = [];
    $hr = $db->prepare("SELECT * FROM web_sn_lost WHERE sn=:sn ORDER BY id DESC LIMIT 20");
    $hr->bindValue(':sn', $sn, SQLITE3_TEXT);
    $hrr = $hr->execute();
    while ($x = $hrr->fetchArray(SQLITE3_ASSOC)) $losts[] = $x;

    $cmds = [];
    $qr = $db->prepare("SELECT * FROM web_sn_commands WHERE sn=:sn ORDER BY id DESC LIMIT 20");
    $qr->bindValue(':sn', $sn, SQLITE3_TEXT);
    $qrr = $qr->execute();
    while ($x = $qrr->fetchArray(SQLITE3_ASSOC)) $cmds[] = $x;

    $held = null;
    $hr2 = $db->prepare("SELECT * FROM web_sn_held WHERE sn=:sn");
    $hr2->bindValue(':sn', $sn, SQLITE3_TEXT);
    $hr2r = $hr2->execute();
    if ($x = $hr2r->fetchArray(SQLITE3_ASSOC)) $held = $x;

    success(['sn' => $row, 'logs' => $logs, 'stock' => $stock,
             'losts' => $losts, 'commands' => $cmds, 'held' => $held]);
}

/** 管理员下发命令：cancel / reissue / locate / bind / report_check */
function snAdminCmd() {
    snRequireAdmin();
    $cmd = trim(getParam('cmd', ''));
    $sn = trim(getParam('sn', ''));
    $reason = trim(getParam('reason', ''));
    $allowed = ['cancel', 'reissue', 'locate', 'bind', 'report_check'];
    if (!in_array($cmd, $allowed, true)) error('非法命令');
    if ($sn === '') error('缺少sn');

    $db = getDB();
    $res = $db->prepare("SELECT * FROM web_item_sn WHERE sn=:sn");
    $res->bindValue(':sn', $sn, SQLITE3_TEXT);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('SN不存在');

    // ★ is_force=1（默认，强制操作）：Java 侧本次绕过冷静期 + 绕过三条件门槛
    //   （强制注销 / 强制补发按定义豁免"脱离自身管控"三项）。
    //   代办操作传 force=0 → 照常按三条件校验，逐条判定见 SN 详情的 custody。
    $force = (int)getParam('force', 1) ? 1 : 0;
    $id = snEnqueueCmd($db, $cmd, $sn, $row['owner'], $row['item_type'],
                       $reason !== '' ? $reason : '管理员操作', '', 0, $force);
    success(['id' => $id], '命令已下发，等待游戏服务器执行（10~30 秒）');
}

/** 管理端统计 */
function snAdminStats() {
    snRequireAdmin();
    $db = getDB();
    $out = ['total' => 0, 'by_status' => [], 'by_type' => [], 'lost_pending' => 0, 'events' => 0];
    $r = $db->query("SELECT status, COUNT(*) AS c FROM web_item_sn GROUP BY status");
    if ($r) { while ($x = $r->fetchArray(SQLITE3_ASSOC)) { $out['by_status'][$x['status']] = (int)$x['c']; $out['total'] += (int)$x['c']; } }
    $r = $db->query("SELECT item_type, COUNT(*) AS c FROM web_item_sn GROUP BY item_type");
    if ($r) { while ($x = $r->fetchArray(SQLITE3_ASSOC)) $out['by_type'][$x['item_type']] = (int)$x['c']; }
    $r = $db->query("SELECT COUNT(*) AS c FROM web_sn_lost WHERE status IN ('checking','manual')");
    if ($r) { if ($x = $r->fetchArray(SQLITE3_ASSOC)) $out['lost_pending'] = (int)$x['c']; }
    $r = $db->query("SELECT COUNT(*) AS c FROM web_sn_event");
    if ($r) { if ($x = $r->fetchArray(SQLITE3_ASSOC)) $out['events'] = (int)$x['c']; }
    success($out);
}

/** 报失工单列表 */
function snAdminLostList() {
    snRequireAdmin();
    $db = getDB();
    $status = trim(getParam('status', ''));
    $page = max(1, (int)getParam('page', 1));
    $size = min(100, max(1, (int)getParam('size', 20)));

    $where = '';
    $bind = [];
    if ($status !== '') { $where = ' WHERE status = :st'; $bind[':st'] = $status; }

    $cnt = $db->prepare('SELECT COUNT(*) AS c FROM web_sn_lost' . $where);
    foreach ($bind as $k => $v) $cnt->bindValue($k, $v, SQLITE3_TEXT);
    $cr = $cnt->execute();
    $total = 0;
    if ($r = $cr->fetchArray(SQLITE3_ASSOC)) $total = (int)$r['c'];

    $q = $db->prepare('SELECT * FROM web_sn_lost' . $where . ' ORDER BY id DESC LIMIT '
                       . (int)$size . ' OFFSET ' . (int)(($page - 1) * $size));
    foreach ($bind as $k => $v) $q->bindValue($k, $v, SQLITE3_TEXT);
    $rr = $q->execute();
    $list = [];
    while ($row = $rr->fetchArray(SQLITE3_ASSOC)) {
        $row['created_str'] = date('Y-m-d H:i:s', (int)$row['created_at']);
        $t = '';
        $tr = $db->prepare("SELECT item_type FROM web_item_sn WHERE sn=:sn");
        $tr->bindValue(':sn', $row['sn'], SQLITE3_TEXT);
        $trr = $tr->execute();
        if ($x = $trr->fetchArray(SQLITE3_ASSOC)) $t = snTypeName($x['item_type']);
        $row['type_cn'] = $t;
        $list[] = $row;
    }
    success(['list' => $list, 'total' => $total, 'page' => $page, 'size' => $size]);
}

/** 人工处理报失：approve=通过补发 / reject=驳回 */
function snAdminLostHandle() {
    snRequireAdmin();
    $id = (int)getParam('id', 0);
    $act = trim(getParam('act', ''));
    $reason = trim(getParam('reason', ''));
    if ($id <= 0) error('缺少id');
    if (!in_array($act, ['approve', 'reject'], true)) error('非法操作');

    $db = getDB();
    $res = $db->prepare("SELECT * FROM web_sn_lost WHERE id=:id");
    $res->bindValue(':id', $id, SQLITE3_INTEGER);
    $rr = $res->execute();
    $row = $rr->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('工单不存在');
    if (!in_array($row['status'], ['checking', 'manual'], true)) {
        error('该工单已处理：' . $row['status']);
    }

    $now = snNow();
    $itemType = '';
    $tr = $db->prepare("SELECT item_type, owner FROM web_item_sn WHERE sn=:sn");
    $tr->bindValue(':sn', $row['sn'], SQLITE3_TEXT);
    $trr = $tr->execute();
    $owner = $row['player'];
    if ($x = $trr->fetchArray(SQLITE3_ASSOC)) { $itemType = $x['item_type']; $owner = $x['owner']; }

    if ($act === 'reject') {
        $msg = $reason !== '' ? $reason : '人工核查未通过';
        $u = $db->prepare("UPDATE web_sn_lost SET status='rejected', result=:r, handled_at=:t WHERE id=:id");
        $u->bindValue(':r', $msg, SQLITE3_TEXT);
        $u->bindValue(':t', $now, SQLITE3_INTEGER);
        $u->bindValue(':id', $id, SQLITE3_INTEGER);
        $u->execute();
        success(null, '已驳回报失');
    } else {
        $u = $db->prepare("UPDATE web_sn_lost SET status='auto_reissue', result=:r, handled_at=:t WHERE id=:id");
        $u->bindValue(':r', $reason !== '' ? $reason : '人工核查通过', SQLITE3_TEXT);
        $u->bindValue(':t', $now, SQLITE3_INTEGER);
        $u->bindValue(':id', $id, SQLITE3_INTEGER);
        $u->execute();
        snEnqueueCmd($db, 'reissue', $row['sn'], $owner, $itemType,
                     '人工核查通过补发', 'lost', $id, 1);
        success(null, '已通过，补发命令已下发');
    }
}
