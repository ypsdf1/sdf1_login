<?php
// ===== 数据安全护栏 / 灾后自愈（2026-10-10）=====
// 用户原则（原话）：「自愈只建立在安全的情况下，其他情况以数据安全作为第一优先级」。
//   · 数据库能连上但被错误清空（TRUNCATE/删表）→ recover：补表结构、把仍为空的表
//     从最近一次可读备份补推 SQL 数据，然后放行与 Java 的握手，等 Java 把其余数据补推回来；
//   · 数据库能连上但「数据不对」（sql.md 指错库/指错机）→ block：停止对该库的一切操作、
//     拒绝与 Java 握手（含对账），提醒登录的管理员并引导修改 sql.md 数据库配置段；
//   · 块模式绝不软回退 SQLite（那是把数据写进另一个库，比停机更危险）。
// 状态文件：data/.db_guard.json（盘上，不依赖数据库——库没了他还在）。
// 节流：每侧至多 240 秒做一次完整体检，避免每次请求都 COUNT 全表。

if (!defined('DF_GUARD_TTL')) define('DF_GUARD_TTL', 240);          // 体检节流（秒）
if (!defined('DF_GUARD_BACKUP_MAX_AGE')) define('DF_GUARD_BACKUP_MAX_AGE', 14 * 86400); // 备份可补推的最大年龄
if (!defined('DF_GUARD_MAX_STMTS')) define('DF_GUARD_MAX_STMTS', 3000); // 单次补推最多执行的语句数

/** 块模式异常：穿透 dbOpen 的 SQLite 软回退 catch */
class dfGuardBlockException extends \Exception
{
}

/** 状态文件路径（data/ 不存在则建） */
function dfGuardStateFile()
{
    $dir = __DIR__ . '/data';
    if (!is_dir($dir)) { @mkdir($dir, 0755, true); }
    return $dir . '/.db_guard.json';
}

function dfGuardReadState()
{
    $f = dfGuardStateFile();
    if (!is_file($f)) return array();
    $j = @json_decode((string)@file_get_contents($f), true);
    return is_array($j) ? $j : array();
}

function dfGuardWriteState($st)
{
    $f = dfGuardStateFile();
    $tmp = $f . '.' . getmypid() . '.tmp';
    @file_put_contents($tmp, json_encode($st, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), LOCK_EX);
    if (!is_file($tmp)) return false;
    return @rename($tmp, $f);
}

/** 每侧的核心表：这些表集体归零 = 库被清空的最可靠信号 */
function dfGuardCoreTables($side)
{
    if ($side === 'java') {
        return array('users', 'game_transactions', 'weblogin_credentials', 'web_player_bans');
    }
    return array('web_item_sn', 'web_sn_stock', 'web_sn_log');
}

/** 当前处于块状态的侧（供 sync.php 拒绝握手 / 登录页横幅读取，不打库） */
function dfGuardBlockedSides()
{
    $st = dfGuardReadState();
    $out = array();
    foreach ($st as $side => $s) {
        if (is_array($s) && isset($s['mode']) && $s['mode'] === 'block' && !empty($s['alert'])) {
            $out[] = $side . '库：' . $s['alert'];
        }
    }
    return $out;
}

/** 某侧的块原因（dbOpen 抛异常用） */
function dfGuardBlockReason($side)
{
    $st = dfGuardReadState();
    if (isset($st[$side]['alert']) && $st[$side]['alert']) return (string)$st[$side]['alert'];
    return '数据库状态异常（身份与上次运行不一致），已停止操作并拒绝握手，请检查 sql.md 数据库配置段。';
}

/**
 * 纯判定函数（可单测）：给定上次体检快照与本次观测，返回 mode。
 * @param array|null $prev   上次快照：db/host/port/tables/rows/core/ts
 * @param array      $now    本次观测：db/host/port/tables(array)/rows(int)/core(array 表=>行数)
 * @return array    array('mode' => 'ok'|'recover'|'block', 'why' => string)
 */
function dfGuardDecide($prev, $now)
{
    $db = isset($now['db']) ? (string)$now['db'] : '';
    $host = isset($now['host']) ? (string)$now['host'] : '';
    $port = isset($now['port']) ? (int)$now['port'] : 0;

    // 没上次快照 → 首次见，先记账不报警（部署/状态文件被删后的正常冷启动）
    if (!is_array($prev) || empty($prev)) {
        return array('mode' => 'ok', 'why' => 'bootstrap');
    }
    $pdb = isset($prev['db']) ? (string)$prev['db'] : '';
    $phost = isset($prev['host']) ? (string)$prev['host'] : '';
    $pport = isset($prev['port']) ? (int)$prev['port'] : 0;

    // 身份变化（库名/主机/端口）→ 指错了地方：块，绝不往里写
    if ($pdb !== $db || $phost !== $host || $pport !== $port) {
        return array('mode' => 'block', 'why' => 'identity_mismatch');
    }

    $core = dfGuardCoreTables($pdb === 'caoyuanphp' || strpos($pdb, 'php') !== false ? 'web' : 'java');
    // 用 prev 记忆的侧别更稳；这里退化用库名猜，调用方会以 $side 覆盖 core 集合
    $tables = isset($now['tables']) ? $now['tables'] : array();
    $coreNow = isset($now['core']) && is_array($now['core']) ? $now['core'] : array();
    $rows = isset($now['rows']) ? (int)$now['rows'] : -1;
    $prevRows = isset($prev['rows']) ? (int)$prev['rows'] : 0;

    // 核心表一张不剩 → 库被连根清空/删库：走 recover（重建+补推+等 Java）
    $haveCore = 0;
    foreach ($core as $t) { if (in_array($t, $tables, true)) $haveCore++; }
    if ($haveCore === 0 && !empty($prev['tables'])) {
        return array('mode' => 'recover', 'why' => 'tables_wiped');
    }

    // 表都在但整体行数塌了（<=2% 或绝对空）→ 被清空：recover
    if ($rows >= 0 && $prevRows > 0) {
        $floor = max(50, (int)($prevRows * 0.02));
        if ($rows <= $floor) {
            return array('mode' => 'recover', 'why' => 'rows_wiped');
        }
        // 核心表精确计数佐证（TABLE_ROWS 是估计值，避免误判）
        $zeroCore = 0;
        $checkedCore = 0;
        foreach ($coreNow as $t => $c) {
            $checkedCore++;
            if ((int)$c <= 0) $zeroCore++;
        }
        if ($checkedCore > 0 && $zeroCore === $checkedCore) {
            return array('mode' => 'recover', 'why' => 'core_zeroed');
        }
    }

    return array('mode' => 'ok', 'why' => 'healthy');
}

/**
 * 连接后体检（在 initTables 之前调用，块模式直接抛，避免往可疑库里建表）。
 * @return string 'ok'|'recover'|'block'
 */
function dfGuardCheck($mdb, $conn, $side)
{
    $st = dfGuardReadState();
    $prev = isset($st[$side]) && is_array($st[$side]) ? $st[$side] : null;
    $now = time();

    // 节流：刚体检过 → 直接沿用缓存判定（块/恢复态也一样缓存，行为一致）
    if (is_array($prev) && isset($prev['ts']) && ($now - (int)$prev['ts']) < DF_GUARD_TTL
        && in_array(isset($prev['mode']) ? $prev['mode'] : '', array('ok', 'recover', 'block'), true)) {
        return $prev['mode'];
    }

    $dbName = isset($conn['name']) ? (string)$conn['name'] : '';
    $host = isset($conn['host']) ? (string)$conn['host'] : '';
    $port = isset($conn['port']) ? (int)$conn['port'] : 3306;

    // ---- 完整体检 ----
    $tables = array();
    $rs = $mdb->query("SELECT table_name FROM information_schema.tables WHERE table_schema='"
        . $mdb->escapeString($dbName) . "'");
    if ($rs) {
        while ($row = $rs->fetch_assoc()) $tables[] = (string)$row['table_name'];
    }
    $estRows = 0;
    $rs = $mdb->query("SELECT SUM(TABLE_ROWS) s FROM information_schema.tables WHERE table_schema='"
        . $mdb->escapeString($dbName) . "'");
    if ($rs && ($row = $rs->fetch_assoc())) $estRows = (int)$row['s'];

    $core = dfGuardCoreTables($side);
    $coreNow = array();
    foreach ($core as $t) {
        if (!in_array($t, $tables, true)) continue;
        $v = dfGuardScalar($mdb, "SELECT COUNT(*) FROM `" . $t . "`");
        if ($v !== null) $coreNow[$t] = (int)$v;
    }

    $nowInfo = array(
        'db' => $dbName, 'host' => $host, 'port' => $port,
        'tables' => $tables, 'rows' => $estRows, 'core' => $coreNow,
    );
    $decide = dfGuardDecide($prev, $nowInfo, $side);
    $mode = $decide['mode'];

    $entry = array(
        'db' => $dbName, 'host' => $host, 'port' => $port,
        'tables' => count($tables), 'rows' => $estRows,
        'core' => $coreNow, 'ts' => $now, 'mode' => $mode,
    );
    if ($mode === 'block') {
        $entry['alert'] = 'sql.md 里这一侧指向的数据库与上次运行不一致（上次 '
            . (is_array($prev) ? ($prev['host'] . ':' . $prev['port'] . '/' . $prev['db']) : '?')
            . ' → 现在 ' . $host . ':' . $port . '/' . $dbName . '）。'
            . '为避免写错库破坏数据，已停止对该库的一切操作并拒绝与 Java 握手。'
            . '请登录服务器核对 sql.md 的数据库配置段（地址/端口/库名/账号），改对后本护栏会自动恢复。';
    } elseif ($mode === 'recover') {
        $entry['alert'] = '检测到该库被清空（' . $decide['why'] . '），已执行自愈：补表结构并尝试从最近备份补推 SQL 数据，'
            . '随后放行 Java 握手补推其余数据。PHP 侧自有表（工单/订单/流水等）只能从备份恢复，请检查 debug.log 的补推明细。';
    } else {
        $entry['alert'] = null;
    }

    $st[$side] = $entry;
    dfGuardWriteState($st);

    if ($mode === 'block') {
        dfGuardLog('dbGuard[' . $side . '] ★ 块模式：' . $entry['alert']);
    } elseif ($mode === 'recover') {
        dfGuardLog('dbGuard[' . $side . '] ★ 恢复模式：' . $decide['why'] . '，行数 '
            . $estRows . '（上次 ' . (is_array($prev) ? (int)$prev['rows'] : 0) . '）');
    }
    return $mode;
}

function dfGuardScalar($mdb, $sql)
{
    try {
        $rs = $mdb->query($sql);
        if ($rs && ($row = $rs->fetch_assoc())) {
            foreach ($row as $v) return $v;
        }
    } catch (\Throwable $e) { return null; }
    return null;
}

function dfGuardLog($msg)
{
    if (function_exists('debugLog')) { debugLog($msg); return; }
    @error_log($msg);
}

/** 恢复模式动作：补表结构（db_schema_mysql.sql）+ 把仍为空的表从最近可读备份补推数据 */
function dfGuardRecover($mdb, $side)
{
    $done = array('schema' => 0, 'restored' => array(), 'skipped' => array(), 'err' => array());

    // 1) 缺的表用内置 schema 补（initTables 通常已经补过，这里是双保险）
    $schemaFile = __DIR__ . '/db_schema_mysql.sql';
    if (is_file($schemaFile) && is_readable($schemaFile)) {
        $done['schema'] = dfGuardApplySchema($mdb, $side, $schemaFile);
    }

    // 2) 仍为空的表 → 从最近一次可读备份补推（只补 0 行表，绝不覆盖有数据的表）
    try {
        $dump = dfGuardPickDump($side);
        if ($dump === null) {
            $done['skipped'][] = 'no_readable_dump';
        } else {
            $done['restored'] = dfGuardRestoreEmptyTables($mdb, $dump);
        }
    } catch (\Throwable $e) {
        $done['err'][] = $e->getMessage();
    }

    dfGuardLog('dbGuard[' . $side . '] 自愈执行明细: ' . json_encode($done, JSON_UNESCAPED_UNICODE));
    return $done;
}

/** 从 db_schema_mysql.sql 里把本侧缺失的表建出来（CREATE TABLE IF NOT EXISTS） */
function dfGuardApplySchema($mdb, $side, $schemaFile)
{
    $javaTables = function_exists('dbJavaTables') ? dbJavaTables() : array();
    $sql = (string)@file_get_contents($schemaFile);
    if ($sql === '') return 0;
    // 只认 CREATE TABLE 段：去掉 DROP/LOCK/UNLOCK（恢复期绝不能删表）
    $lines = preg_split('/\r\n|\r|\n/', $sql);
    $buf = '';
    $statements = array();
    foreach ($lines as $ln) {
        $t = trim($ln);
        if ($t === '' || $t[0] === '#' || strpos($t, '--') === 0) continue;
        if (preg_match('/^(DROP|LOCK|UNLOCK)\b/i', $t)) continue;
        $buf .= $ln . "\n";
        if (substr(rtrim($t), -1) === ';') {
            $statements[] = $buf;
            $buf = '';
        }
    }
    if (trim($buf) !== '') $statements[] = $buf;

    $created = 0;
    foreach ($statements as $s) {
        if (!preg_match('/^CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?([A-Za-z0-9_]+)`?/i', ltrim($s), $m)) continue;
        $tbl = $m[1];
        // 归属过滤：java 组表只准建到 java 侧，其余建到 web 侧
        if ($side === 'java') {
            if (!in_array($tbl, $javaTables, true)) continue;
        } else {
            if (in_array($tbl, $javaTables, true)) continue;
        }
        // 表已存在就跳过
        $v = dfGuardScalar($mdb, "SELECT COUNT(*) c FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"
            . $mdb->escapeString($tbl) . "'");
        if ($v !== null && (int)$v > 0) continue;
        try {
            $stmt = preg_replace('/^CREATE TABLE\s+(?!IF NOT EXISTS)/i', 'CREATE TABLE IF NOT EXISTS ', ltrim($s), 1);
            $mdb->exec($stmt);
            $created++;
        } catch (\Throwable $e) {
            dfGuardLog('dbGuard: 建表失败 ' . $tbl . ': ' . $e->getMessage());
        }
    }
    return $created;
}

/** 挑一份最近的可读备份（库名出现在文件名里、年龄合规、按 mtime 新优先） */
function dfGuardPickDump($side)
{
    $st = dfGuardReadState();
    $dbName = isset($st[$side]['db']) ? (string)$st[$side]['db'] : '';
    if ($dbName === '') return null;
    $cands = array_merge(
        glob('/www/backup/dr*/*' . $dbName . '*.sql') ?: array(),
        glob('/www/backup/*' . $dbName . '*.sql') ?: array(),
        glob(__DIR__ . '/../backup/*' . $dbName . '*.sql') ?: array()
    );
    $best = null;
    $bestMt = 0;
    $now = time();
    foreach ($cands as $f) {
        if (!is_file($f) || !is_readable($f)) continue;
        $mt = @filemtime($f);
        if (!$mt || ($now - $mt) > DF_GUARD_BACKUP_MAX_AGE) continue;
        if ($mt > $bestMt) { $bestMt = $mt; $best = $f; }
    }
    return $best;
}

/**
 * 备份补推：只执行「目标表当前 0 行」的 INSERT / 缺失表的 CREATE，其余语句一律跳过。
 * 返回 array('表名' => 补推行数)。
 */
function dfGuardRestoreEmptyTables($mdb, $dumpFile)
{
    $out = array();
    $fh = @fopen($dumpFile, 'r');
    if (!$fh) return $out;
    $buf = '';
    $executed = 0;
    while (!feof($fh)) {
        $line = fgets($fh);
        if ($line === false) break;
        $buf .= $line;
        if (rtrim($line) === '' || substr(rtrim($line), -1) !== ';') continue;
        $stmt = trim($buf);
        $buf = '';
        if ($stmt === '') continue;
        if ($executed >= DF_GUARD_MAX_STMTS) break;
        if (preg_match('/^INSERT INTO\s+`?([A-Za-z0-9_]+)`?/i', $stmt, $m)) {
            $tbl = $m[1];
            $v = dfGuardScalar($mdb, "SELECT COUNT(*) c FROM `" . $tbl . "`");
            if ($v === null) continue;           // 表不存在：跳过（不该由备份建表）
            if ((int)$v > 0) continue;           // 有数据的表绝不覆盖
            try {
                $mdb->exec($stmt);
                $n = 1;
                $out[$tbl] = isset($out[$tbl]) ? $out[$tbl] + $n : $n;
                $executed++;
            } catch (\Throwable $e) {
                dfGuardLog('dbGuard: 补推 ' . $tbl . ' 失败: ' . $e->getMessage());
            }
        } elseif (preg_match('/^CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?([A-Za-z0-9_]+)`?/i', $stmt, $m)) {
            $tbl = $m[1];
            $v = dfGuardScalar($mdb, "SELECT COUNT(*) c FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='"
                . $mdb->escapeString($tbl) . "'");
            if ($v !== null && (int)$v > 0) continue;
            try {
                $stmt2 = preg_replace('/^CREATE TABLE\s+(?!IF NOT EXISTS)/i', 'CREATE TABLE IF NOT EXISTS ', $stmt, 1);
                $mdb->exec($stmt2);
                $executed++;
            } catch (\Throwable $e) {
                dfGuardLog('dbGuard: 恢复期建表 ' . $tbl . ' 失败: ' . $e->getMessage());
            }
        }
    }
    fclose($fh);
    return $out;
}

/**
 * 管理登录页横幅：有未消除的护栏告警时就亮出来（块/恢复态都会显示）。
 * 告警是派生状态：数据库恢复健康后下一次体检自动转 ok，横幅随之消失。
 */
function dfGuardAdminBanner()
{
    $st = dfGuardReadState();
    $msgs = array();
    foreach ($st as $side => $s) {
        if (is_array($s) && !empty($s['alert']) && in_array($s['mode'], array('block', 'recover'), true)) {
            $label = ($side === 'java') ? 'Java 库(caoyuanjava)' : 'PHP 库(caoyuanphp)';
            $msgs[] = '<b>[' . htmlspecialchars($label) . ']</b> ' . htmlspecialchars((string)$s['alert'])
                . ' <span style="opacity:.8">(' . htmlspecialchars($s['mode'] === 'block' ? '已停止操作并拒绝握手' : '自愈进行中') . ')</span>';
        }
    }
    if (!$msgs) return '';
    $css = '<style>.df-guard-banner{margin:0 0 14px;padding:10px 12px;border:1px solid #f85149;'
        . 'border-radius:8px;background:#3d1416;color:#ffb3b3;font-size:12px;line-height:1.6;text-align:left;}'
        . '.df-guard-banner b{color:#ff8f8f;}</style>';
    return $css . '<div class="df-guard-banner">⚠ 数据库安全护栏告警：<br>' . implode('<br>', $msgs) . '</div>';
}
