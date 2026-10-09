<?php
/**
 * install.php —— 自毁式数据库配置安装页（2026-10-09 大重构）
 *
 * 职责（只做一件事）：把运行模式与 MySQL 连接地址写进 sql.md 的「配置区开始」哨兵块，
 *                     写 install.lock 锁定，然后把自己删掉（unlink(__FILE__)）。
 *
 * 设计要点：
 *   1) 只依赖 security.php，**不加载 core.php** —— 初始化阶段 core.php 未必能跑（配置还没写完）。
 *   2) 门禁沿用 secGatePage(true)+secTokenGate(true)：127.0.0.1/::1 恒通、?boot= 引导令牌通、
 *      IP 白名单通；被挡住就 nginx 原生 404（绝不 403 —— 403 等于承认文件存在）。
 *   3) 只替换 sql.md 里含哨兵的那个 ```json 代码块，说明表格 / 常见问题原样保留。
 *   4) 写盘是「备份 → 原子写 → 回读校验」三步；校验不过就还原备份、绝不留半份配置。
 *   5) install.lock 存在 ⇒ 立刻 404（页面装完即失效，即使 __FILE__ 没删成也不会二次执行）。
 *   6) 全程 PHP 7.3 兼容（无箭头函数 / match / str_contains）。
 *
 * 与 admin_2fa_setup.php 的分工：那个页管 config.php 的 SEC-UPDATE 区（安全配置），
 * 本页管 sql.md 配置区（数据库配置）。两者互不引用。
 */
require_once __DIR__ . '/security.php';

// ============================ 门禁 ============================
// ① 已装过 → 与文件不存在完全一致的表现（本页也可能还没来得及自删）
if (is_file(__DIR__ . '/install.lock')) secNginx404();

// ② 不做 IP 白名单验证（2026-10-09 需求）：全新站根本没有白名单可言，拿白名单
//    挡安装页只会把部署者锁在门外；已引导站点由下面 ③ 的令牌层把关（?token= / ?boot=）。
//    原 secGatePage(true) 在「已引导但没装库」时会把非白名单 IP 挡成 404。
// ③ 动态访问令牌（config 里有 SEC_ACCESS_TOKEN 时才生效；全新部署为空 → 放行）
secTokenGate(true);
secEnsureSession();

define('INSTALL_LOCK', __DIR__ . '/install.lock');
define('INSTALL_MD',   defined('SQL_MD_PATH') ? SQL_MD_PATH : __DIR__ . '/sql.md');

$cur = sqlMdDbConfig();   // static 缓存，此刻读的是「写入前」的旧配置，仅用于回显
$err = '';
$ok  = '';
$saved = null;            // 本次 POST 成功写入的配置 → 完成页回显供截图/手抄

// CSRF：会话级随机数（不依赖 SEC_ACCESS_TOKEN，全新部署时它还是空的）
if (empty($_SESSION['inst_csrf'])) {
    $_SESSION['inst_csrf'] = bin2hex(random_bytes(16));
}
$csrf = (string)$_SESSION['inst_csrf'];

/** 当前 ip 是否本地（本机访问给出更宽松的提示文案） */
function instIsLocal() {
    $ip = secClientIp();
    return $ip === '127.0.0.1' || $ip === '::1';
}

// ============================ 校验 ============================
/**
 * 校验一次提交。返回 array('mode'=>'db'|'mysql', 'dir'=>..., 'java'=>..., 'web'=>..., 'warn'=>[])
 * 校验不过抛 Exception，异常消息给用户看。
 */
function instValidate(array $post) {
    $warn = array();
    $mode = isset($post['mode']) ? strtolower(trim((string)$post['mode'])) : 'db';
    if ($mode !== 'db' && $mode !== 'mysql') {
        throw new Exception('运行模式取值非法（只接受 db 或 mysql）。');
    }
    $dir = isset($post['dir']) ? trim((string)$post['dir']) : '';

    $side = function ($k) use ($post) {
        $g = function ($f) use ($k, $post) {
            $key = $k . '_' . $f;
            return isset($post[$key]) ? trim((string)$post[$key]) : '';
        };
        return array('host' => $g('host'), 'port' => $g('port'), 'name' => $g('name'),
                     'user' => $g('user'), 'pass' => $g('pass'));
    };

    $java = $side('java');
    $web  = $side('web');
    $entries = array('java' => $java, 'web' => $web);

    if ($mode === 'db') {
        // db 模式下地址允许全空（也允许填 —— 保存但不生效，回显时给警告）
        foreach ($entries as $k => $e) {
            $filled = array_filter($e, function ($v) { return $v !== ''; });
            if (!empty($filled)) {
                $warn[] = '当前是 db 模式，' . strtoupper($k) . ' 侧的 MySQL 地址不会生效，已原样保存。';
            }
        }
        // 存储位置必须是合法相对/绝对路径，且目录可写
        if ($dir !== '') {
            $dir = str_replace('\\', '/', $dir);
            if ($dir[0] !== '/' && strpos($dir, './') !== 0 && strpos($dir, '../') !== 0
                && strpos($dir, '/') !== false && !preg_match('#^[A-Za-z]:/#', $dir)) {
                throw new Exception('数据库存储位置写法不合法。用 / 开头=绝对路径，./ 开头=相对本目录，../ 开头=相对上层。');
            }
            $abs = instResolveDir($dir);
            if (!is_dir($abs) && !@mkdir($abs, 0755, true) && !is_dir($abs)) {
                throw new Exception('无法创建数据库目录：' . $abs . '（请检查 Web 目录权限）。');
            }
            if (!is_writable($abs)) {
                throw new Exception('数据库目录不可写：' . $abs);
            }
        }
    } else {
        // mysql 模式：mysqli 扩展必须在
        if (!extension_loaded('mysqli')) {
            throw new Exception('当前 PHP 没有安装 mysqli 扩展，无法使用 mysql 模式。'
                . '请先安装扩展，或改用 db 模式。');
        }
        // 每侧地址要么全填、要么全空
        $complete = 0;
        foreach ($entries as $k => $e) {
            $empty = 0;
            foreach ($e as $v) { if ($v === '') $empty++; }
            if ($empty === count($e)) continue;          // 全空 = 该侧回退 db，允许
            if ($empty > 0) {
                $throw = array();
                foreach ($e as $fk => $fv) { if ($fv === '') $throw[] = $fk; }
                throw new Exception(strtoupper($k) . ' 侧 MySQL 地址信息不完整，缺少：'
                    . implode('、', $throw) . '。每侧要么填全、要么全空。');
            }
            if (!ctype_digit($e['port'])) {
                throw new Exception(strtoupper($k) . ' 侧端口必须是纯数字。');
            }
            $p = (int)$e['port'];
            if ($p < 1 || $p > 65535) {
                throw new Exception(strtoupper($k) . ' 侧端口超出范围（1-65535）。');
            }
            $complete++;
        }
        if ($complete === 0) {
            throw new Exception('mysql 模式下至少要有一侧地址填全，否则会静默回退 db 模式，等于白配。');
        }
        // 实连 + 建表权限探测：MySQL 模式下建表失败是降级不抛异常的（站点会悄悄跑在空库上），
        // 所以必须在这里就替部署者确认「连得上、库在、能建表」。
        foreach ($entries as $k => $e) {
            if (array_sum(array_map(function ($v) { return $v === '' ? 1 : 0; }, $e)) === count($e)) continue;
            instProbeMySql($k, $e);
        }
    }
    return array('mode' => $mode, 'dir' => $dir, 'java' => $java, 'web' => $web, 'warn' => $warn);
}

/** 解析存储位置为绝对路径（与 security.php 的 sqlDbDir() 同一套规则，独立实现避免耦合）。 */
function instResolveDir($p) {
    $p = str_replace('\\', '/', $p);
    $base = str_replace('\\', '/', __DIR__);
    while (strpos($p, '../') === 0) { $base = dirname($base); $p = substr($p, 3); }
    if (strpos($p, './') === 0) $p = substr($p, 2);
    if ($p !== '' && $p[0] === '/') return rtrim($p, '/');
    if ($p === '') return $base;
    return rtrim($base, '/') . '/' . trim($p, '/');
}

/**
 * MySQL 实连探测：连得上 + 库存在 + 能建表（建完立刻删）。
 * 失败抛 Exception，消息带 PHP/mysqli 原始错误码，方便部署者定位。
 */
function instProbeMySql($slot, array $e) {
    $m = mysqli_init();
    if (!($m instanceof mysqli)) {
        throw new Exception(strtoupper($slot) . ' 侧：mysqli_init 失败。');
    }
    @mysqli_options($m, MYSQLI_OPT_CONNECT_TIMEOUT, 6);
    $okc = @mysqli_real_connect($m, $e['host'], $e['user'], $e['pass'], $e['name'], (int)$e['port']);
    if (!$okc) {
        $errno = $m->connect_errno;
        $msg   = $m->connect_error;
        throw new Exception(strtoupper($slot) . ' 侧连接失败 #' . $errno . '：' . $msg
            . '（常见原因：MySQL 服务未启动、账密错误、库名不存在、端口不对）');
    }
    // 建表权限探测（软依赖下建表失败只记日志，站点会悄悄跑在空库上，这里必须硬报）
    $tbl = '_install_probe';
    $failed = '';
    if (!@mysqli_query($m, 'DROP TABLE IF EXISTS `' . $tbl . '`')) {
        $failed = 'DROP: ' . mysqli_error($m);
    } elseif (!@mysqli_query($m, 'CREATE TABLE `' . $tbl . "` (\n  `id` BIGINT NOT NULL AUTO_INCREMENT, PRIMARY KEY (`id`)\n) ENGINE=InnoDB")) {
        $failed = 'CREATE: ' . mysqli_error($m) . '（' . mysqli_errno($m) . '）';
    } else {
        @mysqli_query($m, 'DROP TABLE IF EXISTS `' . $tbl . '`');
    }
    @mysqli_close($m);
    if ($failed !== '') {
        throw new Exception(strtoupper($slot) . ' 侧连得上，但建表失败：' . $failed
            . ' —— 站点初始化需要 CREATE 权限，请检查账号授权。');
    }
}

// ============================ 生成 sql.md 配置区 ============================
/** 把本次配置序列化成带哨兵的 json 数组（与 sql.md 模板同形）。 */
function instBuildArray(array $c) {
    $mkEntry = function ($slot, $e) use ($c) {
        return array(
            '归属'     => $slot,
            '数据库地址' => $e['host'],
            '端口'      => $e['port'],
            '数据库名'  => $e['name'],
            '数据库账号' => $e['user'],
            '数据库密码' => $e['pass'],
        );
    };
    return array(
        array('text' => '配置区开始'),
        array('运行模式' => ($c['mode'] === 'mysql' ? 'mysql' : 'true'),
              '数据库存储位置' => $c['dir']),
        $mkEntry('java', $c['java']),
        $mkEntry('web',  $c['web']),
        array('text' => '配置区域结束'),
    );
}

/**
 * 只替换 sql.md 里含「配置区开始」的那个 ```json 代码块，其余文本原样保留。
 * 找不到哨兵块（文件不存在 / 格式坏）→ 用模板整个重建（这时没有说明书内容可保留）。
 * @return string 新的 sql.md 全文
 */
function instSpliceMd($md, array $arr) {
    $json = json_encode($arr, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if ($json === false) throw new Exception('配置序列化失败：' . json_last_error_msg());
    if (!is_string($md) || trim($md) === '') {
        return instFallbackMd($json);
    }
    // 逐块匹配 ```json ... ```，只换含哨兵的那块
    $out = '';
    $last = 0;
    $re = '/```(?:json|JSON)?\s*(.*?)```/s';
    if (!preg_match_all($re, $md, $mm, PREG_OFFSET_CAPTURE)) {
        return instFallbackMd($json);
    }
    $replaced = false;
    foreach ($mm[1] as $hit) {
        $body = $hit[0];
        $off  = $hit[1];
        $a = json_decode(trim($body), true);
        if (!is_array($a)) continue;
        $has = false;
        foreach ($a as $el) {
            if (is_array($el) && isset($el['text'])
                && strpos((string)$el['text'], '配置区开始') !== false) { $has = true; break; }
        }
        if (!$has) continue;
        $s = $off;
        $e = $off + strlen($body);
        $out .= substr($md, $last, $s - $last) . "\n" . $json . "\n";
        $last = $e;
        $replaced = true;
    }
    if (!$replaced) return instFallbackMd($json);
    $out .= substr($md, $last);
    return $out;
}

/** 无哨兵块时的完整模板（与仓库内 sql.md 分发版同结构）。 */
function instFallbackMd($json) {
    return "```json\n" . $json . "\n```\n"
         . "\n\n| 配置值 | 意义 | 示例 |\n| :---: | :---: | :---: |\n"
         . "| 运行模式 | 选择db/mysql运行。不区分大小写，`true和db`表示使用db，`false和mysql`表示mysql | true |\n"
         . "| 数据库位置 | db模式专用，`/xxx`绝对路径，`./xxx`相对当前目录，`../xxx`相对上层 | ./db/ |\n"
         . "| 数据库地址 | mysql专用，连接地址。一般填127.0.0.1或localhost | localhost |\n"
         . "| 端口 | mysql专用，默认3306 | 3306 |\n"
         . "| 数据库名 | mysql专用 | sqlname |\n"
         . "| 数据库账号 | mysql专用 | mysqluser |\n"
         . "| 数据库密码 | mysql专用，必须与账号成对 | mysqlpassword |\n"
         . "\n## 常见问题\n\n"
         . "问：db不可用<br>\n答: 检查php扩展-sqllite是否安装<br>\n"
         . "问: mysql连不上<br>\n答: 检查mysql服务是否启动、账密端口是否正确<br>\n"
         . "问: 解析配置失败<br>\n答: 把整个json配置区丢入json校验器校验一次json格式\n";
}

// ============================ POST：写配置 ============================
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $act = isset($_POST['act']) ? (string)$_POST['act'] : '';
    $got = isset($_POST['csrf']) ? (string)$_POST['csrf'] : '';
    if ($act !== 'save' || !hash_equals($csrf, $got)) {
        $err = '请求校验失败，请刷新页面后重试。';
    } else {
        try {
            $cfg = instValidate($_POST);
            $new = instSpliceMd(is_file(INSTALL_MD) ? @file_get_contents(INSTALL_MD) : '',
                                instBuildArray($cfg));

            // --- 备份（*.bak* 在 gitignore 内，不会污染仓库） ---
            $bak = '';
            if (is_file(INSTALL_MD)) {
                $bak = INSTALL_MD . '.bak.' . date('YmdHis');
                if (!@copy(INSTALL_MD, $bak)) $bak = '';
            }

            // --- 原子写：先写 .tmp 再 rename，避免中途失败留下半份配置 ---
            $tmp = INSTALL_MD . '.insttmp';
            if (@file_put_contents($tmp, $new, LOCK_EX) === false) {
                throw new Exception('写入临时文件失败（目录不可写？）：' . $tmp);
            }
            if (!@rename($tmp, INSTALL_MD)) {
                @unlink($tmp);
                // Windows 上 rename 覆盖已存在的目标可能失败 → 退化为直接写
                if (@file_put_contents(INSTALL_MD, $new, LOCK_EX) === false) {
                    throw new Exception('写入 sql.md 失败。');
                }
            }

            // --- 回读校验：解出来必须与提交的一致，否则还原备份 ---
            $chk = instVerifyWritten($cfg);
            if ($chk !== true) {
                if ($bak !== '') @copy($bak, INSTALL_MD);
                @unlink($tmp);
                throw new Exception('回读校验失败，已还原原配置。原因：' . $chk);
            }

            // --- 锁定 + 自毁 ---
            @file_put_contents(INSTALL_LOCK, date('c') . "\n"
                . 'ip=' . secClientIp() . "\n"
                . 'mode=' . $cfg['mode'] . "\n"
                . 'md=' . INSTALL_MD . "\n", LOCK_EX);
            @chmod(INSTALL_LOCK, 0644);
            @unlink(__FILE__);

            $ok = $cfg['mode'] === 'mysql' ? 'MySQL 模式配置已写入' : 'db 模式配置已写入';
        } catch (Exception $e) {
            $err = $e->getMessage();
        }
    }
}

/**
 * 回读 sql.md 确认写进去的就是我们提交的那份（绕开 sqlMdDbConfig 的 static 缓存）。
 * @return true|string true=通过；否则返回失败原因
 */
function instVerifyWritten(array $want) {
    $md = @file_get_contents(INSTALL_MD);
    if (!is_string($md)) return '读不回 sql.md';
    if (!preg_match_all('/```(?:json|JSON)?\s*(.*?)```/s', $md, $mm) || empty($mm[1])) {
        return '读不回配置块';
    }
    $arr = null;
    foreach ($mm[1] as $body) {
        $a = json_decode(trim($body), true);
        if (!is_array($a)) continue;
        foreach ($a as $el) {
            if (is_array($el) && isset($el['text'])
                && strpos((string)$el['text'], '配置区开始') !== false) { $arr = $a; break; }
        }
        if ($arr !== null) break;
    }
    if ($arr === null) return '找不到配置区开始哨兵';

    $modeGot = null;
    $ents = array();
    $seq = 0;
    foreach ($arr as $el) {
        if (!is_array($el)) continue;
        if (array_key_exists('运行模式', $el)) {
            $modeGot = strtolower(trim((string)$el['运行模式']));
            continue;
        }
        if (!array_key_exists('数据库地址', $el) && !array_key_exists('数据库名', $el)) continue;
        $own = strtolower(trim(isset($el['归属']) ? (string)$el['归属'] : ''));
        $slot = ($own === '') ? (($seq++ === 0) ? 'java' : 'web') : $own;
        $slot = (strpos($slot, 'java') !== false || strpos($slot, 'plugin') !== false) ? 'java'
              : ((strpos($slot, 'web') !== false || strpos($slot, 'php') !== false) ? 'web' : $slot);
        $ents[$slot] = $el;
    }
    $wantMode = ($want['mode'] === 'mysql') ? 'mysql' : 'true';
    if ($modeGot !== strtolower($wantMode)) {
        return '运行模式没写对（读回=' . var_export($modeGot, true) . '）';
    }
    foreach (array('java', 'web') as $slot) {
        if (!isset($ents[$slot])) return $slot . ' 侧地址条目丢了';
        $e = $ents[$slot];
        foreach (array('数据库地址' => 'host', '端口' => 'port', '数据库名' => 'name',
                       '数据库账号' => 'user', '数据库密码' => 'pass') as $jk => $wk) {
            $rv = array_key_exists($jk, $e) ? (string)$e[$jk] : '';
            if ($rv !== (string)$want[$slot][$wk]) {
                return $slot . ' 侧 ' . $jk . ' 读回不一致';
            }
        }
    }
    return true;
}

// ============================ 渲染 ============================
$modeNow = $cur['mode'];
$modeSel = ($_SERVER['REQUEST_METHOD'] === 'POST' && $err !== '')
    ? (isset($_POST['mode']) ? strtolower(trim((string)$_POST['mode'])) : 'db')
    : $modeNow;
$val = function ($k, $d = '') use ($cur, $err) {
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && $err !== '' && isset($_POST[$k])) {
        return htmlspecialchars((string)$_POST[$k], ENT_QUOTES, 'UTF-8');
    }
    $cur = is_array($cur) ? $cur : array();
    if ($k === 'dir')   return htmlspecialchars(isset($cur['dir']) ? (string)$cur['dir'] : '', ENT_QUOTES, 'UTF-8');
    if ($k === 'mode')  return '';
    if (preg_match('/^(java|web)_(host|port|name|user|pass)$/', $k, $m)) {
        // ★ 全新站 sql.md 配置区账密全空 → sqlMdDbConfig 的 java/web 槽是 null
        //   （"字段不全 → null → 该侧兜底 db"），直接 $e[...] 在 PHP 8 会每个输入框
        //   报一条 "Trying to access array offset on null"（实录：test12 第 391 行）。
        $e = (isset($cur[$m[1]]) && is_array($cur[$m[1]])) ? $cur[$m[1]] : array();
        $map = array('host' => 'host', 'port' => 'port', 'name' => 'name', 'user' => 'user', 'pass' => 'pass');
        $rv = isset($e[$map[$m[2]]]) ? (string)$e[$map[$m[2]]] : '';
        return htmlspecialchars($rv, ENT_QUOTES, 'UTF-8');
    }
    return htmlspecialchars((string)$d, ENT_QUOTES, 'UTF-8');
};
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>数据库配置 · 初始化</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0f1115;color:#e6e8ee;font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
.wrap{max-width:760px;margin:32px auto;padding:0 16px}
.card{background:#171a21;border:1px solid #262a33;border-radius:10px;padding:22px 24px;margin-bottom:18px}
h1{font-size:20px;margin:0 0 6px}
.sub{color:#98a0ad;font-size:13px;margin:0 0 18px}
h2{font-size:15px;margin:22px 0 10px;color:#cdd3dd}
label{display:block;font-size:13px;color:#aab2bf;margin:12px 0 5px}
input[type=text],input[type=password],select{width:100%;padding:9px 11px;background:#0f1115;border:1px solid #2e333d;
  border-radius:6px;color:#e6e8ee;font-size:14px;outline:none}
input:focus,select:focus{border-color:#4c8dff}
.row{display:flex;gap:12px}
.row>div{flex:1}
.modes{display:flex;gap:10px;margin-top:6px}
.modes label{flex:1;margin:0;padding:11px;border:1px solid #2e333d;border-radius:6px;cursor:pointer;
  background:#12151b;text-align:center;color:#c3cad4}
.modes input{display:none}
.modes label.on{border-color:#4c8dff;background:#16243b;color:#fff}
.btn{display:inline-block;padding:11px 22px;background:#4c8dff;color:#fff;border:0;border-radius:6px;
  font-size:14px;cursor:pointer;margin-top:20px}
.btn:hover{background:#3f7ce8}
.note{padding:11px 14px;border-radius:6px;font-size:13.5px;margin:14px 0}
.err{background:#2a1519;border:1px solid #5a2730;color:#ffb4bc}
.ok{background:#12261a;border:1px solid #26543a;color:#9ff0bb}
.info{background:#141b2a;border:1px solid #26314a;color:#a8c1f0}
code{background:#0d0f14;padding:2px 6px;border-radius:4px;font-size:13px;color:#e6b673}
ul{margin:6px 0;padding-left:20px}
.muted{color:#7d8794;font-size:12.5px}
.kbd{background:#22262e;border:1px solid #343a45;padding:2px 7px;border-radius:4px;font-size:12.5px}
</style>
</head>
<body>
<div class="wrap">

<div class="card">
  <h1>数据库配置初始化</h1>
  <p class="sub">本页把运行模式与 MySQL 地址写进 <code>sql.md</code> 的配置区，
     写入成功后会<strong>自动删除本文件</strong>并生成 <code>install.lock</code> 锁定。
     页面配置好前请勿关闭；只保留本机或持引导令牌可访问。</p>

  <?php if ($ok !== ''): ?>
    <div class="note ok">
      <strong><?php echo htmlspecialchars($ok, ENT_QUOTES, 'UTF-8'); ?></strong>
      <ul>
        <li>配置已写入 <code>sql.md</code> 并回读校验通过。</li>
        <li>已生成 <code>install.lock</code>，本页再次访问将返回 404。</li>
        <li><code>install.php</code> 已自我删除（后台静默完成，页面照常停留供你截图留档）。</li>
      </ul>

      <?php if (is_array($saved)): ?>
      <div style="margin-top:10px;border-top:1px solid #26543a;padding-top:10px">
        <div style="font-weight:600;margin:6px 0">📋 本次写入的配置 —— 请截图或手抄留档：</div>
        <table style="width:100%;border-collapse:collapse;font-size:13px">
          <tr>
            <td style="padding:4px 8px;color:#9ff0bb;white-space:nowrap">运行模式</td>
            <td><code><?php echo htmlspecialchars($saved['mode'] === 'mysql' ? 'mysql' : 'db（SQLite 文件）', ENT_QUOTES, 'UTF-8'); ?></code></td>
          </tr>
          <?php if ($saved['mode'] !== 'mysql'): ?>
          <tr>
            <td style="padding:4px 8px;color:#9ff0bb;white-space:nowrap">存储位置</td>
            <td><code><?php echo htmlspecialchars($saved['dir'] === '' ? './db/（默认）' : $saved['dir'], ENT_QUOTES, 'UTF-8'); ?></code></td>
          </tr>
          <?php else: ?>
            <?php foreach (array('java' => 'Java 侧（sync.php · Java.db）', 'web' => 'PHP 侧（web.db）') as $slot => $label): ?>
            <?php $e = $saved[$slot]; ?>
            <tr>
              <td style="padding:4px 8px;color:#9ff0bb;vertical-align:top;white-space:nowrap"><?php echo $label; ?></td>
              <td><?php if ($e['host'] === '' && $e['name'] === ''): ?><code>（空 → 自动回退 db 文件）</code><?php else: ?><code><?php echo htmlspecialchars($e['host'] . ':' . $e['port'] . ' · 库名 ' . $e['name'] . ' · 账号 ' . $e['user'] . ' · 密码 ' . $e['pass'], ENT_QUOTES, 'UTF-8'); ?></code><?php endif; ?></td>
            </tr>
            <?php endforeach; ?>
          <?php endif; ?>
        </table>
        <div style="color:#9ff0bb;font-size:12.5px;margin-top:6px">本页已随安装完成而删除 —— 这是最后一处能整份查看该配置的页面；完整配置仍保存在 <code>sql.md</code>。</div>
      </div>
      <?php endif; ?>

      下一步：
      <ul>
        <li><strong>先去 <a href="admin_2fa_setup.php" style="color:#7fb0ff">安全配置引导页</a></strong>：把你当前的 IP 加入白名单、保存访问令牌、设置 2FA。</li>
        <li>mysql 模式 → 配完安全项首次进后台会自动建表（已在本页实连并验证过建表权限）；db 模式 → 配完即可登录。</li>
        <li>记得让 <strong>Java 端</strong>（api/sync.php 所在的独立站点）也用同一份配置：改 sync.php 时两处都要传。</li>
      </ul>
    </div>
  <?php endif; ?>

  <?php if ($err !== ''): ?>
    <div class="note err"><strong>写入失败：</strong><?php echo htmlspecialchars($err, ENT_QUOTES, 'UTF-8'); ?></div>
  <?php endif; ?>

  <form method="post" action="install.php" autocomplete="off">
    <input type="hidden" name="act" value="save">
    <input type="hidden" name="csrf" value="<?php echo htmlspecialchars($csrf, ENT_QUOTES, 'UTF-8'); ?>">
    <?php if (secToken() !== ''): ?>
    <!-- 已引导站点（config 有 SEC_ACCESS_TOKEN）：POST 也要过第三层令牌门禁；
         本页只会渲染给已经过了门禁的访问者，令牌不出圈。全新站点为空 → 不输出。 -->
    <input type="hidden" name="token" value="<?php echo htmlspecialchars(secToken(), ENT_QUOTES, 'UTF-8'); ?>">
    <?php endif; ?>

    <h2>运行模式</h2>
    <div class="modes">
      <label class="<?php echo $modeSel === 'db' ? 'on' : ''; ?>">
        <input type="radio" name="mode" value="db" onchange="mSwitch(this)"
          <?php echo $modeSel === 'db' ? 'checked' : ''; ?>> db（SQLite 文件，默认）</label>
      <label class="<?php echo $modeSel === 'mysql' ? 'on' : ''; ?>">
        <input type="radio" name="mode" value="mysql" onchange="mSwitch(this)"
          <?php echo $modeSel === 'mysql' ? 'checked' : ''; ?>> mysql</label>
    </div>

    <div id="dirBox">
      <label>数据库存储位置 <span class="muted">（仅 db 模式生效；留空 = 默认 <code>./db/</code>）</span></label>
      <input type="text" name="dir" value="<?php echo $val('dir'); ?>" placeholder="./db/">
      <p class="muted">/ 开头=绝对路径；./ 开头=相对本目录；../ 开头=相对上层目录。</p>
    </div>

    <div id="mysqlBox">
      <div class="note info">每侧地址<strong>要么填全、要么全空</strong>；留空的一侧会自动回退 db 文件。
        本页会真实连接并尝试建表，失败会直接报错，不会悄悄写入一份跑不通的配置。</div>

      <h2>Java 侧（api/sync.php 通信组 · Java.db）</h2>
      <div class="row">
        <div><label>连接地址</label><input type="text" name="java_host" value="<?php echo $val('java_host'); ?>" placeholder="127.0.0.1"></div>
        <div><label>端口</label><input type="text" name="java_port" value="<?php echo $val('java_port'); ?>" placeholder="3306"></div>
      </div>
      <div class="row">
        <div><label>数据库名</label><input type="text" name="java_name" value="<?php echo $val('java_name'); ?>" placeholder="game_java"></div>
        <div><label>账号</label><input type="text" name="java_user" value="<?php echo $val('java_user'); ?>" autocomplete="off"></div>
      </div>
      <label>密码</label>
      <input type="password" name="java_pass" value="<?php echo $val('java_pass'); ?>" autocomplete="new-password">

      <h2>PHP 侧（web.db）</h2>
      <div class="row">
        <div><label>连接地址</label><input type="text" name="web_host" value="<?php echo $val('web_host'); ?>" placeholder="127.0.0.1"></div>
        <div><label>端口</label><input type="text" name="web_port" value="<?php echo $val('web_port'); ?>" placeholder="3306"></div>
      </div>
      <div class="row">
        <div><label>数据库名</label><input type="text" name="web_name" value="<?php echo $val('web_name'); ?>" placeholder="game_web"></div>
        <div><label>账号</label><input type="text" name="web_user" value="<?php echo $val('web_user'); ?>" autocomplete="off"></div>
      </div>
      <label>密码</label>
      <input type="password" name="web_pass" value="<?php echo $val('web_pass'); ?>" autocomplete="new-password">
    </div>

    <button class="btn" type="submit">保存配置（并删除本页）</button>
  </form>

  <?php if (!empty($cur['ok'])): ?>
    <p class="muted" style="margin-top:18px;border-top:1px solid #232732;padding-top:14px">
      当前 sql.md 已有配置 —— 模式 <code><?php echo htmlspecialchars($cur['mode'], ENT_QUOTES, 'UTF-8'); ?></code>，
      存储位置 <code><?php echo htmlspecialchars($cur['dir'] === '' ? '(默认 ./db/)' : $cur['dir'], ENT_QUOTES, 'UTF-8'); ?></code>。
      重新保存会覆盖它。
    </p>
  <?php endif; ?>
</div>

<div class="card muted">
  <strong style="color:#aab2bf">访问要求：</strong>
  <?php if (instIsLocal()): ?>
    你当前来自 <span class="kbd">127.0.0.1/::1</span>（本机），可直接配置。
  <?php else: ?>
    需要 <span class="kbd">?boot=引导令牌</span> 或你的 IP 已在白名单内，否则返回 nginx 404。
  <?php endif; ?>
  <br>本页不加载 core.php；写入前会自动备份 <code>sql.md.bak.时间戳</code>，失败会自动还原。
</div>

</div>
<script>
function mSwitch(el){
  var db = (el.value === 'db');
  document.getElementById('dirBox').style.display  = db ? '' : 'none';
  document.getElementById('mysqlBox').style.display = db ? 'none' : '';
  var ls = document.querySelectorAll('.modes label');
  for (var i=0;i<ls.length;i++) ls[i].className = (ls[i].querySelector('input').checked ? 'on' : '');
}
(function(){
  var sel = document.querySelector('.modes input:checked');
  if (sel) mSwitch(sel);
})();
</script>
</body>
</html>
