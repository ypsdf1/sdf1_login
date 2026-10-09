<?php
/**
 * install.php —— 自毁式四步安装向导（2026-10-09 由单表单重构为四步）
 *
 * 四关：
 *   第一关 运行环境：sqlite3 / pdo_sqlite / mysqli 三项亮状态。
 *          缺哪个，第二关就锁死对应模式 —— 让扩展缺失只表现为「选项灰掉」，
 *          而不是把 mysqli/SQLite3 的原始报错甩到用户脸上（会吓到刚入坑的小白）。
 *   第二关 数据库：db 或 mysql（按第一关锁死）。mysql 侧地址/端口预填 127.0.0.1 / 3306，
 *          选本机 MySQL 时只需填账号、密码、库名。提交即扫库：
 *            空   → 静默过关；
 *            非空 → 立即刹车，列出表清单，取消则退回重填，
 *                   继续则要求手输「确认清空」二次确认后才 DROP 全部表。
 *   第三关 邮箱：要么全空（直接过关），要么全不空 —— 发验证码到测试邮箱，
 *          输对才能过关（不允许跳过）。测试邮箱与验证码不落盘。
 *   第四关 安全：IP 白名单必填（可自动填充当前访问 IP）；CF/VA 验证码密钥选填，
 *          留空即关闭；2FA 跳转 admin_2fa_setup.php 复用其完整流程。
 *
 * 点「确认安装」统一写盘：sql.md 配置区 + config.php 的 SMTP_* / CAPTCHA_PROVIDER /
 * ADMIN_IP_WHITELIST + captcha_keys.php → 写 install.lock → unlink 自毁 →
 * 停留完成页回显全部配置，供截图手抄。
 *
 * 设计要点：
 *   1) 只依赖 security.php，**不加载 core.php** —— 初始化阶段 core.php 未必能跑。
 *   2) 门禁：lock 文件在场即 nginx 404；secTokenGate(true) 把关已引导站点的令牌。
 *      不做 IP 白名单验证（全新站根本没有白名单可言）。
 *   3) 每关数据存 session，最后一关才落盘 —— 中途任何失败都不留半份配置。
 *   4) 写盘一律「备份 → 原子写 → 回读校验」，失败还原。
 *   5) install.lock 存在 ⇒ 立刻 404（装完即失效，即使 __FILE__ 没删成也不会二次执行）。
 *   6) 全程 PHP 7.3 兼容（无箭头函数 / match / str_contains）。
 *
 * 与 admin_2fa_setup.php 的分工：那个页管 2FA 与令牌的完整流程，本页管四步安装配置。
 */
require_once __DIR__ . '/security.php';

// ============================ 门禁 ============================
// ① 已装过 → 与文件不存在完全一致的表现（本页也可能还没来得及自删）
if (is_file(__DIR__ . '/install.lock')) secNginx404();

// ② 不做 IP 白名单验证（2026-10-09 需求）：全新站根本没有白名单可言，拿白名单
//    挡安装页只会把部署者锁在门外；已引导站点由下面 ③ 的令牌层把关（?token= / ?boot=）。
// ③ 动态访问令牌（config 里有 SEC_ACCESS_TOKEN 时才生效；全新部署为空 → 放行）
secTokenGate(true);
secEnsureSession();

define('INSTALL_LOCK', __DIR__ . '/install.lock');
define('INSTALL_MD',   defined('SQL_MD_PATH') ? SQL_MD_PATH : __DIR__ . '/sql.md');
define('INST_PURGE_PHRASE', '确认清空');   // 二次确认口令（用户手输，防误触）

$cur = sqlMdDbConfig();   // static 缓存，此刻读的是「写入前」的旧配置，仅用于回显
$err = '';
$ok  = '';
$notice = '';             // 蓝色提示（验证码已发送等）
$saved = null;            // 本次写入的配置 → 完成页回显供截图/手抄

// CSRF：会话级随机数（不依赖 SEC_ACCESS_TOKEN，全新部署时它还是空的）
if (empty($_SESSION['inst_csrf'])) {
    $_SESSION['inst_csrf'] = bin2hex(random_bytes(16));
}
$csrf = (string)$_SESSION['inst_csrf'];

// 扩展检测（每次请求重算：同一 PHP 进程组结果稳定，且能反映运行期真实状态）
$ext = instExtCheck();

// 当前关卡
$step = isset($_SESSION['inst_step']) ? (int)$_SESSION['inst_step'] : 1;
if ($step < 1 || $step > 5) $step = 1;

// ============================ 基础工具 ============================
/** 当前 ip 是否本地（本机访问给出更宽松的提示文案） */
function instIsLocal() {
    $ip = secClientIp();
    return $ip === '127.0.0.1' || $ip === '::1';
}

/**
 * 第一关：扩展检测。
 * sqlite3      —— db（SQLite 文件）模式的硬依赖（core.php / security.php 全用 new SQLite3）
 * pdo_sqlite   —— 正版验证接口 api/minecraft_auth.php 用 PDO('sqlite:')
 * mysqli       —— mysql 模式硬依赖
 * @return array{sqlite3:bool,pdo_sqlite:bool,mysqli:bool,any_db:bool,any_mysql:bool}
 */
function instExtCheck() {
    $e = array(
        'sqlite3'    => extension_loaded('sqlite3'),
        'pdo_sqlite' => extension_loaded('pdo_sqlite'),
        'mysqli'     => extension_loaded('mysqli'),
    );
    $e['any_db']    = $e['sqlite3'];                 // db 模式能不能选
    $e['any_mysql'] = $e['mysqli'];                  // mysql 模式能不能选
    return $e;
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
    if (!@mysqli_query($m, 'DROP TABLE IF EXISTS `' . str_replace('`', '``', $tbl) . '`')) {
        $failed = 'DROP: ' . mysqli_error($m);
    } elseif (!@mysqli_query($m, 'CREATE TABLE `' . str_replace('`', '``', $tbl) . "` (\n  `id` BIGINT NOT NULL AUTO_INCREMENT, PRIMARY KEY (`id`)\n) ENGINE=InnoDB")) {
        $failed = 'CREATE: ' . mysqli_error($m) . '（' . mysqli_errno($m) . '）';
    } else {
        @mysqli_query($m, 'DROP TABLE IF EXISTS `' . str_replace('`', '``', $tbl) . '`');
    }
    @mysqli_close($m);
    if ($failed !== '') {
        throw new Exception(strtoupper($slot) . ' 侧连得上，但建表失败：' . $failed
            . ' —— 站点初始化需要 CREATE 权限，请检查账号授权。');
    }
}

/**
 * 校验第二关提交。返回 array('mode'=>'db'|'mysql', 'dir'=>..., 'java'=>..., 'web'=>..., 'warn'=>[])
 * 校验不过抛 Exception，异常消息给用户看。
 */
function instValidate(array $post, array $ext) {
    $warn = array();
    $mode = isset($post['mode']) ? strtolower(trim((string)$post['mode'])) : 'db';
    if ($mode !== 'db' && $mode !== 'mysql') {
        throw new Exception('运行模式取值非法（只接受 db 或 mysql）。');
    }
    // ★ 第一关锁死：缺对应扩展时该模式不可选（前端已禁用，这里兜底防绕过）
    if (empty($ext['any_db']) && empty($ext['any_mysql'])) {
        throw new Exception('当前 PHP 既没有 sqlite3 也没有 mysqli 扩展，两种运行模式都用不了，'
            . '请先让主机商安装扩展（宝塔面板 → PHP 设置 → 安装扩展），装好后刷新本页。');
    }
    if ($mode === 'db' && empty($ext['any_db'])) {
        throw new Exception('当前 PHP 没有安装 sqlite3 扩展，无法使用 db 模式，请改用 mysql 模式。');
    }
    if ($mode === 'mysql' && empty($ext['any_mysql'])) {
        throw new Exception('当前 PHP 没有安装 mysqli 扩展，无法使用 mysql 模式，请改用 db 模式。');
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

// ============================ 第二关：扫库 / 清空 ============================
/**
 * 扫一个 MySQL 侧的用户表（表名 + 估算行数）。
 * @return array{ok:bool,err:string,tables:array}
 */
function instScanMysqlSide(array $e) {
    $out = array('ok' => false, 'err' => '', 'tables' => array());
    $m = mysqli_init();
    if (!($m instanceof mysqli)) { $out['err'] = 'mysqli_init 失败'; return $out; }
    @mysqli_options($m, MYSQLI_OPT_CONNECT_TIMEOUT, 6);
    if (!@mysqli_real_connect($m, $e['host'], $e['user'], $e['pass'], $e['name'], (int)$e['port'])) {
        $out['err'] = '#' . $m->connect_errno . ' ' . $m->connect_error;
        @mysqli_close($m);
        return $out;
    }
    @mysqli_set_charset($m, 'utf8mb4');
    // TABLE_ROWS 是估算值（InnoDB 不维护精确计数），标注「约」以免误导
    // ★ 库名必须带单引号 —— real_escape_string 只转义不加引号，
    //   裸拼会被 MySQL 当成列名 → Unknown column 'xxx' in 'where clause'
    $sql = "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES"
         . " WHERE TABLE_SCHEMA = '" . $m->real_escape_string($e['name']) . "'"
         . " AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
    $rs = @mysqli_query($m, $sql);
    if ($rs === false) {
        $out['err'] = mysqli_error($m);
        @mysqli_close($m);
        return $out;
    }
    while ($row = mysqli_fetch_assoc($rs)) {
        $out['tables'][] = array(
            'name' => (string)$row['TABLE_NAME'],
            'rows' => ($row['TABLE_ROWS'] === null) ? -1 : (int)$row['TABLE_ROWS'],
        );
    }
    mysqli_free_result($rs);
    @mysqli_close($m);
    $out['ok'] = true;
    return $out;
}

/** 清空一个 MySQL 侧的全部用户表。@return array{ok:bool,err:string,dropped:int} */
function instPurgeMysqlSide(array $e) {
    $out = array('ok' => false, 'err' => '', 'dropped' => 0);
    $m = mysqli_init();
    if (!($m instanceof mysqli)) { $out['err'] = 'mysqli_init 失败'; return $out; }
    @mysqli_options($m, MYSQLI_OPT_CONNECT_TIMEOUT, 6);
    if (!@mysqli_real_connect($m, $e['host'], $e['user'], $e['pass'], $e['name'], (int)$e['port'])) {
        $out['err'] = '#' . $m->connect_errno . ' ' . $m->connect_error;
        @mysqli_close($m);
        return $out;
    }
    @mysqli_set_charset($m, 'utf8mb4');
    @mysqli_query($m, 'SET FOREIGN_KEY_CHECKS=0');
    $scan = instScanMysqlSide($e);
    if (!$scan['ok']) { $out['err'] = $scan['err']; @mysqli_close($m); return $out; }
    foreach ($scan['tables'] as $t) {
        $q = 'DROP TABLE IF EXISTS `' . str_replace('`', '``', $t['name']) . '`';
        if (!@mysqli_query($m, $q)) {
            $out['err'] = '删除 ' . $t['name'] . ' 失败：' . mysqli_error($m);
            @mysqli_query($m, 'SET FOREIGN_KEY_CHECKS=1');
            @mysqli_close($m);
            return $out;
        }
        $out['dropped']++;
    }
    @mysqli_query($m, 'SET FOREIGN_KEY_CHECKS=1');
    @mysqli_close($m);
    $out['ok'] = true;
    return $out;
}

/**
 * 扫 db（SQLite）模式下已存在的 .db 文件里的用户表。
 * @return array{ok:bool,err:string,tables:array,files:array}
 */
function instScanSqlite($dir) {
    $out = array('ok' => false, 'err' => '', 'tables' => array(), 'files' => array());
    if (!extension_loaded('sqlite3')) { $out['err'] = 'sqlite3 扩展不可用'; return $out; }
    $abs = instResolveDir($dir === '' ? './db/' : $dir);
    if (!is_dir($abs)) { $out['ok'] = true; return $out; }   // 目录都没有 = 空
    $cands = array();
    foreach (array('web.db', 'orders.db', 'login.db', 'Java.db') as $n) {
        if (is_file($abs . '/' . $n)) $cands[] = $abs . '/' . $n;
    }
    foreach ((array)glob($abs . '/*.db') as $f) {
        if (!in_array($f, $cands, true)) $cands[] = $f;
    }
    if (empty($cands)) { $out['ok'] = true; return $out; }
    foreach ($cands as $f) {
        $db = @new SQLite3($f, SQLITE3_OPEN_READONLY);
        if (!$db) continue;
        $out['files'][] = basename($f);
        $rs = @$db->query("SELECT name FROM sqlite_master WHERE type='table'"
                        . " AND name NOT LIKE 'sqlite_%' ORDER BY name");
        if ($rs) {
            while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
                $n = (string)$row['name'];
                $cnt = -1;
                $c = @$db->querySingle('SELECT COUNT(*) FROM "' . str_replace('"', '""', $n) . '"');
                if ($c !== null && $c !== false) $cnt = (int)$c;
                $out['tables'][] = array('name' => $n, 'rows' => $cnt, 'file' => basename($f));
            }
        }
        $db->close();
    }
    $out['ok'] = true;
    return $out;
}

/** 清空 db（SQLite）模式下已存在的用户表。@return array{ok:bool,err:string,dropped:int} */
function instPurgeSqlite($dir) {
    $out = array('ok' => false, 'err' => '', 'dropped' => 0);
    if (!extension_loaded('sqlite3')) { $out['err'] = 'sqlite3 扩展不可用'; return $out; }
    $abs = instResolveDir($dir === '' ? './db/' : $dir);
    if (!is_dir($abs)) { $out['ok'] = true; return $out; }
    $cands = array();
    foreach ((array)glob($abs . '/*.db') as $f) $cands[] = $f;
    foreach ($cands as $f) {
        $db = @new SQLite3($f);
        if (!$db) continue;
        $rs = @$db->query("SELECT name FROM sqlite_master WHERE type='table'"
                        . " AND name NOT LIKE 'sqlite_%' ORDER BY name");
        $names = array();
        if ($rs) { while ($row = $rs->fetchArray(SQLITE3_ASSOC)) $names[] = (string)$row['name']; }
        foreach ($names as $n) {
            if (!@$db->exec('DROP TABLE IF EXISTS "' . str_replace('"', '""', $n) . '"')) {
                $out['err'] = '删除 ' . $n . ' 失败：' . $db->lastErrorMsg();
                $db->close();
                return $out;
            }
            $out['dropped']++;
        }
        $db->close();
    }
    $out['ok'] = true;
    return $out;
}

// ============================ 生成 sql.md 配置区 ============================
/** 把本次配置序列化成带哨兵的 json 数组（与 sql.md 模板同形）。 */
function instBuildArray(array $c) {
    $mkEntry = function ($slot, $e) {
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

// ============================ 第三关：SMTP ============================
/** 生成 6 位数字验证码 */
function instGenCode() {
    return (string)random_int(100000, 999999);
}

/**
 * 极简 SMTP 客户端（不依赖 core.php —— 初始化阶段 core.php 未必能跑）。
 * 与 core.php 的 smtpSendEmail 同一套对话流程，失败抛 Exception。
 */
function instSmtpSend($host, $port, $user, $pass, $ssl, $to, $subject, $body) {
    $errno = 0; $errstr = '';
    $target = ($ssl ? 'ssl://' : '') . $host;
    $socket = @fsockopen($target, (int)$port, $errno, $errstr, 20);
    if (!$socket) throw new Exception('SMTP 连接失败：' . $errstr . ' (' . $errno . ')');
    stream_set_timeout($socket, 20);

    $response = fgets($socket);
    if (strpos($response, '220') !== 0) { fclose($socket); throw new Exception('SMTP 服务器拒绝连接：' . trim($response)); }

    fwrite($socket, "EHLO localhost\r\n");
    fgets($socket);   // 多行响应，忽略

    fwrite($socket, "AUTH LOGIN\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) { fclose($socket); throw new Exception('SMTP 认证被拒绝：' . trim($response)); }

    fwrite($socket, base64_encode($user) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) { fclose($socket); throw new Exception('SMTP 用户名错误：' . trim($response)); }

    fwrite($socket, base64_encode($pass) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '235') !== 0) { fclose($socket); throw new Exception('SMTP 密码/授权码错误：' . trim($response)); }

    fwrite($socket, 'MAIL FROM:<' . $user . ">\r\n");
    fgets($socket);
    fwrite($socket, 'RCPT TO:<' . $to . ">\r\n");
    $r = fgets($socket);
    if (strpos($r, '250') !== 0 && strpos($r, '251') !== 0) {
        fclose($socket);
        throw new Exception('收件地址被拒：' . trim($r) . '（检查测试邮箱是否填对）');
    }
    fwrite($socket, "DATA\r\n");
    fgets($socket);

    $hdr  = 'From: ' . (defined('SMTP_SENDER_NAME') ? SMTP_SENDER_NAME : 'Sdf1_login')
          . ' <' . $user . ">\r\n";
    $hdr .= 'To: <' . $to . ">\r\n";
    $hdr .= 'Subject: ' . $subject . "\r\n";
    $hdr .= "MIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n";
    fwrite($socket, $hdr . "\r\n" . $body . "\r\n.\r\n");
    $r = fgets($socket);
    fclose($socket);
    if ($r !== false && strpos($r, '250') !== 0) {
        throw new Exception('邮件投递被拒：' . trim($r));
    }
    return true;
}

// ============================ 第四关：写 config.php 主区 define ============================
/**
 * 精确替换 config.php 主区的单行 define（SMTP_* / CAPTCHA_PROVIDER 用）。
 * 只做「一行一 define」的确定性替换：找不到、或该行不是单行 define，一律报错不动手。
 * 走 security.php 的 secReplaceConfigFile（备份到 db/config_bak/ + 原子替换）。
 *
 * @param array $kv  key => PHP 字面量文本（如 "'smtp.qq.com'" / "true" / "'off'"）
 */
function instConfigDefine(array $kv) {
    $path = __DIR__ . '/config.php';
    $src = @file_get_contents($path);
    if ($src === false) throw new Exception('读取 config.php 失败。');
    $srcLines = preg_split('/\r\n|\r|\n/', $src);
    $lines = $srcLines;

    foreach ($kv as $key => $lit) {
        if (!preg_match('/^[A-Z][A-Z0-9_]*$/', (string)$key)) {
            throw new Exception('非法配置项名：' . $key);
        }
        $hit = -1;
        for ($i = 0; $i < count($lines); $i++) {
            if (preg_match('/^\s*define\s*\(\s*\'' . $key . '\'\s*,/', $lines[$i])) { $hit = $i; break; }
        }
        if ($hit < 0) throw new Exception('config.php 里找不到 ' . $key . '，已放弃整批写入（未改动任何文件）。');
        $orig = $lines[$hit];
        // 必须是「单行 define ... );」才敢动，值里含 // 也不会误伤（只认行尾 ); 之后的注释）
        if (!preg_match('/^(\s*)define\s*\(\s*\'' . $key . '\'\s*,.*\)\s*;\s*(\/\/.*)?$/', $orig)) {
            throw new Exception($key . ' 不是单行 define，无法安全改写，已放弃整批写入。');
        }
        $indent = preg_match('/^\s*/', $orig, $mm) ? $mm[0] : '';
        $comment = preg_match('/\)\s*;\s*(\/\/.*)$/', $orig, $cm) ? ' ' . trim($cm[1]) : '';
        $lines[$hit] = $indent . "define('" . $key . "', " . $lit . ");" . $comment;
    }

    $new = implode("\n", $lines);
    if (count($lines) !== count($srcLines)) throw new Exception('config.php 替换后行数变化，已放弃。');
    foreach (array('SEC-UPDATE-BEGIN', 'SEC-UPDATE-END') as $mk) {
        if (strpos($new, $mk) === false) throw new Exception('config.php 安全标记区被破坏，已放弃。');
    }
    // 语法自检：token 化一遍确保没有截断成半个语句
    if (@token_get_all($new) === false) throw new Exception('config.php 改写后无法解析，已放弃。');

    secReplaceConfigFile($src, $new, 'inst:define=' . implode(',', array_keys($kv)));

    $back = @file_get_contents($path);
    if ($back === false) throw new Exception('写入后读不回 config.php。');
    foreach ($kv as $key => $lit) {
        if (!preg_match('/define\s*\(\s*\'' . $key . '\'\s*,\s*' . preg_quote($lit, '/') . '\s*\)/', $back)) {
            throw new Exception('config.php 回读校验失败：' . $key . ' 没写成预期值。');
        }
    }
    return true;
}

/**
 * 写 captcha_keys.php（第四关填了 CF/VA 密钥时）。
 * 保留模板的说明头，只改四个变量的值；写前备份。
 */
function instWriteCaptchaKeys($provider, $k1, $k2) {
    if ($provider === '' || $provider === 'off') return true;   // 关闭 → 不动密钥文件
    $path = __DIR__ . '/captcha_keys.php';
    $tplHead = "<?php\n"
        . "/**\n"
        . " * 人机验证码密钥 —— 由 install.php 四步向导第四关自动写入。\n"
        . " * ⚠️ 本文件已在 .gitignore 中排除，真实密钥不要提交进 git。\n"
        . " * 改完可用 captcha_test.php 自测连通性。\n"
        . " */\n\n";
    if (is_file($path)) {
        $bak = $path . '.bak.' . date('YmdHis');
        if (!@copy($path, $bak)) $bak = '';
    }
    if ($provider === 'CF') {
        $body = "// ===== Cloudflare Turnstile =====\n"
              . "\$CF_SITEKEY = '" . str_replace(array('\\', "'"), array('\\\\', "\\'"), (string)$k1) . "';\n"
              . "\$CF_SECRET   = '" . str_replace(array('\\', "'"), array('\\\\', "\\'"), (string)$k2) . "';\n"
              . "\n// ===== VAPTCHA (V4) =====\n"
              . "\$VAPTCHA_VID   = '';\n\$VAPTCHA_VKEY  = '';\n\$VAPTCHA_SCENE = 0;\n";
    } else {
        $body = "// ===== Cloudflare Turnstile =====\n"
              . "\$CF_SITEKEY = '';\n\$CF_SECRET   = '';\n"
              . "\n// ===== VAPTCHA (V4) =====\n"
              . "\$VAPTCHA_VID   = '" . str_replace(array('\\', "'"), array('\\\\', "\\'"), (string)$k1) . "';\n"
              . "\$VAPTCHA_VKEY  = '" . str_replace(array('\\', "'"), array('\\\\', "\\'"), (string)$k2) . "';\n"
              . "\$VAPTCHA_SCENE = 0;\n";
    }
    $content = $tplHead . $body;
    $tmp = $path . '.insttmp';
    if (@file_put_contents($tmp, $content, LOCK_EX) === false) {
        throw new Exception('写入 captcha_keys.php 临时文件失败（目录不可写？）');
    }
    if (!@rename($tmp, $path)) {
        @unlink($tmp);
        if (@file_put_contents($path, $content, LOCK_EX) === false) {
            throw new Exception('写入 captcha_keys.php 失败。');
        }
    }
    $back = @file_get_contents($path);
    if ($back === false || strpos($back, $body) === false) {
        throw new Exception('captcha_keys.php 回读校验失败。');
    }
    return true;
}

// ============================ 第一关回显：字段取值 ============================
/**
 * 取第二关输入框的回显值。
 * 优先级：本次 POST 出错时的原始输入 > session 暂存 > sql.md 已有配置 > 默认预填。
 */
function instDbField($k) {
    global $cur, $err;
    // 1) 本次 POST 出错 → 回显用户刚填的（不清空他填的东西）
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && $err !== '' && isset($_POST[$k])) {
        return (string)$_POST[$k];
    }
    // 2) session 暂存（上一次成功校验过的）
    if (isset($_SESSION['inst_raw2']) && is_array($_SESSION['inst_raw2'])
        && isset($_SESSION['inst_raw2'][$k])) {
        return (string)$_SESSION['inst_raw2'][$k];
    }
    // 3) sql.md 已有配置（避免覆盖已填过的值）
    $cur = is_array($cur) ? $cur : array();
    if (preg_match('/^(java|web)_(host|port|name|user|pass)$/', $k, $m)) {
        $e = (isset($cur[$m[1]]) && is_array($cur[$m[1]])) ? $cur[$m[1]] : array();
        $have = isset($e[$m[2]]) ? trim((string)$e[$m[2]]) : '';
        if ($have !== '') return $have;
        // 4) 默认预填：本机 MySQL 的常规配置 —— 地址 127.0.0.1、端口 3306
        if ($m[2] === 'host') return '127.0.0.1';
        if ($m[2] === 'port') return '3306';
        return '';
    }
    if ($k === 'dir') {
        $d = isset($cur['dir']) ? trim((string)$cur['dir']) : '';
        return ($d !== '') ? $d : '';
    }
    return '';
}

// ============================ POST 分发 ============================
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $act = isset($_POST['act']) ? (string)$_POST['act'] : '';
    $got = isset($_POST['csrf']) ? (string)$_POST['csrf'] : '';
    if (!hash_equals($csrf, $got)) {
        $err = '请求校验失败，请刷新页面后重试。';
    } else {
        try {
            switch ($act) {

            // ---------- 第一关 → 第二关 ----------
            // 双扩展全缺也不在这里拦：锁死动作属于第二关（那里有「两种模式都用不了」
            // 的红条 + 两个单选的「扩展缺失已锁定」），instValidate 再兜底防提交。
            case 'n1':
                $_SESSION['inst_step'] = 2;
                $step = 2;
                break;

            // ---------- 第二关：数据库 ----------
            case 'n2':
                // 先原样存下用户输入，出错回显用
                $_SESSION['inst_raw2'] = $_POST;
                $cfg = instValidate($_POST, $ext);
                $_SESSION['inst_d2'] = $cfg;
                unset($_SESSION['inst_purge']);

                // ★ 扫库：确认安装前先看目标库是不是空白
                $scan = instScanAll($cfg);
                if (!$scan['ok']) {
                    throw new Exception('扫描数据库状态失败：' . $scan['err']);
                }
                if ($scan['empty']) {
                    // 静默继续
                    $_SESSION['inst_step'] = 3;
                    $step = 3;
                } else {
                    // 立即刹车
                    $_SESSION['inst_purge'] = array('stage' => 'warn', 'scan' => $scan);
                    secLog('install_db_notempty', 'tables=' . count($scan['tables']));
                    $step = 2;
                }
                break;

            // ---------- 第二关：刹车 → 取消（退回重新输入数据库） ----------
            case 'pcancel':
                unset($_SESSION['inst_purge']);
                $step = 2;
                break;

            // ---------- 第二关：刹车 → 继续（展开二次确认输入框） ----------
            case 'pack':
                $p = isset($_SESSION['inst_purge']) ? $_SESSION['inst_purge'] : null;
                if (!is_array($p)) throw new Exception('状态已失效，请重新提交数据库配置。');
                $p['stage'] = 'ask';
                $_SESSION['inst_purge'] = $p;
                $step = 2;
                break;

            // ---------- 第二关：二次确认 → 真的清空 ----------
            case 'pdo':
                $p = isset($_SESSION['inst_purge']) ? $_SESSION['inst_purge'] : null;
                if (!is_array($p)) throw new Exception('状态已失效，请重新提交数据库配置。');
                $typed = isset($_POST['purge_phrase']) ? trim((string)$_POST['purge_phrase']) : '';
                if ($typed !== INST_PURGE_PHRASE) {
                    $p['stage'] = 'ask';
                    $p['bad'] = true;
                    $_SESSION['inst_purge'] = $p;
                    throw new Exception('二次确认口令不匹配 —— 必须一字不差输入「'
                        . INST_PURGE_PHRASE . '」才会清空。');
                }
                $cfg2 = isset($_SESSION['inst_d2']) ? $_SESSION['inst_d2'] : null;
                if (!is_array($cfg2)) throw new Exception('数据库配置丢失，请重新填写。');
                $pg = instPurgeAll($cfg2);
                if (!$pg['ok']) throw new Exception('清空失败：' . $pg['err']);
                unset($_SESSION['inst_purge']);
                secLog('install_db_purged', 'dropped=' . $pg['dropped'] . ' mode=' . $cfg2['mode']);
                $_SESSION['inst_step'] = 3;
                $step = 3;
                $notice = '已清空 ' . $pg['dropped'] . ' 张表，数据库现在是空白状态，进入下一关。';
                break;

            // ---------- 第三关：发送测试验证码 ----------
            case 'scode':
                $_SESSION['inst_raw3'] = $_POST;
                $s = instSmtpCollect($_POST);          // 抛异常 = 半空/非法
                if ($s['empty']) {
                    throw new Exception('SMTP 信息全空表示「不配置邮箱」—— 不用点发送，直接点「下一步」即可。');
                }
                $code = instGenCode();
                $_SESSION['inst_smtp'] = $s;
                $_SESSION['inst_code'] = $code;
                $_SESSION['inst_code_at'] = time();
                $html = instMailBody($code);
                instSmtpSend($s['host'], $s['port'], $s['user'], $s['pass'], $s['ssl'],
                             $s['to'], '【Sdf1_login】邮箱验证验证码', $html);
                $notice = '验证码已发送到 <strong>' . htmlspecialchars($s['to'], ENT_QUOTES, 'UTF-8')
                        . '</strong>，请查收后填入下方（5 分钟内有效）。没收到请检查垃圾箱。';
                secLog('install_smtp_sent', 'to=' . $s['to'] . ' host=' . $s['host']);
                break;

            // ---------- 第三关：下一步（全空直接过 / 全不空要验证码对） ----------
            case 'n3':
                $_SESSION['inst_raw3'] = $_POST;
                $s = instSmtpCollect($_POST);
                if ($s['empty']) {
                    // 全空 → 不配邮箱，直接过
                    $_SESSION['inst_d3'] = array('empty' => true);
                    unset($_SESSION['inst_smtp'], $_SESSION['inst_code'], $_SESSION['inst_code_at']);
                    $_SESSION['inst_step'] = 4;
                    $step = 4;
                    break;
                }
                // 全不空 → 必须有验证码且比对通过
                $typed = isset($_POST['code']) ? trim((string)$_POST['code']) : '';
                // 注意：这里不能复用 $saved —— 那是完成页回显整份配置用的变量
                $expectCode = (isset($_SESSION['inst_code']) && $_SESSION['inst_code'] !== '')
                            ? (string)$_SESSION['inst_code'] : '';
                $at    = isset($_SESSION['inst_code_at']) ? (int)$_SESSION['inst_code_at'] : 0;
                if ($expectCode === '') {
                    throw new Exception('请先点「发送测试验证码」，收到码后再提交。');
                }
                if (time() - $at > 300) {
                    unset($_SESSION['inst_code'], $_SESSION['inst_code_at']);
                    throw new Exception('验证码已过期（5 分钟），请重新发送。');
                }
                if (!hash_equals($expectCode, $typed)) {
                    throw new Exception('验证码不正确，请核对测试邮箱里的 6 位数字。');
                }
                $_SESSION['inst_d3'] = array('empty' => false, 'cfg' => $s);
                unset($_SESSION['inst_code'], $_SESSION['inst_code_at']);
                $_SESSION['inst_step'] = 4;
                $step = 4;
                break;

            // ---------- 第四关：确认安装（统一写盘 + 自毁） ----------
            case 'n4':
                $d2 = isset($_SESSION['inst_d2']) && is_array($_SESSION['inst_d2']) ? $_SESSION['inst_d2'] : null;
                if (!is_array($d2)) throw new Exception('数据库配置丢失，请回到第二关重新填写。');
                $d3 = (isset($_SESSION['inst_d3']) && is_array($_SESSION['inst_d3'])) ? $_SESSION['inst_d3']
                    : array('empty' => true);

                // ---- 第四关输入 ----
                $wlText = isset($_POST['whitelist']) ? (string)$_POST['whitelist'] : '';
                $items = secParseWhitelistText($wlText);
                $items = array_values(array_unique(array_filter($items, function ($v) {
                    $v = trim((string)$v);
                    return $v !== '';
                })));
                if (empty($items)) {
                    throw new Exception('IP 白名单是必填项 —— 至少要有一条，否则除了服务器本机谁也进不了后台。'
                        . '点「使用当前访问 IP」可以自动填入。');
                }
                $provider = isset($_POST['cap_provider']) ? strtolower(trim((string)$_POST['cap_provider'])) : 'off';
                if (!in_array($provider, array('off', 'cf', 'va'), true)) $provider = 'off';
                $cap1 = isset($_POST['cap_k1']) ? trim((string)$_POST['cap_k1']) : '';
                $cap2 = isset($_POST['cap_k2']) ? trim((string)$_POST['cap_k2']) : '';
                if ($provider !== 'off' && ($cap1 === '' || $cap2 === '')) {
                    throw new Exception('选择了验证码厂商就必须把两个密钥都填上；不想配就选「关闭」。');
                }
                $go2fa = !empty($_POST['go2fa']);
                $_SESSION['inst_raw4'] = $_POST;

                // ================= 统一写盘 =================
                $written = array();

                // ① config.php 主区：SMTP_*（第三关填了才写）+ CAPTCHA_PROVIDER
                $kv = array();
                $kv['CAPTCHA_PROVIDER'] = ($provider === 'off') ? "'off'"
                    : (($provider === 'cf') ? "'CF'" : "'VA'");
                if (isset($d3['empty']) && !$d3['empty'] && is_array($d3['cfg'])) {
                    $sc = $d3['cfg'];
                    $kv['SMTP_HOST'] = "'" . addslashes($sc['host']) . "'";
                    $kv['SMTP_PORT'] = "'" . (string)$sc['port'] . "'";
                    $kv['SMTP_USER'] = "'" . addslashes($sc['user']) . "'";
                    $kv['SMTP_PASS'] = "'" . addslashes($sc['pass']) . "'";
                    $kv['SMTP_USE_SSL'] = $sc['ssl'] ? 'true' : 'false';
                    $written[] = 'SMTP 已写入 config.php';
                } else {
                    $written[] = 'SMTP 未配置（保持 config.php 原值）';
                }
                instConfigDefine($kv);
                $written[] = '人机验证码 = ' . ($provider === 'off' ? '关闭' : strtoupper($provider));

                // ② captcha_keys.php（选了厂商才有）
                if ($provider !== 'off') {
                    instWriteCaptchaKeys($provider, $cap1, $cap2);
                    $written[] = '验证码密钥已写入 captcha_keys.php';
                }

                // ③ config.php SEC-UPDATE 区：IP 白名单
                secWriteConfig(array('ADMIN_IP_WHITELIST' => $items));
                $written[] = 'IP 白名单 ' . count($items) . ' 条已写入';

                // ④ sql.md 配置区
                $new = instSpliceMd(is_file(INSTALL_MD) ? @file_get_contents(INSTALL_MD) : '',
                                    instBuildArray($d2));
                $bak = '';
                if (is_file(INSTALL_MD)) {
                    $bak = INSTALL_MD . '.bak.' . date('YmdHis');
                    if (!@copy(INSTALL_MD, $bak)) $bak = '';
                }
                $tmp = INSTALL_MD . '.insttmp';
                if (@file_put_contents($tmp, $new, LOCK_EX) === false) {
                    throw new Exception('写入临时文件失败（目录不可写？）：' . $tmp);
                }
                if (!@rename($tmp, INSTALL_MD)) {
                    @unlink($tmp);
                    if (@file_put_contents(INSTALL_MD, $new, LOCK_EX) === false) {
                        throw new Exception('写入 sql.md 失败。');
                    }
                }
                $chk = instVerifyWritten($d2);
                if ($chk !== true) {
                    if ($bak !== '') @copy($bak, INSTALL_MD);
                    @unlink($tmp);
                    throw new Exception('sql.md 回读校验失败，已还原原配置。原因：' . $chk);
                }
                $written[] = '运行模式与数据库地址已写入 sql.md';

                // ⑤ 锁定 + 自毁
                @file_put_contents(INSTALL_LOCK, date('c') . "\n"
                    . 'ip=' . secClientIp() . "\n"
                    . 'mode=' . $d2['mode'] . "\n"
                    . 'md=' . INSTALL_MD . "\n", LOCK_EX);
                @chmod(INSTALL_LOCK, 0644);
                @unlink(__FILE__);

                $saved = array(
                    'db' => $d2, 'smtp' => $d3, 'whitelist' => $items,
                    'provider' => $provider, 'written' => $written, 'go2fa' => $go2fa,
                );
                $ok = ($d2['mode'] === 'mysql') ? 'MySQL 模式配置已写入' : 'db 模式配置已写入';
                $_SESSION['inst_step'] = 5;
                $step = 5;
                break;

            // ---------- 上一步 ----------
            case 'prev':
                $from = isset($_POST['from']) ? (int)$_POST['from'] : $step;
                $step = max(1, $from - 1);
                // 刹车状态只在第二关有意义
                if ($step !== 2) unset($_SESSION['inst_purge']);
                $_SESSION['inst_step'] = $step;
                break;

            default:
                throw new Exception('未知操作：' . $act);
            }
        } catch (Exception $e) {
            $err = $e->getMessage();
            $_SESSION['inst_step'] = $step;   // 出错留在当前关
        }
    }
}

/** 汇总第二关配置的所有侧，扫一遍数据库状态。 */
function instScanAll(array $cfg) {
    $out = array('ok' => false, 'err' => '', 'empty' => true, 'tables' => array(), 'mode' => $cfg['mode']);
    if ($cfg['mode'] === 'mysql') {
        $any = false;
        foreach (array('java', 'web') as $slot) {
            $e = $cfg[$slot];
            if ($e['host'] === '' && $e['name'] === '') continue;   // 该侧回退 db
            $any = true;
            $r = instScanMysqlSide($e);
            if (!$r['ok']) { $out['err'] = strtoupper($slot) . ' 侧：' . $r['err']; return $out; }
            foreach ($r['tables'] as $t) {
                $t['slot'] = $slot;
                $t['db'] = $e['name'];
                $out['tables'][] = $t;
            }
        }
        if (!$any) { $out['err'] = 'mysql 模式下没有任何一侧地址生效'; return $out; }
    } else {
        $r = instScanSqlite($cfg['dir']);
        if (!$r['ok']) { $out['err'] = $r['err']; return $out; }
        foreach ($r['tables'] as $t) $out['tables'][] = $t;
    }
    $out['ok'] = true;
    $out['empty'] = empty($out['tables']);
    return $out;
}

/** 按第二关配置清空全部侧。 */
function instPurgeAll(array $cfg) {
    $out = array('ok' => false, 'err' => '', 'dropped' => 0);
    if ($cfg['mode'] === 'mysql') {
        foreach (array('java', 'web') as $slot) {
            $e = $cfg[$slot];
            if ($e['host'] === '' && $e['name'] === '') continue;
            $r = instPurgeMysqlSide($e);
            if (!$r['ok']) { $out['err'] = strtoupper($slot) . ' 侧：' . $r['err']; return $out; }
            $out['dropped'] += $r['dropped'];
        }
    } else {
        $r = instPurgeSqlite($cfg['dir']);
        if (!$r['ok']) { $out['err'] = $r['err']; return $out; }
        $out['dropped'] += $r['dropped'];
    }
    $out['ok'] = true;
    return $out;
}

/**
 * 收集第三关 SMTP 输入。全空返回 empty=true；半空抛异常；全不空返回配置。
 */
function instSmtpCollect(array $post) {
    $g = function ($k) use ($post) {
        return isset($post[$k]) ? trim((string)$post[$k]) : '';
    };
    $host = $g('smtp_host');
    $user = $g('smtp_user');
    $pass = isset($post['smtp_pass']) ? trim((string)$post['smtp_pass']) : '';
    $to   = $g('smtp_to');
    $port = $g('smtp_port');
    $ssl  = !isset($post['smtp_ssl']) || $post['smtp_ssl'] !== '0';

    $vals = array('SMTP 服务器' => $host, '用户名/发信账号' => $user,
                  '密码（授权码）' => $pass, '测试邮箱' => $to);
    $filled = array();
    $blank  = array();
    foreach ($vals as $label => $v) {
        if ($v === '') $blank[] = $label; else $filled[] = $label;
    }
    if (empty($filled)) {
        return array('empty' => true);              // 全空 = 不配邮箱
    }
    if (!empty($blank)) {
        throw new Exception('SMTP 要么全填、要么全空。当前还缺：' . implode('、', $blank)
            . '。<br><span class="muted">不想配置邮箱（找回密码、订单通知将发不出去）就把上面四项全部清空，'
            . '再点「下一步」。</span>');
    }
    if ($port === '') $port = '465';
    if (!ctype_digit($port)) throw new Exception('SMTP 端口必须是纯数字。');
    $p = (int)$port;
    if ($p < 1 || $p > 65535) throw new Exception('SMTP 端口超出范围（1-65535）。');
    if (strpos($to, '@') === false) throw new Exception('测试邮箱格式不正确。');
    return array('empty' => false, 'host' => $host, 'port' => $p, 'user' => $user,
                 'pass' => $pass, 'to' => $to, 'ssl' => $ssl);
}

/** 测试邮件正文。 */
function instMailBody($code) {
    return "<div style=\"font:14px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;"
         . "color:#1f2328;max-width:520px\">"
         . "<h2 style=\"margin:0 0 12px;font-size:18px\">邮箱验证验证码</h2>"
         . "<p style=\"margin:0 0 14px;color:#57606a\">你正在配置 Sdf1_login 站点的 SMTP 邮箱。"
         . "下面这串验证码用于确认这个邮箱确实能发信：</p>"
         . "<div style=\"font-size:32px;letter-spacing:8px;font-weight:700;color:#1a7f37;"
         . "background:#f6f8fa;border:1px solid #d0d7de;border-radius:8px;padding:14px 18px;text-align:center\">"
         . htmlspecialchars($code, ENT_QUOTES, 'UTF-8') . "</div>"
         . "<p style=\"margin:14px 0 0;color:#8b949e;font-size:12.5px\">验证码 5 分钟内有效。"
         . "不是你本人操作请忽略本邮件。</p></div>";
}

// ============================ 渲染 ============================
// 第二关的刹车状态
$purge = (isset($_SESSION['inst_purge']) && is_array($_SESSION['inst_purge'])) ? $_SESSION['inst_purge'] : null;
$purgeStage = is_array($purge) && isset($purge['stage']) ? $purge['stage'] : '';

// 第三关回显源
$raw3 = (isset($_SESSION['inst_raw3']) && is_array($_SESSION['inst_raw3'])) ? $_SESSION['inst_raw3'] : array();
$smtpSent = (isset($_SESSION['inst_code']) && $_SESSION['inst_code'] !== '');

// 第四关回显源
$raw4 = (isset($_SESSION['inst_raw4']) && is_array($_SESSION['inst_raw4'])) ? $_SESSION['inst_raw4'] : array();
$myIp = secClientIp();
$curProvider = 'off';
if (isset($raw4['cap_provider'])) {
    $curProvider = strtolower(trim((string)$raw4['cap_provider']));
} elseif (defined('CAPTCHA_PROVIDER')) {
    $v = strtolower(trim((string)CAPTCHA_PROVIDER));
    $curProvider = in_array($v, array('va', 'v', 'vaptcha'), true) ? 'va'
                 : (($v === '' || $v === 'off' || $v === 'none' || $v === 'false' || $v === '0') ? 'off' : 'cf');
}
$curWl = '';
if (isset($raw4['whitelist'])) {
    $curWl = (string)$raw4['whitelist'];
} elseif (defined('ADMIN_IP_WHITELIST') && is_array(ADMIN_IP_WHITELIST) && !empty(ADMIN_IP_WHITELIST)) {
    $curWl = implode("\n", ADMIN_IP_WHITELIST);
} else {
    $curWl = $myIp;   // 未配置时默认预填当前访问 IP
}
$curK1 = isset($raw4['cap_k1']) ? (string)$raw4['cap_k1'] : '';
$curK2 = isset($raw4['cap_k2']) ? (string)$raw4['cap_k2'] : '';

$modeNow = is_array($cur) && isset($cur['mode']) ? $cur['mode'] : 'db';
$modeSel = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST' && $err !== '' && isset($_POST['mode'])) {
    $modeSel = strtolower(trim((string)$_POST['mode']));
} elseif (isset($_SESSION['inst_raw2']['mode'])) {
    $modeSel = strtolower(trim((string)$_SESSION['inst_raw2']['mode']));
} elseif ($modeNow !== '') {
    $modeSel = $modeNow;
}
// 第一关锁死后强制回到可用模式
if ($modeSel === 'db' && empty($ext['any_db']))    $modeSel = $ext['any_mysql'] ? 'mysql' : 'db';
if ($modeSel === 'mysql' && empty($ext['any_mysql'])) $modeSel = $ext['any_db'] ? 'db' : 'mysql';
if ($modeSel !== 'db' && $modeSel !== 'mysql') $modeSel = 'db';

$f = function ($k) { return htmlspecialchars(instDbField($k), ENT_QUOTES, 'UTF-8'); };
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>安装向导 · 初始化</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0f1115;color:#e6e8ee;font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
.wrap{max-width:820px;margin:32px auto;padding:0 16px}
.card{background:#171a21;border:1px solid #262a33;border-radius:10px;padding:22px 24px;margin-bottom:18px}
h1{font-size:20px;margin:0 0 6px}
.sub{color:#98a0ad;font-size:13px;margin:0 0 18px}
h2{font-size:15px;margin:22px 0 10px;color:#cdd3dd}
label{display:block;font-size:13px;color:#aab2bf;margin:12px 0 5px}
input[type=text],input[type=password],input[type=email],select,textarea{width:100%;padding:9px 11px;background:#0f1115;
  border:1px solid #2e333d;border-radius:6px;color:#e6e8ee;font-size:14px;outline:none;font-family:inherit}
input:focus,select:focus,textarea:focus{border-color:#4c8dff}
textarea{min-height:96px;resize:vertical}
.row{display:flex;gap:12px}
.row>div{flex:1}
.modes{display:flex;gap:10px;margin-top:6px}
.modes label{flex:1;margin:0;padding:11px;border:1px solid #2e333d;border-radius:6px;cursor:pointer;
  background:#12151b;text-align:center;color:#c3cad4}
.modes input{display:none}
.modes label.on{border-color:#4c8dff;background:#16243b;color:#fff}
.modes label.dis{opacity:.42;cursor:not-allowed}
.btn{display:inline-block;padding:11px 22px;background:#4c8dff;color:#fff;border:0;border-radius:6px;
  font-size:14px;cursor:pointer;margin-top:20px}
.btn:hover{background:#3f7ce8}
.btn.gr{background:#238636}.btn.gr:hover{background:#2ea043}
.btn.gh{background:#21262d;border:1px solid #343a45;color:#c9d1d9}
.btn.gh:hover{background:#2a3038}
.btn.rd{background:#b62324}.btn.rd:hover{background:#cf3132}
.btn.sm{padding:7px 14px;font-size:13px;margin-top:0}
.acts{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.note{padding:11px 14px;border-radius:6px;font-size:13.5px;margin:14px 0}
.err{background:#2a1519;border:1px solid #5a2730;color:#ffb4bc}
.ok{background:#12261a;border:1px solid #26543a;color:#9ff0bb}
.info{background:#141b2a;border:1px solid #26314a;color:#a8c1f0}
.warn{background:#2b1f10;border:1px solid #6b4a17;color:#ffd9a0}
.code{background:#0d0f14;padding:2px 6px;border-radius:4px;font-size:13px;color:#e6b673}
ul{margin:6px 0;padding-left:20px}
.muted{color:#7d8794;font-size:12.5px}
.kbd{background:#22262e;border:1px solid #343a45;padding:2px 7px;border-radius:4px;font-size:12.5px}
.steps{display:flex;gap:8px;margin:0 0 18px}
.step{flex:1;padding:9px 4px;text-align:center;border-radius:6px;background:#12151b;border:1px solid #262a33;
  color:#7d8794;font-size:12.5px;line-height:1.35}
.step b{display:block;font-size:13px;color:#98a0ad}
.step.on{border-color:#4c8dff;background:#16243b}
.step.on b{color:#fff}
.step.done{border-color:#26543a;background:#12261a}
.step.done b{color:#9ff0bb}
.ext{width:100%;border-collapse:collapse;font-size:13.5px;margin-top:6px}
.ext td{padding:9px 10px;border-bottom:1px solid #232732}
.ext tr:last-child td{border-bottom:0}
.pill{display:inline-block;padding:1px 9px;border-radius:999px;font-size:12px}
.pill.y{background:#1a7f37;color:#fff}.pill.n{background:#da3633;color:#fff}
.tbl{width:100%;border-collapse:collapse;font-size:13px;margin:8px 0}
.tbl th,.tbl td{padding:6px 9px;border-bottom:1px solid #232732;text-align:left}
.tbl th{color:#98a0ad;font-weight:600;font-size:12.5px}
.chk{display:flex;gap:8px;align-items:center;margin-top:8px;color:#aab2bf;font-size:13px}
.chk input{width:auto}
</style>
</head>
<body>
<div class="wrap">

<div class="card">
  <h1>安装向导</h1>
  <p class="sub">四步完成站点初始化：运行环境 → 数据库 → 邮箱 → 安全。全部写入成功后本页会
     <strong>自动删除</strong>并生成 <code>install.lock</code> 锁定。配置好前请勿关闭页面。</p>

  <div class="steps" data-step="<?php echo (int)$step; ?>">
    <?php
    $titles = array(1 => array('第一关', '运行环境'), 2 => array('第二关', '数据库'),
                    3 => array('第三关', '邮箱'),   4 => array('第四关', '安全'));
    foreach ($titles as $n => $t):
        $cls = ($step === $n) ? 'on' : (($step > $n || $step === 5) ? 'done' : '');
    ?>
    <div class="step <?php echo $cls; ?>"><b><?php echo $t[0]; ?></b><?php echo $t[1]; ?></div>
    <?php endforeach; ?>
  </div>

  <?php if ($ok !== '' && $step === 5): ?>
    <div class="note ok">
      <strong><?php echo htmlspecialchars($ok, ENT_QUOTES, 'UTF-8'); ?></strong>
      <ul>
        <li>配置已写入并逐项回读校验通过。</li>
        <li>已生成 <code>install.lock</code>，本页再次访问将返回 404。</li>
        <li><code>install.php</code> 已自我删除（后台静默完成，<strong>页面照常停留供你截图留档，请勿刷新</strong>）。</li>
      </ul>

      <?php if (is_array($saved)): ?>
      <div style="margin-top:10px;border-top:1px solid #26543a;padding-top:10px">
        <div style="font-weight:600;margin:6px 0">📋 本次写入的配置 —— 请截图或手抄留档：</div>
        <table style="width:100%;border-collapse:collapse;font-size:13px">
          <tr>
            <td style="padding:4px 8px;color:#9ff0bb;white-space:nowrap;vertical-align:top">运行模式</td>
            <td><code><?php echo htmlspecialchars($saved['db']['mode'] === 'mysql' ? 'mysql' : 'db（SQLite 文件）', ENT_QUOTES, 'UTF-8'); ?></code></td>
          </tr>
          <?php if ($saved['db']['mode'] !== 'mysql'): ?>
          <tr>
            <td style="padding:4px 8px;color:#9ff0bb;white-space:nowrap;vertical-align:top">存储位置</td>
            <td><code><?php echo htmlspecialchars($saved['db']['dir'] === '' ? './db/（默认）' : $saved['db']['dir'], ENT_QUOTES, 'UTF-8'); ?></code></td>
          </tr>
          <?php else: ?>
            <?php foreach (array('java' => 'Java 侧（sync.php · Java.db）', 'web' => 'PHP 侧（web.db）') as $slot => $label): ?>
            <?php $se = $saved['db'][$slot]; ?>
            <tr>
              <td style="padding:4px 8px;color:#9ff0bb;vertical-align:top;white-space:nowrap"><?php echo $label; ?></td>
              <td><?php if ($se['host'] === '' && $se['name'] === ''): ?><code>（空 → 自动回退 db 文件）</code><?php else: ?><code><?php echo htmlspecialchars($se['host'] . ':' . $se['port'] . ' · 库名 ' . $se['name'] . ' · 账号 ' . $se['user'] . ' · 密码 ' . $se['pass'], ENT_QUOTES, 'UTF-8'); ?></code><?php endif; ?></td>
            </tr>
            <?php endforeach; ?>
          <?php endif; ?>
          <tr>
            <td style="padding:4px 8px;color:#9ff0bb;white-space:nowrap;vertical-align:top">邮箱 SMTP</td>
            <td><?php if (!empty($saved['smtp']['empty'])): ?><code>未配置（找回密码、订单通知发不出去）</code><?php else: ?><code><?php echo htmlspecialchars($saved['smtp']['cfg']['host'] . ':' . $saved['smtp']['cfg']['port'] . ' · 账号 ' . $saved['smtp']['cfg']['user'], ENT_QUOTES, 'UTF-8'); ?></code><?php endif; ?></td>
          </tr>
          <tr>
            <td style="padding:4px 8px;color:#9ff0bb;white-space:nowrap;vertical-align:top">IP 白名单</td>
            <td><code><?php echo htmlspecialchars(implode('、', $saved['whitelist']), ENT_QUOTES, 'UTF-8'); ?></code></td>
          </tr>
          <tr>
            <td style="padding:4px 8px;color:#9ff0bb;white-space:nowrap;vertical-align:top">人机验证码</td>
            <td><code><?php echo htmlspecialchars($saved['provider'] === 'off' ? '关闭' : strtoupper($saved['provider']), ENT_QUOTES, 'UTF-8'); ?></code></td>
          </tr>
        </table>
        <div style="color:#9ff0bb;font-size:12.5px;margin-top:6px">本页已随安装完成而删除 —— 这是最后一处能整份查看该配置的页面；完整配置仍保存在 <code>sql.md</code> 与 <code>config.php</code>。</div>
      </div>

      <div style="margin-top:12px;border-top:1px solid #26543a;padding-top:10px">
        <div style="font-weight:600;margin:0 0 6px">写入明细：</div>
        <ul><?php foreach ($saved['written'] as $w): ?><li><?php echo htmlspecialchars($w, ENT_QUOTES, 'UTF-8'); ?></li><?php endforeach; ?></ul>
      </div>
      <?php endif; ?>

      下一步：
      <ul>
        <?php if (!empty($saved['go2fa'])): ?>
        <li><strong>正在跳转到 <a href="admin_2fa_setup.php" style="color:#7fb0ff">安全配置引导页</a></strong>：配置 2FA 验证器与访问令牌。</li>
        <?php else: ?>
        <li>2FA 二次验证<strong>尚未配置</strong> —— 建议尽快去 <a href="admin_2fa_setup.php" style="color:#7fb0ff">安全配置引导页</a> 配好（不配则后台只靠 IP 白名单 + 访问令牌把关）。</li>
        <?php endif; ?>
        <li>mysql 模式 → 首次进后台会自动建表（已在第二关实连并验证过建表权限）；db 模式 → 配完即可登录。</li>
        <li>记得让 <strong>Java 端</strong>（api/sync.php 所在的独立站点）也用同一份配置：改 sync.php 时两处都要传。</li>
      </ul>
      <?php if (!empty($saved['go2fa'])): ?>
      <script>setTimeout(function(){location.href='admin_2fa_setup.php';},1500);</script>
      <?php endif; ?>
    </div>
  <?php endif; ?>

  <?php if ($err !== ''): ?>
    <div class="note err"><strong>本步未通过：</strong><?php echo $err; ?></div>
  <?php endif; ?>
  <?php if ($notice !== ''): ?>
    <div class="note info"><?php echo $notice; ?></div>
  <?php endif; ?>

  <?php if ($step === 5): ?>
    <!-- 完成态：只剩上面的成功卡，不再渲染表单 -->
    <p class="muted" style="border-top:1px solid #232732;padding-top:14px">
      安装已完成。如果需要查看配置，请打开 <code>sql.md</code>（数据库）与 <code>config.php</code>（邮箱、验证码、白名单）。
    </p>

  <?php elseif ($step === 1): ?>
  <!-- ==================== 第一关：运行环境 ==================== -->
  <form method="post" action="install.php" autocomplete="off">
    <input type="hidden" name="act" value="n1">
    <input type="hidden" name="csrf" value="<?php echo htmlspecialchars($csrf, ENT_QUOTES, 'UTF-8'); ?>">
    <?php if (secToken() !== ''): ?>
    <input type="hidden" name="token" value="<?php echo htmlspecialchars(secToken(), ENT_QUOTES, 'UTF-8'); ?>">
    <?php endif; ?>

    <h2>第一关 · 运行环境检测</h2>
    <p class="muted">先确认 PHP 扩展齐全。缺扩展在这里只是「选项灰掉」，不会把底层原始报错甩到你脸上。</p>

    <table class="ext">
      <tr>
        <td style="width:130px"><code>sqlite3</code></td>
        <td style="width:96px"><span class="pill <?php echo $ext['sqlite3'] ? 'y">已安装' : 'n">未安装'; ?></span></td>
        <td class="muted">db（SQLite 文件）模式依赖它</td>
      </tr>
      <tr>
        <td><code>pdo_sqlite</code></td>
        <td><span class="pill <?php echo $ext['pdo_sqlite'] ? 'y">已安装' : 'n">未安装'; ?></span></td>
        <td class="muted">正版验证接口依赖它（缺失只影响该功能，不影响安装）</td>
      </tr>
      <tr>
        <td><code>mysqli</code></td>
        <td><span class="pill <?php echo $ext['mysqli'] ? 'y">已安装' : 'n">未安装'; ?></span></td>
        <td class="muted">mysql 模式依赖它</td>
      </tr>
    </table>

    <?php if (!$ext['any_db'] && !$ext['any_mysql']): ?>
      <div class="note err"><strong>两种模式都用不了：</strong>当前 PHP 既没有 <code>sqlite3</code> 也没有 <code>mysqli</code>。
        请先在宝塔面板 → PHP 设置 → 安装扩展里装好其中之一，再刷新本页。</div>
    <?php elseif (!$ext['any_db']): ?>
      <div class="note warn">检测到缺少 <code>sqlite3</code> → 下一关的 <strong>db 模式会被锁定</strong>，只能用 mysql。</div>
    <?php elseif (!$ext['any_mysql']): ?>
      <div class="note warn">检测到缺少 <code>mysqli</code> → 下一关的 <strong>mysql 模式会被锁定</strong>，只能用 db。</div>
    <?php else: ?>
      <div class="note ok">扩展齐全，db 与 mysql 两种模式都可以选。</div>
    <?php endif; ?>

    <div class="acts"><button class="btn" type="submit">下一步：配置数据库</button></div>
  </form>

  <?php elseif ($step === 2): ?>
  <!-- ==================== 第二关：数据库 ==================== -->
  <form method="post" action="install.php" autocomplete="off">
    <input type="hidden" name="csrf" value="<?php echo htmlspecialchars($csrf, ENT_QUOTES, 'UTF-8'); ?>">
    <?php if (secToken() !== ''): ?>
    <input type="hidden" name="token" value="<?php echo htmlspecialchars(secToken(), ENT_QUOTES, 'UTF-8'); ?>">
    <?php endif; ?>

    <h2>第二关 · 数据库</h2>

    <?php if ($purgeStage === 'warn' || $purgeStage === 'ask'): ?>
    <!-- ===== 非空刹车 ===== -->
    <div class="note warn">
      <strong>⚠️ 目标数据库不是空白的，已暂停安装。</strong>
      <div class="muted" style="margin-top:4px">继续下去会把下面这些表当作已有数据处理，站点初始化可能报错或串数据。
        先确认它们是不是你需要的。</div>
      <table class="tbl">
        <tr><th>库 / 文件</th><th>表</th><th style="width:110px">行数（约）</th></tr>
        <?php foreach ($purge['scan']['tables'] as $t): ?>
        <tr>
          <td><code><?php echo htmlspecialchars(isset($t['db']) ? $t['db'] : (isset($t['file']) ? $t['file'] : '-'), ENT_QUOTES, 'UTF-8'); ?></code></td>
          <td><code><?php echo htmlspecialchars($t['name'], ENT_QUOTES, 'UTF-8'); ?></code></td>
          <td><?php echo ((int)$t['rows'] < 0) ? '—' : number_format((int)$t['rows']); ?></td>
        </tr>
        <?php endforeach; ?>
      </table>
      <?php if ($purgeStage === 'ask'): ?>
        <div style="border-top:1px solid #6b4a17;margin-top:8px;padding-top:8px">
          <strong>二次确认：</strong>要清空上述全部表，请在下面一字不差输入
          <span class="kbd"><?php echo INST_PURGE_PHRASE; ?></span>
          <input type="text" name="purge_phrase" value="" placeholder="<?php echo INST_PURGE_PHRASE; ?>"
                 style="margin-top:6px" autocomplete="off">
          <?php if (!empty($purge['bad'])): ?>
            <div class="muted" style="color:#ffb4bc;margin-top:4px">口令不对，未执行任何删除。</div>
          <?php endif; ?>
        </div>
      <?php endif; ?>
    </div>

    <div class="acts">
      <?php if ($purgeStage === 'warn'): ?>
        <button class="btn rd" type="submit" name="act" value="pack">确认要清空，继续</button>
        <button class="btn gh" type="submit" name="act" value="pcancel">取消，回去重新输入数据库</button>
      <?php else: ?>
        <button class="btn rd" type="submit" name="act" value="pdo">输入无误，清空并继续</button>
        <button class="btn gh" type="submit" name="act" value="pcancel">取消，回去重新输入数据库</button>
      <?php endif; ?>
    </div>
    </div>
    <?php else: ?>

    <div class="modes">
      <label class="<?php echo ($modeSel === 'db' ? 'on ' : '') . ($ext['any_db'] ? '' : 'dis'); ?>">
        <input type="radio" name="mode" value="db" <?php echo $ext['any_db'] ? 'onchange="mSwitch(this)"' : 'disabled'; ?>
          <?php echo $modeSel === 'db' ? 'checked' : ''; ?>>
        db（SQLite 文件，默认）<?php echo $ext['any_db'] ? '' : ' · 扩展缺失已锁定'; ?></label>
      <label class="<?php echo ($modeSel === 'mysql' ? 'on ' : '') . ($ext['any_mysql'] ? '' : 'dis'); ?>">
        <input type="radio" name="mode" value="mysql" <?php echo $ext['any_mysql'] ? 'onchange="mSwitch(this)"' : 'disabled'; ?>
          <?php echo $modeSel === 'mysql' ? 'checked' : ''; ?>>
        mysql<?php echo $ext['any_mysql'] ? '' : ' · 扩展缺失已锁定'; ?></label>
    </div>

    <div id="dirBox">
      <label>数据库存储位置 <span class="muted">（仅 db 模式生效；留空 = 默认 <code>./db/</code>）</span></label>
      <input type="text" name="dir" value="<?php echo $f('dir'); ?>" placeholder="./db/">
      <p class="muted">/ 开头=绝对路径；./ 开头=相对本目录；../ 开头=相对上层目录。</p>
    </div>

    <div id="mysqlBox">
      <div class="note info">地址与端口已按<strong>本机 MySQL 的常规配置</strong>预填（<code>127.0.0.1:3306</code>）——
        用本机 MySQL 的话，你只需要填账号、密码、库名。<br>
        每侧地址<strong>要么填全、要么全空</strong>；留空的一侧会自动回退 db 文件。
        本页会真实连接并尝试建表，失败会直接报错，不会悄悄写入一份跑不通的配置。</div>

      <h2>Java 侧（api/sync.php 通信组 · Java.db）</h2>
      <div class="row">
        <div><label>连接地址</label><input type="text" name="java_host" value="<?php echo $f('java_host'); ?>" placeholder="127.0.0.1"></div>
        <div><label>端口</label><input type="text" name="java_port" value="<?php echo $f('java_port'); ?>" placeholder="3306"></div>
      </div>
      <div class="row">
        <div><label>数据库名</label><input type="text" name="java_name" value="<?php echo $f('java_name'); ?>" placeholder="game_java"></div>
        <div><label>账号</label><input type="text" name="java_user" value="<?php echo $f('java_user'); ?>" autocomplete="off"></div>
      </div>
      <label>密码</label>
      <input type="password" name="java_pass" value="<?php echo $f('java_pass'); ?>" autocomplete="new-password">

      <h2>PHP 侧（web.db）</h2>
      <div class="row">
        <div><label>连接地址</label><input type="text" name="web_host" value="<?php echo $f('web_host'); ?>" placeholder="127.0.0.1"></div>
        <div><label>端口</label><input type="text" name="web_port" value="<?php echo $f('web_port'); ?>" placeholder="3306"></div>
      </div>
      <div class="row">
        <div><label>数据库名</label><input type="text" name="web_name" value="<?php echo $f('web_name'); ?>" placeholder="game_web"></div>
        <div><label>账号</label><input type="text" name="web_user" value="<?php echo $f('web_user'); ?>" autocomplete="off"></div>
      </div>
      <label>密码</label>
      <input type="password" name="web_pass" value="<?php echo $f('web_pass'); ?>" autocomplete="new-password">
    </div>

    <div class="acts">
      <button class="btn" type="submit" name="act" value="n2">下一步：检查数据库状态</button>
      <button class="btn gh" type="submit" name="act" value="prev">上一步</button>
      <input type="hidden" name="from" value="2">
    </div>
    <?php endif; ?>
  </form>

  <?php elseif ($step === 3): ?>
  <!-- ==================== 第三关：邮箱 ==================== -->
  <form method="post" action="install.php" autocomplete="off">
    <input type="hidden" name="csrf" value="<?php echo htmlspecialchars($csrf, ENT_QUOTES, 'UTF-8'); ?>">
    <?php if (secToken() !== ''): ?>
    <input type="hidden" name="token" value="<?php echo htmlspecialchars(secToken(), ENT_QUOTES, 'UTF-8'); ?>">
    <?php endif; ?>

    <h2>第三关 · 邮箱（SMTP）</h2>
    <div class="note info"><strong>要么全填，要么全空。</strong><br>
      全空 → 直接进最后一关（站点不能发邮件：找回密码、订单通知都发不出去）。<br>
      全不空 → 必须收一个测试验证码，确认这个邮箱真的能发信，才放你进下一关。</div>

    <div class="row">
      <div style="flex:2"><label>SMTP 服务器</label>
        <input type="text" name="smtp_host" value="<?php echo htmlspecialchars(isset($raw3['smtp_host']) ? $raw3['smtp_host'] : '', ENT_QUOTES, 'UTF-8'); ?>" placeholder="smtp.qq.com"></div>
      <div><label>端口</label>
        <input type="text" name="smtp_port" value="<?php echo htmlspecialchars(isset($raw3['smtp_port']) ? $raw3['smtp_port'] : '465', ENT_QUOTES, 'UTF-8'); ?>" placeholder="465"></div>
    </div>
    <label>用户名 / 发信账号</label>
    <input type="text" name="smtp_user" value="<?php echo htmlspecialchars(isset($raw3['smtp_user']) ? $raw3['smtp_user'] : '', ENT_QUOTES, 'UTF-8'); ?>" placeholder="you@example.com" autocomplete="off">
    <label>密码 / 授权码 <span class="muted">（不是登录密码，是邮箱后台生成的授权码）</span></label>
    <input type="password" name="smtp_pass" value="<?php echo htmlspecialchars(isset($raw3['smtp_pass']) ? $raw3['smtp_pass'] : '', ENT_QUOTES, 'UTF-8'); ?>" autocomplete="new-password">
    <div class="chk">
      <input type="checkbox" id="sslBox" name="smtp_ssl" value="1"
        <?php echo (!isset($raw3['smtp_ssl']) || $raw3['smtp_ssl'] === '1') ? 'checked' : ''; ?>>
      <label for="sslBox" style="margin:0">使用 SSL（465 端口勾选，25/587 明文端口取消勾选）</label>
    </div>

    <label>测试邮箱 <span class="muted">（验证码发到这里，仅用于本次测试，不写入配置）</span></label>
    <input type="email" name="smtp_to" value="<?php echo htmlspecialchars(isset($raw3['smtp_to']) ? $raw3['smtp_to'] : '', ENT_QUOTES, 'UTF-8'); ?>" placeholder="you@example.com" autocomplete="off">

    <label>验证码</label>
    <div class="row">
      <div style="flex:2"><input type="text" name="code" value="" placeholder="<?php echo $smtpSent ? '6 位数字，已发送' : '先点右侧发送'; ?>" autocomplete="one-time-code" inputmode="numeric" maxlength="6"></div>
      <div style="flex:0 0 auto;display:flex;align-items:center">
        <button class="btn gh sm" type="submit" name="act" value="scode">发送测试验证码</button>
      </div>
    </div>
    <?php if ($smtpSent): ?>
      <p class="muted" style="margin-top:6px">验证码已发出，5 分钟内有效；没收到请检查垃圾箱或确认授权码。</p>
    <?php endif; ?>

    <div class="acts">
      <button class="btn" type="submit" name="act" value="n3">下一步</button>
      <button class="btn gh" type="submit" name="act" value="prev">上一步</button>
      <input type="hidden" name="from" value="3">
    </div>
    <p class="muted" style="margin-top:10px">不想配置邮箱？把上面四个字段全部清空，直接点「下一步」。</p>
  </form>

  <?php elseif ($step === 4): ?>
  <!-- ==================== 第四关：安全 ==================== -->
  <form method="post" action="install.php" autocomplete="off">
    <input type="hidden" name="csrf" value="<?php echo htmlspecialchars($csrf, ENT_QUOTES, 'UTF-8'); ?>">
    <?php if (secToken() !== ''): ?>
    <input type="hidden" name="token" value="<?php echo htmlspecialchars(secToken(), ENT_QUOTES, 'UTF-8'); ?>">
    <?php endif; ?>

    <h2>第四关 · 安全</h2>

    <label>IP 白名单 <span style="color:#ffb4bc">★ 必填</span> <span class="muted">（一行一个 IP 或 CIDR）</span></label>
    <textarea name="whitelist" id="wlBox" placeholder="一行一个 IP 或 CIDR 网段，例如：&#10;203.0.113.8&#10;192.168.1.0/24"><?php echo htmlspecialchars($curWl, ENT_QUOTES, 'UTF-8'); ?></textarea>
    <div class="acts" style="margin-top:8px">
      <button class="btn gh sm" type="button" onclick="fillIp()">使用当前访问 IP（<?php echo htmlspecialchars($myIp, ENT_QUOTES, 'UTF-8'); ?>）</button>
    </div>
    <p class="muted">当前访问 IP：<span class="kbd"><?php echo htmlspecialchars($myIp, ENT_QUOTES, 'UTF-8'); ?></span>
      —— 它必须在白名单里，否则保存后你自己也进不了后台（除了服务器本机 127.0.0.1）。</p>

    <h2>人机验证码 <span class="muted" style="font-weight:400">（选填，留空即关闭）</span></h2>
    <label>使用哪家</label>
    <select name="cap_provider" onchange="capSwitch(this)">
      <option value="off" <?php echo $curProvider === 'off' ? 'selected' : ''; ?>>关闭（不启用人机验证）</option>
      <option value="cf" <?php echo $curProvider === 'cf' ? 'selected' : ''; ?>>Cloudflare Turnstile</option>
      <option value="va" <?php echo $curProvider === 'va' ? 'selected' : ''; ?>>VAPTCHA V4</option>
    </select>

    <div id="capBox" style="<?php echo $curProvider === 'off' ? 'display:none' : ''; ?>">
      <div class="row">
        <div><label id="capL1">Sitekey / VID</label>
          <input type="text" name="cap_k1" id="capK1" value="<?php echo htmlspecialchars($curK1, ENT_QUOTES, 'UTF-8'); ?>" autocomplete="off"></div>
        <div><label id="capL2">Secret / VKey</label>
          <input type="text" name="cap_k2" id="capK2" value="<?php echo htmlspecialchars($curK2, ENT_QUOTES, 'UTF-8'); ?>" autocomplete="off"></div>
      </div>
      <p class="muted">CF：控制台 → Workers &amp; Pages → Turnstile → Add site，拿 Sitekey 与 Secret。<br>
        VA：VAPTCHA 控制台 → 创建应用，拿 VID 与 VKey。</p>
    </div>

    <h2>2FA 二次验证 <span class="muted" style="font-weight:400">（选填）</span></h2>
    <div class="note info">2FA 验证器（Google Authenticator / 1Password / Microsoft Authenticator）
      在<strong>配置完成后的安全引导页</strong>里设置 —— 那里会生成密钥、显示二维码并要求你输一次动态码确认。<br>
      下面两个按钮都会在安装完成后跳过去；选「稍后再配」则后台只靠 IP 白名单 + 访问令牌把关。</div>

    <div class="acts">
      <button class="btn gr" type="submit" name="act" value="n4">完成安装，去配置 2FA</button>
      <input type="hidden" name="go2fa" id="go2fa" value="1">
      <button class="btn gh" type="button" onclick="document.getElementById('go2fa').value='0';
        this.form.querySelector('[name=act]').value='n4'; this.form.submit();">完成安装，稍后再配 2FA</button>
      <button class="btn gh" type="submit" name="act" value="prev">上一步</button>
      <input type="hidden" name="from" value="4">
    </div>
    <p class="muted" style="margin-top:10px">点「确认安装」后会一次性写入全部配置、生成 <code>install.lock</code> 并删除本页。</p>
  </form>

  <?php endif; ?>
</div>

<div class="card muted">
  <strong style="color:#aab2bf">访问要求：</strong>
  <?php if (instIsLocal()): ?>
    你当前来自 <span class="kbd">127.0.0.1/::1</span>（本机），可直接配置。
  <?php else: ?>
    需要 <span class="kbd">?token=访问令牌</span> 或已通过引导，否则返回 nginx 404。
  <?php endif; ?>
  <br>本页不加载 core.php；所有写入都会先备份再原子替换，失败自动还原。
</div>

</div>
<script>
function mSwitch(el){
  var db = (el.value === 'db');
  document.getElementById('dirBox').style.display  = db ? '' : 'none';
  document.getElementById('mysqlBox').style.display = db ? 'none' : '';
  var ls = document.querySelectorAll('.modes label');
  for (var i=0;i<ls.length;i++) ls[i].className = (ls[i].querySelector('input').disabled ? 'dis' : '')
      + (ls[i].querySelector('input').checked ? ' on' : '');
}
function capSwitch(sel){
  var off = (sel.value === 'off');
  document.getElementById('capBox').style.display = off ? 'none' : '';
  var l1 = document.getElementById('capL1'), l2 = document.getElementById('capL2');
  if (sel.value === 'va'){ l1.textContent='VID'; l2.textContent='VKey'; }
  else { l1.textContent='Sitekey'; l2.textContent='Secret'; }
}
function fillIp(){
  var t = document.getElementById('wlBox');
  t.value = <?php echo json_encode((string)$myIp, JSON_UNESCAPED_UNICODE); ?>;
  t.focus();
}
(function(){
  var sel = document.querySelector('.modes input:checked:not([disabled])');
  if (sel) mSwitch(sel);
  else {
    var first = document.querySelector('.modes input:not([disabled])');
    if (first) { first.checked = true; mSwitch(first); }
    else { document.getElementById('dirBox').style.display='none';
           document.getElementById('mysqlBox').style.display='none'; }
  }
  var ps = document.querySelector('select[name=cap_provider]');
  if (ps) capSwitch(ps);
})();
</script>
</body>
</html>
