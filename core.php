<?php
// ===== Web通信系统 - 核心引擎 =====
require_once __DIR__ . '/config.php';
// ★ 紧急安全加固（2026-09-29）：TOTP 二次验证 + IP 白名单 + 会话纪元（见 security.php）
require_once __DIR__ . '/security.php';
// ★ 数据安全护栏 / 灾后自愈（2026-10-10）：dbOpen 连接后体检，块模式拒绝操作
require_once __DIR__ . '/db_guard.php';

// ★ 确保任何前置输出（包括 BOM、空格、Warning）都被丢弃
if (ob_get_level() > 0) {
    ob_end_clean();
}

// ===== 调试日志函数 =====
function debugLog($message, $context = []) {
    $logFile = __DIR__ . '/db/debug.log';
    $timestamp = date('Y-m-d H:i:s');
    $logEntry = "[$timestamp] $message";
    if (!empty($context)) {
        $logEntry .= " | Context: " . json_encode($context, JSON_UNESCAPED_UNICODE);
    }
    $logEntry .= "\n";

    // 确保db目录存在
    $dbDir = dirname($logFile);
    if (!is_dir($dbDir)) {
        mkdir($dbDir, 0755, true);
    }

    file_put_contents($logFile, $logEntry, FILE_APPEND | LOCK_EX);

    // ★ 保留策略（2026-10-05）：只留最近3天 + ≤5MB，任一超标即清理到两条件同时满足
    if (is_file(__DIR__ . '/log_maintain.php')) {
        @require_once __DIR__ . '/log_maintain.php';
        if (function_exists('logMaintain')) { @logMaintain($logFile); }
    }
}

// ===== 双库切分（2026-10-09 大重构）：Java.db（Java 专属）/ web.db（PHP 专属）=====
// 归属规则：Java 专属表 = api/sync.php 通信组（Java 走 HTTP 读写的 40 张表），
//          其余表归 PHP 专属。跨组 SQL 全仓为 0（IP 两张表已同归 Java 组），
//          所以按表名路由是安全的，任何一方都不需要跨库 JOIN。
// 两侧可以各自是 SQLite 文件或 MySQL 地址（由 sql.md 的「归属」字段决定连谁），
// 本层只负责「按语句里的表名判侧别」，真正给哪个连接由 dbOpen() 按运行模式决定。

if (!function_exists('dbJavaTables')) {
    /** Java 专属表清单（api/sync.php 通信组，41 张，精确匹配不做前缀匹配） */
    function dbJavaTables() {
        static $t = null;
        if ($t === null) {
            $t = array_flip(array(
                'bond_cache', 'cdk', 'cdk_validate_requests', 'cdk_validate_results',
                'confirmed_transactions', 'email_codes', 'game_transactions',
                'hourly_active_players', 'online_player_hb', 'online_players',
                'password_reset_requests', 'password_reset_tokens', 'player_checkins',
                'player_daily_logins', 'player_ip_changes', 'player_ip_locations',
                'shop_config', 'shop_items', 'sync_requests', 'tokens', 'users',
                'web_admin_changes', 'web_area_config', 'web_area_lands',
                'web_area_permissions', 'web_area_shop', 'web_ip_blacklist',
                'web_login_confirmations', 'web_login_requests', 'web_login_verified',
                'web_player_bans', 'web_player_blacklist', 'web_plugin_admins',
                'web_register_requests',
                'web_service_providers', 'web_session_log', 'web_transactions',
                'web_user_group_members', 'web_user_groups', 'weblogin_credentials',
                'weblogin_tokens',
            ));
        }
        return $t;
    }
}

if (!function_exists('dbSideOfTable')) {
    /** 表名 → 'java' | 'web'（未知表一律归 PHP 专属，保证新表始终可被后台访问） */
    function dbSideOfTable($name) {
        $n = strtolower(trim((string)$name, " `\"'\t\n\r"));
        if ($n === '') return 'web';
        $map = dbJavaTables();
        return isset($map[$n]) ? 'java' : 'web';
    }
}

if (!function_exists('dbExtractTables')) {
    /**
     * 抽出一条 SQL 语句涉及的表名（小写、去重）。抽不到返回空数组（PRAGMA/BEGIN 等）。
     * ★ CREATE/DROP INDEX 必须特殊处理：
     *   `CREATE INDEX IF NOT EXISTS idx_xxx ON tbl(...)` 里紧跟关键字的是**索引名**，
     *   直接套通用正则会把索引名当表名 → 索引名不在 Java 清单 → 语句被判成 web 侧 →
     *   在 web 库上给 java 侧表建索引 → 1146 / "no such table" → initTables 整段中断。
     */
    function dbExtractTables($sql) {
        $sql = (string)$sql;
        $out = array();

        // ⓪ upsert 尾段预抹（2026-10-10 灾备演练暴露）：
        //   INSERT ... ON CONFLICT(...) DO UPDATE SET 列=...（SQLite 方言）
        //   INSERT ... ON DUPLICATE KEY UPDATE 列=...（MySQL 方言）
        //   尾段里只有列名，会被 ③ 的 UPDATE 正则误提成表名 → dbPickSide 因混进
        //   非 Java 表而整句判 web → java 组表被送去 caoyuanphp → #1146。
        //   router 判侧发生在方言翻译之前、拿的是 SQLite 原句，故两种形态都要抹；
        //   只抹到分号/语句尾，绝不开跨界（单次 exec/prepare 本就是单语句）。
        $sql = preg_replace('/\bON\s+CONFLICT\b[^;]*/is', ' ', $sql);
        $sql = preg_replace('/\bON\s+DUPLICATE\s+KEY\s+UPDATE\b[^;]*/is', ' ', $sql);

        // ① CREATE [UNIQUE] INDEX [IF NOT EXISTS] <索引名> ON <表名>(...)：只取 ON 之后的表名
        $patIdx = '/\bCREATE\s+(?:UNIQUE\s+)?INDEX(?:\s+IF\s+NOT\s+EXISTS)?\s+[`"\']?[\w$]+[`"\']?\s+ON\s+[`"\']?([\w$]+)/i';
        if (preg_match_all($patIdx, $sql, $mi)) {
            foreach ($mi[1] as $n) $out[strtolower($n)] = true;
            // 抹成 `CREATE INDEX ON`（占位名仍非 Java 表，且已在①里取过真表名）
            $sql = preg_replace($patIdx, 'CREATE INDEX ON ', $sql);
        }
        // ② DROP INDEX <索引名>：后面跟的是索引名而非表名，整段抹掉避免误判
        $sql = preg_replace('/\bDROP\s+INDEX(?:\s+IF\s+EXISTS)?\s+[`"\']?[\w$]+[`"\']?/i', 'DROP INDEX ', $sql);

        // ③ 其余 DDL/DML
        $pat = '/\b(?:CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?|ALTER\s+TABLE|DROP\s+TABLE(?:\s+IF\s+EXISTS)?'
             . '|INSERT\s+(?:OR\s+\w+\s+)?INTO|UPDATE|DELETE\s+FROM|FROM|JOIN)\s+[`"\']?([\w$]+)/i';
        if (preg_match_all($pat, $sql, $m)) {
            foreach ($m[1] as $n) $out[strtolower($n)] = true;
        }
        return array_keys($out);
    }
}

if (!function_exists('dbPickSide')) {
    /**
     * 语句 → 侧别：纯 Java 组表 → 'java'；只要混进一张非 Java 表（或抽不到表名）→ $fallback。
     * fallback 默认维持上一条语句的侧别（让 BEGIN/COMMIT/PRAGMA 跟着当前连接走）。
     */
    function dbPickSide($sql, $fallback = 'web') {
        $tabs = dbExtractTables($sql);
        if (!$tabs) return $fallback;
        foreach ($tabs as $t) {
            if (dbSideOfTable($t) !== 'java') return 'web';
        }
        return 'java';
    }
}

if (!class_exists('SdfDbSideFilter')) {
    /**
     * 侧别过滤器：只执行属于本侧的语句，其余原样跳过。
     * 存在的意义是让 initTables()/migrateDatabase() 的源码一行都不用改就能分家
     * （它们拿到本侧连接后，凡是碰另一侧表的建表/改表语句都被这层拦掉）。
     */
    class SdfDbSideFilter {
        private $db;
        private $side;
        public function __construct($db, $side) { $this->db = $db; $this->side = $side; }
        public function exec($sql) {
            $tabs = dbExtractTables($sql);
            if ($tabs) {
                foreach ($tabs as $t) {
                    if (dbSideOfTable($t) !== $this->side) return true;   // 别侧的表：跳过
                }
            }
            return $this->db->exec($sql);
        }
        public function __call($m, $a) { return call_user_func_array(array($this->db, $m), $a); }
    }
}

if (!class_exists('SdfDbRouter')) {
    /**
     * 按表名路由的连接外观（SQLite3 同形 API）——全仓 224 处 getDB() 调用零改动。
     *   lastInsertRowID()/changes() 依赖「上一次写入在哪一侧」，故每条语句都记账。
     */
    class SdfDbRouter {
        private $last = 'web';
        private $txn = null;       // 事务已绑定的侧别
        private $txnWait = false;  // 收到 BEGIN，但还没绑定到侧别

        /**
         * 定一条语句该走哪一侧。
         * ★ 事务处理是重点：BEGIN/COMMIT 里没有表名，猜错侧别就会把事务撕成两半。
         *   故首个 BEGIN 先挂起，等事务内第一条真正操作表的语句出现时再绑定侧别；
         *   之后语句若与事务侧不一致，一律按事务侧执行（宁可报"表不存在"暴露问题，
         *   也绝不让一半写入提交、另一半丢失）。全仓已核实跨侧 SQL 为 0，此为兜底。
         */
        private function side($sql) {
            $tabs = dbExtractTables($sql);
            if (!$tabs) {
                if ($this->txn !== null) return $this->txn;   // 事务中的 PRAGMA 等跟着事务走
                return $this->last;
            }
            $s = dbPickSide($sql, $this->last);
            $this->last = $s;
            if ($this->txnWait) {
                dbOpen($s)->exec('BEGIN TRANSACTION');
                $this->txn = $s;
                $this->txnWait = false;
            } elseif ($this->txn !== null && $this->txn !== $s) {
                debugLog('dbRouter: 语句侧别与事务侧不一致，按事务侧执行',
                    array('txn' => $this->txn, 'want' => $s, 'sql' => substr((string)$sql, 0, 140)));
                return $this->txn;
            }
            return $s;
        }

        public function exec($sql) {
            $k = strtoupper(preg_replace('/\s+/', ' ', trim((string)$sql)));
            if (preg_match('/^BEGIN\b/', $k)) {
                if ($this->txnWait) return true;                                        // 重复 BEGIN：忽略
                if ($this->txn !== null) return dbOpen($this->txn)->exec($sql);         // 嵌套：与改造前一致地抛错
                $this->txnWait = true;                                                  // 首个 BEGIN：挂起待绑定
                return true;
            }
            if (preg_match('/^(COMMIT|ROLLBACK)\b/', $k)) {
                if ($this->txnWait) { $this->txnWait = false; return true; }            // BEGIN 没绑上就结束了：空事务丢弃
                $side = ($this->txn !== null) ? $this->txn : $this->last;
                $this->txn = null;
                return dbOpen($side)->exec($sql);
            }
            return dbOpen($this->side($sql))->exec($sql);
        }
        public function query($sql)  { return dbOpen($this->side($sql))->query($sql); }
        public function prepare($sql) { return dbOpen($this->side($sql))->prepare($sql); }
        public function querySingle($sql, $assoc = false) { return dbOpen($this->side($sql))->querySingle($sql, $assoc); }
        public function lastInsertRowID() { return dbOpen($this->last)->lastInsertRowID(); }
        public function changes()         { return dbOpen($this->last)->changes(); }
        public function escapeString($v)  { return dbOpen($this->last)->escapeString($v); }
        public function busyTimeout($ms)  { foreach (array('java', 'web') as $s) { @dbOpen($s)->busyTimeout($ms); } return true; }
        public function enableExceptions($f) { foreach (array('java', 'web') as $s) { @dbOpen($s)->enableExceptions($f); } return true; }
        public function close() { return true; }   // 连接由 dbOpen 的 static 缓存复用，不在此关闭
        public function __call($m, $a) { return call_user_func_array(array(dbOpen($this->last), $m), $a); }
    }
}

if (!function_exists('dbSplitMigrateIfNeeded')) {
    /**
     * 首次切分迁移：把 web.db 里 Java 专属的 40 张表（含索引）搬到新建的 Java.db。
     *   幂等 / 原子 / 绝不递归：先写 Java.db.new，校验无残留 WAL 后再 rename 成 Java.db；
     *   中途任何一步失败就删掉 .new 整体放弃，下次请求自动重试，web.db 原样保留不动
     *   （旧数据留在 web.db 作为回退副本，路由层之后不会再读写它们）。
     *   全新部署（web.db 还不存在）直接跳过 —— 没有存量可搬。
     */
    function dbSplitMigrateIfNeeded() {
        static $done = false;
        if ($done) return;
        // ★ MySQL 模式不切分：两侧数据都在 MySQL 里，SQLite 文件已不再使用。
        //   不加这个判断的话，mode=mysql 时仍会把 web.db 白切一遍（11MB 约 18 秒）
        //   并生成一个永远用不到的 Java.db。
        $mcfg = sqlMdDbConfig();
        if (isset($mcfg['mode']) && $mcfg['mode'] === 'mysql'
            && isset($mcfg['java']) && is_array($mcfg['java'])
            && isset($mcfg['web'])  && is_array($mcfg['web'])) {
            $done = true;
            return;
        }
        // 默认让本次请求的 java 侧走共库（web.db）：切分还没确认成功之前，
        // 绝不能让 dbOpen('java') 把一个空 Java.db 建出来——空库一建出来，
        // 路由就会把 Java 表的读写全指过去，存量数据等于"消失"。
        // 只有下面真正切分成功（或已存在带标记的 Java.db）才取消这个回退。
        $GLOBALS['SDF_SPLIT_FALLBACK_WEB'] = true;
        $webFile  = str_replace('\\', '/', sqlDbFile('web'));
        $javaFile = str_replace('\\', '/', sqlDbFile('java'));
        $markFile = $javaFile . '.splitdone';   // 完成标记：只有真正切分成功才写
        if ($webFile === $javaFile || !is_file($webFile)) { $done = true; return; }

        // ① Java.db 存在且带完成标记 → 已真正切分过，跳过
        if (is_file($javaFile) && is_file($markFile)) { $done = true; unset($GLOBALS['SDF_SPLIT_FALLBACK_WEB']); return; }

        // ② Java.db 存在但**无**完成标记 = 历史半成品孤儿（2026-10-09 生产事故形态：
        //    3 张表搬成、37 张表数据留在 web.db，而路由已指向空壳 Java.db）。
        //    旧逻辑只看 is_file 就永久跳过 → 数据永远搬不过去。切分是纯复制
        //    （INSERT..SELECT，源表不动），故可安全备份后重切。
        if (is_file($javaFile)) {
            $bak = $javaFile . '.orphan.' . date('YmdHis');
            @rename($javaFile, $bak);
            @unlink($javaFile . '-wal'); @unlink($javaFile . '-shm');
            if (is_file($javaFile)) { $done = true; return; }   // 备份失败 → 本次放弃
            debugLog('dbSplit: 发现无标记的孤儿 Java.db，已备份待重切', array('bak' => basename($bak)));
        }

        // ③ 文件锁：PHP-FPM 多 worker 并发进来时只允许一个真正切分。
        //    旧逻辑的 static $done/$running 只在单进程内有效，多进程会互删 Java.db.new。
        //    拿不到锁 → 本次请求直接共库运行（切分很快，下次请求自然会看到 Java.db）。
        $lockFile = $javaFile . '.splitlock';
        $lk = @fopen($lockFile, 'c');
        if ($lk === false) { $done = true; return; }
        if (!@flock($lk, LOCK_EX | LOCK_NB)) { @fclose($lk); $done = true; return; }

        $tmp = $javaFile . '.new';
        $q = function ($v) { return "'" . str_replace("'", "''", (string)$v) . "'"; };
        $moved = 0; $errs = ''; $wantTables = 0;
        try {
            @unlink($tmp); @unlink($tmp . '-wal'); @unlink($tmp . '-shm');
            $src = new SQLite3($webFile);
            $src->enableExceptions(true);
            $src->exec('PRAGMA journal_mode=WAL');
            $src->exec('PRAGMA busy_timeout=8000');
            $src->exec('ATTACH DATABASE ' . $q($tmp) . ' AS jd');
            $rs = $src->query("SELECT name, type, sql FROM sqlite_master"
                . " WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'");
            while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
                $name = trim((string)$row['name']);
                $type = strtolower((string)$row['type']);
                $ddl  = (string)$row['sql'];
                // 索引的侧别跟随它的表（索引名本身不在表清单里）
                $judge = $name;
                if ($type === 'index') {
                    if (!preg_match('/\bON\s+[\'"`]?(\w+)/i', $ddl, $mm)) continue;
                    $judge = $mm[1];
                }
                if ($type !== 'table' && $type !== 'index') continue;
                if (dbSideOfTable($judge) !== 'java') continue;      // 只搬 Java 专属的
                if ($type === 'table') $wantTables++;
                $exists = $src->querySingle("SELECT COUNT(*) FROM jd.sqlite_master WHERE name = " . $q($name));
                if ((int)$exists > 0) { if ($type === 'table') $moved++; continue; }   // 已搬过（续跑）
                try {
                    if ($type === 'table') {
                        $new = preg_replace('/^(CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?)([`\"]?)(\w+)/i',
                            '$1jd.$2$3', $ddl, 1);
                        $src->exec($new);                            // 结构（含主键/自增/默认值）
                        $src->exec('INSERT INTO jd.' . $name . ' SELECT * FROM ' . $name);   // 数据
                        $moved++;
                    } else {
                        // ★ 索引名要带 jd. 前缀（索引才落进 jd 库），表名**不能**带前缀：
                        //   SQLite 的 CREATE INDEX 语法里 ON 后面不接受 schema限定，
                        //   写成 `ON jd.tbl` 会直接 `near ".": syntax error`（2026-10-09 生产事故根因）。
                        //   表名不带前缀时，SQLite 在索引所属的 jd 库内解析，正好命中 jd.tbl。
                        $new = preg_replace('/^(CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?)([`\"]?)(\w+)(\s+ON\s+)([`\"]?)(\w+)/i',
                            '$1jd.$2$3$4$5$6', $ddl, 1);
                        $src->exec($new);
                    }
                } catch (\Throwable $e) {
                    $errs .= $name . ':' . $e->getMessage() . '; ';
                }
            }
            try { $src->exec('PRAGMA jd.wal_checkpoint(TRUNCATE)'); } catch (\Throwable $_) {}
            $src->exec('DETACH DATABASE jd');
            $src->close();

            // ★ 完整性校验：只要有任何一张表/索引失败，就整轮作废（删 .new，下次自动重试）。
            //   绝不产出"部分表缺失"的 Java.db —— 那会让路由指向空库、玩家数据全部不可见。
            $gotTables = 0;
            try {
                $chk = new SQLite3($tmp);
                $chk->enableExceptions(true);
                $gotTables = (int)$chk->querySingle(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'");
                $chk->close();
            } catch (\Throwable $_) { $gotTables = -1; }
            if ($errs !== '' || $gotTables < $wantTables) {
                @unlink($tmp); @unlink($tmp . '-wal'); @unlink($tmp . '-shm');
                $done = true;
                debugLog('dbSplit: 切分不完整，已作废 .new 待下次重试',
                    array('want' => $wantTables, 'got' => $gotTables, 'err' => substr($errs, 0, 400)));
                return;
            }

            if (is_file($tmp . '-wal') && @filesize($tmp . '-wal') > 0) {
                // 新库仍有未落盘的 WAL → 不改名（改名会让 -wal 与主文件脱钩），保留 .new 待人工处理
                debugLog('dbSplit: 新库残留 WAL，放弃本次切分', array('wal' => @filesize($tmp . '-wal')));
                $done = true;
                return;
            }
            if (!@rename($tmp, $javaFile)) {
                debugLog('dbSplit: rename 失败，保留 .new 供下次重试');
                return;   // 不置 done：web.db 还在，下次请求自动重来
            }
            // ★ 完成标记：只有走到这里（表数校验通过 + rename 成功）才写。
            //   之后 is_file($javaFile) 之外还要看这个标记，孤儿 Java.db 才能被自动识别重切。
            @file_put_contents($markFile, date('c') . " tables=" . $moved . "/" . $wantTables . "\n");
            $done = true;
            unset($GLOBALS['SDF_SPLIT_FALLBACK_WEB']);   // 切分成功，java 侧恢复独立库
            debugLog('dbSplit: 已切分出 Java.db', array('tables' => $moved, 'want' => $wantTables, 'err' => $errs));
        } catch (\Throwable $e) {
            @unlink($tmp); @unlink($tmp . '-wal'); @unlink($tmp . '-shm');
            $done = true;
            debugLog('dbSplit: 切分失败，回退共库运行', array('error' => $e->getMessage()));
        } finally {
            @flock($lk, LOCK_UN); @fclose($lk);
        }
    }
}

// ===== MySQL 适配层（2026-10-09 大重构 · Task103/104/105）=====
// 目的：sql.md 运行模式 = mysql 且该侧地址齐全时，全站 224 处 getDB() / 1500+ 处
//       bindValue / 1400+ 处 SQLite 方言**一行都不用改**地跑在 MySQL 上。
// 组成：① sqlDialectMySql()  —— SQLite → MySQL 方言翻译器（纯函数，逐条语句）
//       ② sqlMySqlNormalize() —— :name / $1 占位符归一为 ? 并产出参数位置表
//       ③ SdfMySqlDb / SdfMySqlStmt / SdfMySqlResult —— 复刻 SQLite3 系 API 形状
// 软依赖：mode=mysql 但 mysqli 扩展缺失 / 地址不全 / 连接失败 → dbOpen() 自动回退 SQLite。

if (!defined('SQLITE3_ASSOC'))   define('SQLITE3_ASSOC', 1);
if (!defined('SQLITE3_NUM'))     define('SQLITE3_NUM', 2);
if (!defined('SQLITE3_BOTH'))    define('SQLITE3_BOTH', 3);
if (!defined('SQLITE3_INTEGER')) define('SQLITE3_INTEGER', 1);
if (!defined('SQLITE3_FLOAT'))   define('SQLITE3_FLOAT', 2);
if (!defined('SQLITE3_TEXT'))    define('SQLITE3_TEXT', 3);
if (!defined('SQLITE3_BLOB'))    define('SQLITE3_BLOB', 4);
if (!defined('SQLITE3_NULL'))    define('SQLITE3_NULL', 5);

// 方言翻译产物标记（不是合法 SQL，只有适配器认识）
define('SDF_SQL_NOOP', "\x01SDF_NOOP\x01");                 // 整条语句无需执行（PRAGMA journal_mode 之类）
define('SDF_SQL_SHOWCREATE', "\x01SDF_SHOWCREATE\x01");     // 需要执行 SHOW CREATE TABLE 取建表文本

if (!function_exists('sqlMySqlSplitSegments')) {
    /**
     * 把 SQL 切成 [是否字符串字面量, 文本] 两段序列。
     * 字符串识别规则与 MySQL 一致：'' / "" 是转义的引号，\x 是转义符，反引号用 `` 转义。
     * 所有"只在 SQL 结构上做替换"的步骤（key 反引号、占位符归一、? 渲染）都必须走它，
     * 否则会把 WHERE name='key' 里的 key 也改掉。
     */
    function sqlMySqlSplitSegments($sql) {
        $sql = (string)$sql;
        $segs = array(); $buf = ''; $n = strlen($sql); $i = 0;
        while ($i < $n) {
            $ch = $sql[$i];
            if ($ch === "'" || $ch === '"' || $ch === '`') {
                if ($buf !== '') { $segs[] = array(false, $buf); $buf = ''; }
                $start = $i; $q = $ch; $i++;
                while ($i < $n) {
                    if ($sql[$i] === '\\' && $q !== '`') { $i += 2; continue; }
                    if ($sql[$i] === $q) {
                        if ($i + 1 < $n && $sql[$i + 1] === $q) { $i += 2; continue; }
                        $i++; break;
                    }
                    $i++;
                }
                $segs[] = array(true, substr($sql, $start, $i - $start));
            } else {
                $buf .= $ch; $i++;
            }
        }
        if ($buf !== '') $segs[] = array(false, $buf);
        return $segs;
    }
}

if (!function_exists('sqlMySqlMapLiterals')) {
    /** 只对非字面量片段跑 $fn（回调接收一段 SQL 结构文本，返回替换后文本）。 */
    function sqlMySqlMapLiterals($sql, $fn) {
        $out = '';
        foreach (sqlMySqlSplitSegments($sql) as $seg) {
            $out .= $seg[0] ? $seg[1] : call_user_func($fn, $seg[1]);
        }
        return $out;
    }
}

if (!function_exists('sqlMySqlSplitTop')) {
    /** 按顶层逗号切分（尊重括号与字符串）—— CREATE TABLE 列定义、索引列清单都用它。 */
    function sqlMySqlSplitTop($s) {
        $parts = array(); $depth = 0; $cur = ''; $n = strlen($s); $i = 0; $in = null;
        while ($i < $n) {
            $ch = $s[$i];
            if ($in !== null) {
                $cur .= $ch;
                if ($ch === $in) {
                    if ($in !== '`' && $i + 1 < $n && $s[$i + 1] === $in) { $cur .= $s[$i + 1]; $i += 2; continue; }
                    $in = null;
                }
                $i++; continue;
            }
            if ($ch === "'" || $ch === '"' || $ch === '`') { $in = $ch; $cur .= $ch; $i++; continue; }
            if ($ch === '(') $depth++;
            elseif ($ch === ')') $depth--;
            if ($ch === ',' && $depth === 0) { $parts[] = $cur; $cur = ''; $i++; continue; }
            $cur .= $ch; $i++;
        }
        if (trim($cur) !== '') $parts[] = $cur;
        return $parts;
    }
}

if (!function_exists('sqlMySqlReservedCols')) {
    /** 撞 MySQL 保留字的列名清单（命中就加反引号；反引号对非保留字也无害，故宁多勿漏）。 */
    function sqlMySqlReservedCols() {
        static $r = null;
        if ($r === null) {
            $r = array_flip(array(
                'key', 'keys', 'desc', 'rank', 'rows', 'groups', 'group', 'system', 'systems',
                'column', 'columns', 'data', 'index', 'fulltext', 'usage', 'read', 'write',
                'lock', 'mode', 'zones', 'zone', 'sonic', 'sound', 'equal', 'empty', 'begin',
                'end', 'top', 'level', 'speed', 'engine', 'gravity', 'start', 'stop', 'kill',
                'apply', 'disable', 'enable', 'master', 'second', 'seconds', 'minute', 'hour',
                'day', 'month', 'year', 'year_month', 'current', 'shared', 'specific', 'tables',
            ));
        }
        return $r;
    }
}

if (!function_exists('sqlMySqlQuoteKeyCol')) {
    /**
     * web_area_config 的列名叫 key（MySQL 保留字），DML 里裸写会 1064。
     * 只对字符串字面量之外、且不是 primary/foreign/unique/duplicate key 的裸 key 加反引号。
     * :key 绑定符被 (?<![:\w$]) 挡掉，cfg_key 之类被词边界挡掉。
     *
     * ★ duplicate 必须排除：section 4 已把 SQLite 的 ON CONFLICT(key) DO UPDATE 翻成
     *   MySQL 的 ON DUPLICATE KEY UPDATE，这里若把其中的 KEY 反引号会得到
     *   ON DUPLICATE `key` UPDATE → #1064（test12 2026-10-10 的 6 条报错即此）。
     */
    function sqlMySqlQuoteKeyCol($sql) {
        return sqlMySqlMapLiterals($sql, function ($s) {
            return preg_replace_callback(
                '/(?<![:\w$])(?<!primary\s)(?<!foreign\s)(?<!unique\s)(?<!duplicate\s)key(?![\w$])/i',
                function ($m) { return '`key`'; },
                $s
            );
        });
    }
}

if (!function_exists('sqlMySqlNormalize')) {
    /**
     * 占位符归一：:name / $name / $1 → ?，并产出参数位置表。
     * @return array [归一后的 SQL, map]  map = ['named'=>token=>[0基位置...], 'count'=>占位符总数]
     */
    function sqlMySqlNormalize($sql) {
        $map = array('named' => array(), 'count' => 0);
        $out = ''; $pos = 0;
        foreach (sqlMySqlSplitSegments($sql) as $seg) {
            if ($seg[0]) { $out .= $seg[1]; continue; }
            $s = $seg[1]; $len = strlen($s); $buf = ''; $i = 0;
            while ($i < $len) {
                $ch = $s[$i];
                if ($ch === '?') { $buf .= '?'; $pos++; $i++; continue; }
                if ($ch === ':') {
                    if ($i + 1 < $len && $s[$i + 1] !== ':' && preg_match('/[A-Za-z_]/', $s[$i + 1])) {
                        if (preg_match('/\G[A-Za-z_]\w*/', $s, $mm, 0, $i + 1)) {
                            $tok = ':' . $mm[0];
                            if (!isset($map['named'][$tok])) $map['named'][$tok] = array();
                            $map['named'][$tok][] = $pos;
                            $buf .= '?'; $pos++; $i += 1 + strlen($mm[0]); continue;
                        }
                    }
                    $buf .= $ch; $i++; continue;
                }
                if ($ch === '$' && $i + 1 < $len) {
                    if (preg_match('/\G\$[A-Za-z_]\w*/', $s, $mm, 0, $i)
                        || preg_match('/\G\$\d+/', $s, $mm, 0, $i)) {
                        $tok = $mm[0];
                        if (!isset($map['named'][$tok])) $map['named'][$tok] = array();
                        $map['named'][$tok][] = $pos;
                        $buf .= '?'; $pos++; $i += strlen($tok); continue;
                    }
                }
                $buf .= $ch; $i++;
            }
            $out .= $buf;
        }
        $map['count'] = $pos;
        return array($out, $map);
    }
}

if (!function_exists('sqlMySqlRenderValue')) {
    /** 单个绑定值 → SQL 字面量（走连接的 real_escape_string，连接已 SET NAMES utf8mb4）。 */
    function sqlMySqlRenderValue($v, $type, $esc) {
        if ($v === null) return 'NULL';
        if ($type === SQLITE3_NULL) return 'NULL';
        if ($type === SQLITE3_INTEGER) {
            return is_scalar($v) ? (string)(int)$v : 'NULL';
        }
        if ($type === SQLITE3_FLOAT) {
            if (!is_scalar($v)) return 'NULL';
            $f = (float)$v;
            if (is_nan($f) || is_infinite($f)) return 'NULL';
            $s = sprintf('%.17G', $f);
            // ★ 只有小数形式才能去尾零（"1.5000"→"1.5"）。%.17G 对整数值不产生小数点，
            //   尾随 0 是**有效数位**：0.0→"0"、10.0→"10"、100.0→"100"，
            //   直接 rtrim('0') 会吃成 ""/1/1 → 空槽位 #1064（test12 line 22）与坐标静默被篡改。
            //   指数形式（1e20→"1e+20"）的尾 0 同样不能碰，故 'e' 一并排除。
            if (strpos($s, '.') !== false && stripos($s, 'e') === false) {
                $s = rtrim(rtrim($s, '0'), '.');
            }
            if ($s === '' || $s === '-' || $s === '+') $s = '0';   // 兜底：绝不产出空/孤立正负号
            return $s;
        }
        if ($type === SQLITE3_BLOB) { return "x'" . bin2hex((string)$v) . "'"; }
        $s = (string)$v;
        return "'" . call_user_func($esc, $s) . "'";
    }
}

if (!function_exists('sqlMySqlRender')) {
    /** 把归一后的 SQL 里的第 n 个 ? 换成第 n 个绑定值（字面量之外的 ? 才算占位符）。 */
    function sqlMySqlRender($mysqlSql, $vals, $types, $esc) {
        $out = ''; $n = 0;
        foreach (sqlMySqlSplitSegments($mysqlSql) as $seg) {
            if ($seg[0]) { $out .= $seg[1]; continue; }
            $s = $seg[1]; $len = strlen($s);
            for ($k = 0; $k < $len; $k++) {
                if ($s[$k] === '?') {
                    $v = array_key_exists($n, $vals) ? $vals[$n] : null;      // 未绑定 = NULL（与 SQLite 一致）
                    $t = array_key_exists($n, $types) ? $types[$n] : SQLITE3_TEXT;
                    $out .= sqlMySqlRenderValue($v, $t, $esc);
                    $n++;
                } else {
                    $out .= $s[$k];
                }
            }
        }
        return $out;
    }
}

if (!function_exists('sqlMySqlColumnPart')) {
    /**
     * CREATE TABLE 里的一段（一列 或 一条表级约束）→ MySQL 版。
     * 变换规则（对齐 SQLite 语义）：
     *   TEXT 作键（PRIMARY KEY / UNIQUE，含表级约束里的引用）→ VARCHAR（长度按索引字节预算算）
     *   TEXT 带 DEFAULT → 8.0.13+ 写成 DEFAULT ('x')；MariaDB 10.2+ 原样；老 MySQL 退化为 VARCHAR
     *   INTEGER → BIGINT（SQLite 的 INTEGER 是 64 位，毫秒时间戳塞进 INT 会溢出）
     *   INTEGER PRIMARY KEY 且无 DEFAULT → 补 AUTO_INCREMENT（SQLite 行号语义）
     *   AUTO_INCREMENT 列带 DEFAULT → 去掉 DEFAULT（MySQL 1067 非法）
     *   撞保留字的列名 → 反引号
     *
     * @param string $part  一段 DDL
     * @param array  $opt  ['textDefaultMode'=>..., 'keyLen'=>array(列名=>VARCHAR长度)]
     */
    function sqlMySqlColumnPart($part, $opt = array()) {
        $t = trim($part);
        if ($t === '') return $part;
        $keyLen = isset($opt['keyLen']) && is_array($opt['keyLen']) ? $opt['keyLen'] : array();
        if (preg_match('/^(PRIMARY\s+KEY|UNIQUE)\b/i', $t)) {
            // 表级键约束：只给列清单里的列名加反引号（UNIQUE/PRIMARY/KEY 这些关键字必须保持裸写）
            if (!preg_match('/^(PRIMARY\s+KEY|UNIQUE)\s*\(([\s\S]*)\)\s*$/i', $t, $m)) return $t;
            $cols = array();
            foreach (sqlMySqlSplitTop($m[2]) as $c) {
                $c = trim($c);
                $cols[] = preg_match('/^([`\"]?)(\w+)\1$/', $c, $mm) ? '`' . $mm[2] . '`' : $c;
            }
            return strtoupper(substr($m[1], 0, 1)) . substr($m[1], 1) . '(' . implode(',', $cols) . ')';
        }
        // ★ 表级索引必须"后接 (列清单)"才算：KEY/INDEX 后面跟类型关键字的是**列定义**
        //   （web_area_config 的 `key TEXT PRIMARY KEY` 就以 key 开头）。老写法用 ^KEY\b
        //   一票判定，会把该列当表级约束整段跳过 → TEXT 键列没转成 VARCHAR → 1170
        //   （test12 实测 137 条，直接导致 web_area_config 建不出来 → 1146）。
        if (preg_match('/^(?:KEY|INDEX)\s*(?:`?[\w$]+`?\s*)?\(/i', $t)) {
            return $t;   // 表级索引 KEY idx_name (cols)：长度由 CREATE INDEX 适配处理，原样保留
        }
        if (preg_match('/^(CHECK|CONSTRAINT|FOREIGN)\b/i', $t)) {
            return $t;   // 表级非键约束：CHECK/CONSTRAINT/FOREIGN 本仓不用，原样保留
        }
        if (!preg_match('/^([`\"]?)(\w+)\1\s+(\w+)([\s\S]*)$/', $t, $m)) return $t;
        $name = $m[2];
        $type = strtoupper($m[3]);
        $rest = $m[4];
        $pk   = (bool)preg_match('/\bPRIMARY\s+KEY\b/i', $rest);
        $uniq = (bool)preg_match('/\bUNIQUE\b/i', $rest);
        // ★ 被表级 PRIMARY KEY(...) / UNIQUE(...) 引用的列同样是"键列"，
        //   但它自己的定义里没有 PRIMARY KEY/UNIQUE 字样，只能靠 sqlMySqlPlanKeyLengths
        //   预算出来的 keyLen 反查判定 —— 漏了这一步就是 1170（BLOB/TEXT used in key）。
        $inTableKey = isset($keyLen[strtolower($name)]);
        $keyed = $pk || $uniq || $inTableKey;
        $hasDefault = (bool)preg_match('/\bDEFAULT\b/i', $rest);
        $mode = isset($opt['textDefaultMode']) ? $opt['textDefaultMode'] : 'expr';

        if ($type === 'TEXT' || $type === 'TINYTEXT' || $type === 'MEDIUMTEXT' || $type === 'LONGTEXT'
            || $type === 'BLOB' || $type === 'TINYBLOB' || $type === 'MEDIUMBLOB' || $type === 'LONGBLOB') {
            $isText = ($type !== 'BLOB' && $type !== 'TINYBLOB' && $type !== 'MEDIUMBLOB' && $type !== 'LONGBLOB');
            if ($keyed) {
                // 键列长度由表级预算统一决定（单列 500 / 多列按 3072 字节均分并留余量）
                $len = $inTableKey ? (int)$keyLen[strtolower($name)] : 500;
                if ($len < 1) $len = 1;
                if ($len > 500) $len = 500;
                $type = $isText ? 'VARCHAR(' . $len . ')' : 'VARBINARY(' . $len . ')';
                // 键列的 DEFAULT 一律去掉（VARCHAR 带默认值没问题，但 PRIMARY KEY 列带默认值在
                // MySQL 里语义混乱，且 SQLite 的 PK 列默认值毫无意义）—— 只在 PK 时去
                if ($pk) {
                    $rest = preg_replace('/\s*\bDEFAULT\s+(?:\'(?:[^\']|\'\')*\'|"(?:[^"]|"")*"|[^\s,]+)/i', '', $rest);
                    $hasDefault = false;
                }
            } elseif ($hasDefault) {
                if ($mode === 'expr') {
                    // MySQL ≥8.0.13：字面量默认值必须包在括号里才算表达式
                    $rest = preg_replace_callback(
                        '/\bDEFAULT\s+(\'(?:[^\']|\'\')*\'|"(?:[^"]|"")*"|NULL|\d+(?:\.\d+)?)/i',
                        function ($mm) {
                            $lit = $mm[1];
                            if (strcasecmp($lit, 'NULL') === 0) return '';
                            return 'DEFAULT (' . $lit . ')';
                        },
                        $rest, 1
                    );
                } elseif ($mode === 'none') {
                    // 老 MySQL（<8.0.13）：TEXT 一律不能有默认值 → 有默认值的列退化成 VARCHAR(500)
                    // （严格模式下超长会**报错**而不是静默截断，宁可大声失败）
                    $type = 'VARCHAR(500)';
                }
                // mode=plain（MariaDB ≥10.2）：TEXT DEFAULT 'x' 本来就合法，原样保留
            }
        } elseif ($type === 'INTEGER' || $type === 'INT') {
            $type = 'BIGINT';
        } elseif ($type === 'REAL') {
            $type = 'DOUBLE';
        }

        if (preg_match('/\bAUTO_INCREMENT\b/i', $rest)) {
            $rest = preg_replace('/\s*\bDEFAULT\s+(?:\'(?:[^\']|\'\')*\'|"(?:[^"]|"")*"|[^\s,]+)/i', '', $rest);
        } elseif ($pk && !$hasDefault && ($type === 'BIGINT' || $type === 'INT')) {
            $rest = rtrim($rest) . ' AUTO_INCREMENT';   // SQLite 的 INTEGER PRIMARY KEY = 行号自增
        }

        $reserved = sqlMySqlReservedCols();
        $name = isset($reserved[strtolower($name)]) ? '`' . $name . '`' : '`' . $name . '`';   // 统一加反引号

        return $name . ' ' . $type . $rest;
    }
}

if (!function_exists('sqlMySqlPlanKeyLengths')) {
    /**
     * 预算表级键约束里各 TEXT 键列该用多长的 VARCHAR。
     * InnoDB 单索引键上限 3072 字节（DYNAMIC 行格式），utf8mb4 每字符最多 4 字节，
     * 每列还要 2 字节变长长度前缀 → 可用字符预算 = (3072 - 固定宽列字节 - 2*n) / 4。
     *   单 TEXT 键列   → 500（远在预算内）
     *   多个 TEXT 键列 → 均分可用预算，并留 15% 余量（实测 384×2 恰好 3072 会 1071）
     * 返回 array(列名小写 => 长度)。
     */
    function sqlMySqlPlanKeyLengths($parts) {
        // 先收列定义
        $types = array();
        $tblKeys = array();
        foreach ($parts as $part) {
            $t = trim($part);
            if ($t === '') continue;
            if (preg_match('/^(PRIMARY\s+KEY|UNIQUE)\b/i', $t)) {
                if (preg_match('/^(PRIMARY\s+KEY|UNIQUE)\s*\(([\s\S]*)\)\s*$/i', $t, $m)) {
                    $names = array();
                    foreach (sqlMySqlSplitTop($m[2]) as $c) {
                        $c = trim($c);
                        if (preg_match('/^([`\"]?)(\w+)\1$/', $c, $mm)) $names[] = $mm[2];
                    }
                    if ($names) $tblKeys[] = $names;
                }
                continue;
            }
            if (preg_match('/^([`\"]?)(\w+)\1\s+([A-Za-z]+)/', $t, $m)) {
                $rest = substr($t, strlen($m[0]));
                $types[strtolower($m[2])] = array(
                    'type' => strtoupper($m[3]),
                    'keyed' => (bool)(preg_match('/\bPRIMARY\s+KEY\b/i', $rest) || preg_match('/\bUNIQUE\b/i', $rest)),
                );
            }
        }
        $isTextish = function ($ty) {
            return in_array($ty, array('TEXT', 'BLOB', 'TINYTEXT', 'MEDIUMTEXT', 'LONGTEXT',
                                       'TINYBLOB', 'MEDIUMBLOB', 'LONGBLOB'), true);
        };
        $fixed = array('INTEGER' => 8, 'INT' => 8, 'BIGINT' => 8, 'SMALLINT' => 2, 'TINYINT' => 1,
                       'REAL' => 8, 'DOUBLE' => 8, 'FLOAT' => 4, 'BOOLEAN' => 1);
        $out = array();

        // ① 表级键约束：逐个独立预算（每个键都是独立索引，不共享 3072）
        foreach ($tblKeys as $names) {
            $txt = array(); $fx = 0;
            foreach ($names as $cn) {
                $k = strtolower($cn);
                if (isset($types[$k])) {
                    if ($isTextish($types[$k]['type'])) { $txt[] = $k; continue; }
                    $fx += isset($fixed[$types[$k]['type']]) ? $fixed[$types[$k]['type']] : 8;
                } else {
                    $fx += 8;
                }
            }
            $n = count($txt);
            if ($n === 0) continue;
            if ($n === 1) { if (!isset($out[$txt[0]])) $out[$txt[0]] = 500; continue; }
            $avail = 3072 - $fx - 2 * $n;              // 扣掉每列 2 字节长度前缀
            $per = (int)floor($avail / 4 / $n * 0.85);  // 再留 15% 余量
            if ($per > 500) $per = 500;
            if ($per < 32) $per = 32;
            foreach ($txt as $k) {
                $cur = isset($out[$k]) ? $out[$k] : 500;
                $out[$k] = min($cur, $per);             // 多处引用同一列时取最严的
            }
        }
        // ② 内联单列键（PRIMARY KEY / UNIQUE 写在列定义里）
        foreach ($types as $cn => $info) {
            if ($info['keyed'] && $isTextish($info['type']) && !isset($out[$cn])) $out[$cn] = 500;
        }
        return $out;
    }
}

if (!function_exists('sqlMySqlTranslateCreateTable')) {
    /** CREATE TABLE 全句翻译：换掉列定义 + 补表选项（InnoDB/utf8mb4_bin 与 SQLite 语义对齐）。 */
    function sqlMySqlTranslateCreateTable($sql, $opt = array()) {
        if (!preg_match('/^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([`\"]?)(\w+)\1\s*\(/i', $sql, $m, PREG_OFFSET_CAPTURE)) {
            return $sql;
        }
        $openPos = $m[0][1] + strlen($m[0][0]);
        $depth = 1; $i = $openPos; $len = strlen($sql); $in = null;
        for (; $i < $len; $i++) {
            $ch = $sql[$i];
            if ($in !== null) {
                if ($ch === $in) {
                    if ($in !== '`' && $i + 1 < $len && $sql[$i + 1] === $in) { $i++; continue; }
                    $in = null;
                }
                continue;
            }
            if ($ch === "'" || $ch === '"' || $ch === '`') { $in = $ch; continue; }
            if ($ch === '(') $depth++;
            elseif ($ch === ')') { $depth--; if ($depth === 0) break; }
        }
        if ($depth !== 0) return $sql;
        $body = substr($sql, $openPos, $i - $openPos);
        $tail = substr($sql, $i + 1);

        $parts = sqlMySqlSplitTop($body);
        // 先算表级键里各 TEXT 键列的 VARCHAR 长度（3072 字节索引上限均分），再逐段翻译
        $opt['keyLen'] = sqlMySqlPlanKeyLengths($parts);
        $out = array();
        foreach ($parts as $part) {
            $out[] = sqlMySqlColumnPart($part, $opt);
        }
        $head = substr($sql, 0, $openPos);
        $tail = trim($tail);
        $tail = preg_replace('/^;\s*/', '', $tail);
        if (preg_match('/\bENGINE\s*=/i', $tail)) {
            return $head . implode(',', $out) . ') ' . $tail;
        }
        return $head . implode(',', $out) . ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ' . $tail;
    }
}

if (!function_exists('sqlMySqlTranslateAlterTable')) {
    /** ALTER TABLE：只翻 ADD COLUMN 的列定义（ALTER ... RENAME / DROP COLUMN 原样可用）。 */
    function sqlMySqlTranslateAlterTable($sql, $opt = array()) {
        if (!preg_match('/^(.*?\bALTER\s+TABLE\s+[`\"]?\w+[`\"]?\s+(?:ADD|CHANGE|MODIFY)\s+(?:COLUMN\s+)?)([\s\S]*)$/i', $sql, $m)) {
            return $sql;
        }
        $head = $m[1]; $defs = trim($m[2]);
        $out = array();
        foreach (sqlMySqlSplitTop($defs) as $part) {
            $out[] = sqlMySqlColumnPart($part, $opt);
        }
        return $head . implode(',', $out);
    }
}

if (!function_exists('sqlDialectMySql')) {
    /**
     * SQLite → MySQL 方言翻译器。纯函数：不碰连接、不查库，输入什么语句输出什么语句。
     * 覆盖面按全仓实测：PRAGMA 21 种 / sqlite_master 13 形态 / AUTOINCREMENT 65 处 /
     * INSERT OR REPLACE 73 处 / INSERT OR IGNORE 19 处 / ON CONFLICT 15 处 /
     * CREATE INDEX IF NOT EXISTS 27 处 / TEXT 主键 22 处 / TEXT DEFAULT 73 处 / key 保留字 1 处。
     * `||` 拼接（SQL 内 14 处）不在这里翻：连库时把 sql_mode 设成 PIPES_AS_CONCAT 一次性解决，
     * 逐处翻成 CONCAT 在没有完整表达式解析器的情况下极易翻错。
     *
     * @param string $sql
     * @param array  $opt ['textDefaultMode' => 'expr'|'plain'|'none']
     * @return string SDF_SQL_NOOP / SDF_SQL_SHOWCREATE表名 / 翻译后的 MySQL 语句
     */
    function sqlDialectMySql($sql, $opt = array()) {
        $sql = (string)$sql;
        if ($sql === '') return SDF_SQL_NOOP;
        $trim = ltrim($sql);

        // ---------- 1. PRAGMA ----------
        if (preg_match('/^PRAGMA\s+table_info\s*\(\s*[\'"]?(\w+)[\'"]?\s*\)\s*;?\s*$/is', $trim, $m)) {
            $t = str_replace("'", "''", $m[1]);
            // ★ 列顺序必须与 SQLite 的 PRAGMA table_info 完全一致（cid, name, type, notnull, dflt_value, pk），
            //   否则 SQLITE3_NUM 取值形态会整体错位（消费点 core.php:1023 一类按 $row['name'] 取值则不受影响）。
            //   cid/notnull/pk 一律 CAST 成整数：MySQL 表达式默认给字符串，原生给 int（=== 对拍会挂）。
            return "SELECT CAST(ORDINAL_POSITION - 1 AS SIGNED) AS cid,"
                 . " COLUMN_NAME AS `name`,"
                 . " COLUMN_TYPE AS `type`,"
                 . " CAST(IF(IS_NULLABLE='NO' AND COLUMN_KEY<>'PRI',1,0) AS SIGNED) AS notnull,"
                 . " COLUMN_DEFAULT AS dflt_value,"
                 . " CAST(IF(COLUMN_KEY='PRI',1,0) AS SIGNED) AS pk"
                 . " FROM information_schema.COLUMNS"
                 . " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" . $t . "'"
                 . " ORDER BY ORDINAL_POSITION";
        }
        if (preg_match('/^PRAGMA\b/i', $trim)) {
            return SDF_SQL_NOOP;   // journal_mode / busy_timeout / synchronous / cache_size / wal_* 全是 SQLite 专有调优项
        }

        // ---------- 2. sqlite_master ----------
        if (stripos($sql, 'sqlite_master') !== false) {
            // 取建表文本（core.php 用它检测老表有没有 UNIQUE 约束）→ SHOW CREATE TABLE 替身
            if (preg_match('/SELECT\s+sql\s+FROM\s+sqlite_master\s+WHERE[\s\S]*?name\s*=\s*\'([^\']+)\'/i', $sql, $m)) {
                return SDF_SQL_SHOWCREATE . $m[1];
            }
            // 表存在性 / 表名清单 → information_schema
            if (preg_match('/SELECT\s+(?:name|1)\s+FROM\s+sqlite_master\s+WHERE\s+type\s*=\s*\'table\'([\s\S]*?)(?:;|$)/i', $sql, $m)) {
                if (preg_match('/\bname\s*=\s*\'([^\']+)\'/i', $m[1], $mm)) {
                    $cond = "TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" . str_replace("'", "''", $mm[1]) . "'";
                } else {
                    $cond = "TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'";
                }
                return "SELECT TABLE_NAME AS `name` FROM information_schema.TABLES WHERE " . $cond;
            }
            // 剩余形态（带 sql 列的全量扫描等）只在 SQLite 文件切分流程里出现；
            // 原样返回让它报错并进日志，比静默改错安全。
            return $sql;
        }

        // ---------- 3. 事务 ----------
        if (preg_match('/^BEGIN\b/i', $trim)) {
            // MySQL 只认 BEGIN [WORK] / START TRANSACTION；BEGIN TRANSACTION / BEGIN IMMEDIATE 都是 1064
            $sql = preg_replace('/^(\s*)BEGIN\b(\s+(?:TRANSACTION|IMMEDIATE|DEFERRED))?(\s+WORK)?\b/i', '$1START TRANSACTION', $sql, 1);
        } elseif (preg_match('/^\s*END\s*;?\s*$/i', $sql)) {
            $sql = 'COMMIT';    // SQLite 的 END = 提交；MySQL 的 END 是存储程序块结束符
        }

        // ---------- 4. 插入冲突语义 ----------
        $sql = preg_replace('/\bINSERT\s+OR\s+REPLACE\s+INTO\b/i', 'REPLACE INTO', $sql);
        $sql = preg_replace('/\bINSERT\s+OR\s+IGNORE\s+INTO\b/i', 'INSERT IGNORE INTO', $sql);
        $sql = preg_replace('/\bINSERT\s+OR\s+(?:FAIL|ABORT|ROLLBACK)\s+INTO\b/i', 'INSERT INTO', $sql);
        if (preg_match('/\bON\s+CONFLICT\s*\(/i', $sql)) {
            $sql = preg_replace('/\bON\s+CONFLICT\s*\([^)]*\)\s*DO\s+UPDATE\s+SET\b/i', 'ON DUPLICATE KEY UPDATE', $sql);
            // excluded.x = 新行的值；MySQL 里等价物是 VALUES(x)
            $sql = preg_replace('/\bexcluded\s*\.\s*([`\"]?\w+[`\"]?)/i', 'VALUES($1)', $sql);
            $sql = preg_replace('/\bON\s+CONFLICT\s*\([^)]*\)\s*DO\s+NOTHING\b/i', '', $sql);   // 全仓 0 处，兜底
        }

        // ---------- 5. AUTOINCREMENT（拼写差异） ----------
        $sql = preg_replace('/\bAUTOINCREMENT\b/i', 'AUTO_INCREMENT', $sql);

        // ---------- 6. CREATE INDEX：MySQL 不支持 IF NOT EXISTS（重复索引由适配器吞 1061） ----------
        if (preg_match('/^CREATE\s+(?:UNIQUE\s+)?INDEX\b/i', $trim)) {
            $sql = preg_replace('/^(CREATE\s+(?:UNIQUE\s+)?INDEX\s+)IF\s+NOT\s+EXISTS\s+/i', '$1', $sql);
            $sql = ltrim($sql);
        }

        // ---------- 7. DDL ----------
        if (preg_match('/^CREATE\s+TABLE\b/i', $trim)) {
            $sql = sqlMySqlTranslateCreateTable($sql, $opt);
        } elseif (preg_match('/^ALTER\s+TABLE\b/i', $trim)) {
            $sql = sqlMySqlTranslateAlterTable($sql, $opt);
        }

        // ---------- 8. 时间 / 随机函数 ----------
        $sql = preg_replace("/\bdatetime\s*\(\s*([^,()]+?)\s*,\s*'unixepoch'\s*,\s*'localtime'\s*\)/i", 'FROM_UNIXTIME($1)', $sql);
        $sql = preg_replace("/\bdatetime\s*\(\s*([^,()]+?)\s*,\s*'unixepoch'\s*\)/i", 'FROM_UNIXTIME($1)', $sql);
        $sql = preg_replace("/\bdatetime\s*\(\s*'now'\s*\)/i", 'UTC_TIMESTAMP()', $sql);
        $sql = preg_replace("/\bdate\s*\(\s*'now'\s*\)/i", 'CURDATE()', $sql);
        $sql = preg_replace("/\btime\s*\(\s*'now'\s*\)/i", 'CURTIME()', $sql);
        $sql = preg_replace("/\bstrftime\s*\(\s*'%s'\s*,\s*'now'\s*\)/i", 'UNIX_TIMESTAMP()', $sql);
        $sql = preg_replace('/\bRANDOM\s*\(\s*\)/i', 'RAND()', $sql);

        // ---------- 9. web_area_config 的 key 列（唯一撞保留字的列） ----------
        if (preg_match('/\bweb_area_config\b/i', $sql)) {
            $sql = sqlMySqlQuoteKeyCol($sql);
        }

        // ---------- 10. 占位符归一 :name → ? ----------
        $sql = sqlMySqlNormalize($sql)[0];
        return $sql;
    }
}

if (!class_exists('SdfMySqlResult', false)) {
    /** SQLite3Result 同形：fetchArray(SQLITE3_ASSOC|SQLITE3_NUM) / finalize / numColumns。 */
    class SdfMySqlResult {
        private $res;          // mysqli_result | null
        private $rows = null;  // 数组后备（SHOW CREATE TABLE 这类需要改列名的结果）
        private $idx = 0;
        private $done = false;

        public function __construct($res, $rows = null) { $this->res = $res; $this->rows = $rows; }

        public function fetchArray($mode = SQLITE3_ASSOC) {
            if ($this->rows !== null) {
                if ($this->done || !isset($this->rows[$this->idx])) { $this->done = true; return false; }
                $row = $this->rows[$this->idx++];
                return ($mode === SQLITE3_NUM) ? array_values($row) : $row;
            }
            if ($this->res === null || $this->done) return false;
            $row = ($mode === SQLITE3_NUM) ? $this->res->fetch_row() : $this->res->fetch_assoc();
            if ($row === null || $row === false) { $this->done = true; return false; }
            return $row;
        }

        public function numColumns() {
            if ($this->rows !== null) { $k = array_keys($this->rows); return $k ? count($this->rows[0]) : 0; }
            return ($this->res instanceof mysqli_result) ? (int)$this->res->field_count : 0;
        }

        public function finalize() { $this->free(); return true; }
        public function free() {
            if ($this->res instanceof mysqli_result) { @$this->res->free(); }
            $this->res = null; $this->rows = null; $this->done = true;
            return true;
        }
        public function close() { return $this->free(); }
        public function __destruct() { $this->free(); }
        public function __call($m, $a) { throw new Exception('SdfMySqlResult::' . $m . '() 未实现'); }
    }
}

if (!class_exists('SdfMySqlStmt', false)) {
    /**
     * SQLite3Stmt 同形：bindValue(:name / $1 / 1) + execute()。
     * 实现选择「客户端渲染」而非 mysqli 预处理：占位符位置表由 sqlMySqlNormalize 给出，
     * 值经连接的 real_escape_string 转义后原位替换。这样
     *   ① 不受 mysqli bind_param 引用/类型串/重复 execute 限制；
     *   ② 未绑定的参数按 NULL 渲染，与 SQLite3 行为一致；
     *   ③ 重绑重跑零成本（while 循环里反复 bind+execute 的写法全仓都有）。
     * 安全前提：连接已 SET NAMES utf8mb4（否则多字节注入），构造函数里强制设置。
     */
    class SdfMySqlStmt {
        private $db;
        private $sql;      // 归一后的 MySQL 语句
        private $map;      // ['named'=>..., 'count'=>...]
        private $vals = array();
        private $types = array();

        public function __construct($db, $sql, $map) {
            $this->db = $db; $this->sql = $sql; $this->map = $map;
        }

        /** SQLite 的参数序号是 1 基的；命名参数按其在语句里首次出现的位置编号。 */
        private function resolve($key) {
            if (is_int($key) || (is_string($key) && ctype_digit($key))) {
                $p = (int)$key - 1;
                if ($p < 0 || $p >= (int)$this->map['count']) return null;
                return array($p);
            }
            $k = (string)$key;
            if (isset($this->map['named'][$k])) return $this->map['named'][$k];
            if (strncmp($k, ':', 1) === 0 && isset($this->map['named'][':' . substr($k, 1)])) {
                return $this->map['named'][':' . substr($k, 1)];
            }
            if (isset($this->map['named'][':' . $k])) return $this->map['named'][':' . $k];
            return null;
        }

        public function bindValue($key, $value, $type = SQLITE3_TEXT) {
            $p = $this->resolve($key);
            if ($p === null) return false;
            foreach ($p as $i) { $this->vals[$i] = $value; $this->types[$i] = $type; }
            return true;
        }

        public function bindParam($key, &$ref, $type = SQLITE3_TEXT) {
            return $this->bindValue($key, $ref, $type);   // 渲染式实现里按值取即可
        }

        public function bind($key, $value, $type = SQLITE3_TEXT) { return $this->bindValue($key, $value, $type); }

        public function execute() { return $this->db->stmtExecute($this->sql, $this->vals, $this->types); }

        public function close() { $this->vals = array(); $this->types = array(); return true; }
        public function reset() { $this->vals = array(); $this->types = array(); return true; }
        public function finalize() { return $this->close(); }
        public function __destruct() { $this->close(); }
        public function __call($m, $a) { throw new Exception('SdfMySqlStmt::' . $m . '() 未实现'); }
    }
}

if (!class_exists('SdfMySqlDb', false)) {
    /**
     * mysqli 封装，对外暴露 SQLite3 同形 API：
     *   exec / query / prepare / querySingle / lastInsertRowID / changes / escapeString /
     *   busyTimeout / enableExceptions / lastErrorMsg / close
     * 错误模型对齐 SQLite3：enableExceptions(true)（dbOpen 的默认）时抛异常，false 时返回 false。
     * close() 刻意做成 no-op —— 连接由 dbOpen 的 static 缓存共享，业务代码习惯性 close 不该把它掐断。
     */
    class SdfMySqlDb {
        private $m;
        private $exc = true;
        private $lastError = '';
        private $lastErrno = 0;
        private $opt = array('textDefaultMode' => 'expr');
        private $colCache = array();
        private $colLoaded = false;
        private $side = '';

        public function __construct($cfg, $side = '') {
            $this->side = $side;
            if (!function_exists('mysqli_connect')) {
                throw new Exception('mysqli 扩展未安装，无法使用 MySQL 模式');
            }
            if (function_exists('mysqli_report')) { mysqli_report(MYSQLI_REPORT_OFF); }
            $port = isset($cfg['port']) ? (int)$cfg['port'] : 3306;
            $this->m = @mysqli_connect($cfg['host'], $cfg['user'], $cfg['pass'], $cfg['name'], $port);
            if (!$this->m) {
                throw new Exception('MySQL 连接失败[' . $cfg['host'] . ':' . $port . ']: ' . mysqli_connect_error());
            }
            mysqli_set_charset($this->m, 'utf8mb4');   // ★ 必须在 real_escape_string 之前，否则转义不安全

            // ★ 让 fetch 出来的整型/浮点列直接以 PHP int/float 返回，而不是字符串。
            //   不开的话 SELECT COUNT(*) / CAST(x AS SIGNED) 全是 string，业务代码里
            //   `(int)$n` 之外的用法（如 $x === 1、array_sum、json 数字输出）会静默走字符串语义，
            //   与 SQLite3 原生（返回 int）不一致 —— 这是跨库切换最容易踩的暗坑。
            if (defined('MYSQLI_OPT_INT_AND_FLOAT_NATIVE')) {
                @mysqli_options($this->m, MYSQLI_OPT_INT_AND_FLOAT_NATIVE, true);
            }

            // 语义对齐 SQLite：
            //   PIPES_AS_CONCAT      —— || 变字符串拼接（SQL 内 14 处，逐处翻 CONCAT 风险更大）
            //   去掉 ONLY_FULL_GROUP_BY —— SQLite 允许 SELECT 非聚合列
            //   保留 STRICT_TRANS_TABLES —— 类型/非空错误像 SQLite 一样当场炸，不静默截断
            @mysqli_query($this->m, "SET SESSION sql_mode = 'STRICT_TRANS_TABLES,PIPES_AS_CONCAT'");
            @mysqli_query($this->m, "SET NAMES utf8mb4");
            @mysqli_query($this->m, 'SET SESSION time_zone = \'+00:00\'');   // 与 SQLite 的 UTC 'now' 对齐

            $this->detectVersion();
        }

        /** 判定 TEXT 默认值的写法：MySQL≥8.0.13 用括号表达式 / MariaDB≥10.2 原样 / 更老的退化 VARCHAR。 */
        private function detectVersion() {
            $mode = 'none';
            $r = @mysqli_query($this->m, 'SELECT VERSION()');
            if ($r) {
                $row = $r->fetch_row(); $r->free();
                $v = isset($row[0]) ? (string)$row[0] : '';
                if ($v !== '') {
                    if (stripos($v, 'mariadb') !== false) {
                        if (preg_match('/(\d+)\.(\d+)\.(\d+)/', $v, $mm)) {
                            $mode = ((int)$mm[1] > 10 || ((int)$mm[1] === 10 && (int)$mm[2] >= 2)) ? 'plain' : 'none';
                        } else { $mode = 'plain'; }
                    } elseif (preg_match('/(\d+)\.(\d+)\.(\d+)/', $v, $mm)) {
                        $big = (int)$mm[1] * 10000 + (int)$mm[2] * 100 + (int)$mm[3];
                        $mode = ($big >= 80013) ? 'expr' : 'none';
                    }
                }
            }
            $this->opt['textDefaultMode'] = $mode;
        }

        // ---------------- 方言层 ----------------

        private function translate($sql) { return sqlDialectMySql($sql, $this->opt); }

        private function showCreateSql($t) {
            $name = substr($t, strlen(SDF_SQL_SHOWCREATE));
            return 'SHOW CREATE TABLE `' . str_replace('`', '', $name) . '`';
        }

        /**
         * SHOW CREATE TABLE 的行（name/type/sql 三列）按原 sqlite_master 查询的 SELECT 投影裁剪。
         * 例：SELECT sql FROM sqlite_master WHERE ... → 只留 ['sql'=>...]，
         *     否则 querySingle 取首行首列会错拿到表名。解析不了表达式投影时保底给全列。
         */
        private function projectShowCreate($origSql, $rows) {
            if (!preg_match('/\bSELECT\s+([\s\S]*?)\s+FROM\s+sqlite_master\b/i', (string)$origSql, $m)) return $rows;
            $list = trim($m[1]);
            if ($list === '' || $list === '*' ) return $rows;
            if (preg_match('/^\s*(COUNT|SUM|MAX|MIN|AVG|GROUP_CONCAT)\s*\(/i', $list)) return $rows;
            $keep = array();
            foreach (sqlMySqlSplitTop($list) as $c) {
                $c = trim($c);
                if (!preg_match('/^([`\"]?)(\w+)\1(?:\s+AS\s+([`\"]?\w+[`\"]?))?$/i', $c, $mm)) return $rows;
                $alias = (isset($mm[3]) && $mm[3] !== '') ? trim($mm[3], '`"') : $mm[2];
                $keep[] = array(strtolower($mm[2]), $alias);
            }
            $out = array();
            foreach ($rows as $row) {
                $o = array();
                foreach ($keep as $ka) { $o[$ka[1]] = array_key_exists($ka[0], $row) ? $row[$ka[0]] : null; }
                $out[] = $o;
            }
            return $out;
        }

        /**
         * CREATE INDEX 的长列必须带前缀长度（否则 1071）。列元数据从 information_schema 查。
         *
         * ★ 判定不能只看"是不是 TEXT"：SQLite 的 TEXT DEFAULT '' 在 MySQL 侧会被翻成
         *   VARCHAR(500)，utf8mb4 下两列 500×4×2=4000 字节 > InnoDB 3072 索引键上限
         *   （test12 实测 #1071 刷 9459 条，web_item_sn 的联合索引始终建不出来，查询退化全表扫描）。
         *   故改为估算索引字节预算：装得下就保留全列索引（语义最优），
         *   装不下或撞上必须前缀的类型（TEXT/BLOB/JSON/GEOMETRY/SET/ENUM）才均分预算加前缀。
         */
        private function applyIndexPrefix($sql) {
            if (!preg_match('/^CREATE\s+(UNIQUE\s+)?INDEX\s+([`\"]?\w+[`\"]?)\s+ON\s+([`\"]?\w+[`\"]?)\s*\(([\s\S]*?)\)\s*;?\s*$/i', $sql, $m)) {
                return $sql;
            }
            $uniq = $m[1]; $idx = $m[2]; $tbl = trim($m[3], '`"'); $cols = $m[4];
            $items = array();
            foreach (sqlMySqlSplitTop($cols) as $c) {
                $c = trim($c);
                if (!preg_match('/^([`\"]?)(\w+)\1\s*((?:COLLATE\s+\S+\s*)?(?:ASC|DESC)?)$/i', $c, $mm)) {
                    $items[] = array('raw' => $c, 'col' => null, 'dir' => '');
                } else {
                    $items[] = array('raw' => null, 'col' => $mm[2], 'dir' => $mm[3]);
                }
            }
            // InnoDB DYNAMIC 行格式的索引键上限 3072 字节，变长列每列另计 2 字节长度前缀
            $budget = 3072;
            $n = 0; $est = 0; $must = false;
            foreach ($items as $it) {
                if ($it['col'] === null) continue;   // 解析不了的原样带过，不计入
                $n++;
                $info = $this->colIndexInfo($tbl, $it['col']);
                if ($info === null) continue;
                if ($info['mustPrefix']) $must = true;
                $est += $info['maxBytes'] * max(1, (int)$info['maxLen']) + 2;
            }
            if (!$must && $est <= $budget) return $sql;   // 装得下：保留全列索引，不加前缀

            // 均分预算；191 是 utf8mb4 下兼容 767 老限制的安全值（191×4+2=766），多列也远在预算内
            $share = ($n > 0) ? (int)floor(($budget / $n - 2) / 4) : 191;
            if ($share < 1) $share = 1;
            if ($share > 191) $share = 191;
            $out = array();
            foreach ($items as $it) {
                if ($it['col'] === null) { $out[] = $it['raw']; continue; }
                $info = $this->colIndexInfo($tbl, $it['col']);
                if ($info !== null && $info['varLen']) {
                    $len = $share;
                    if ((int)$info['maxLen'] > 0 && (int)$info['maxLen'] < $len) $len = (int)$info['maxLen'];
                    $out[] = '`' . $it['col'] . '`(' . $len . ')' . ($it['dir'] !== '' ? ' ' . $it['dir'] : '');
                } else {
                    $out[] = '`' . $it['col'] . '`' . ($it['dir'] !== '' ? ' ' . $it['dir'] : '');
                }
            }
            return 'CREATE ' . ($uniq !== '' ? 'UNIQUE ' : '') . 'INDEX ' . $idx . ' ON `' . $tbl . '` (' . implode(',', $out) . ')';
        }

        /** 单列的索引预算信息：maxBytes=每字符最大字节 / maxLen=最大字符数 / varLen=可加前缀 / mustPrefix=必须前缀 */
        private function colIndexInfo($table, $col) {
            $c = $this->colMeta($table, $col);
            if ($c === null) return null;
            $dt = $c['type'];
            $mustPrefixTypes = array('text', 'tinytext', 'mediumtext', 'longtext', 'blob', 'tinyblob',
                                     'mediumblob', 'longblob', 'json', 'geometry', 'set', 'enum');
            $varLenTypes = array('char', 'varchar', 'binary', 'varbinary', 'enum', 'set');
            $info = array(
                'type' => $dt,
                'maxBytes' => 4,   // 未知字符集按 utf8mb4 保守估
                'maxLen' => (int)$c['maxLen'],
                'varLen' => false,
                'mustPrefix' => false,
            );
            $cs = strtolower((string)$c['charset']);
            if ($cs === 'utf8mb4' || $cs === 'utf16' || $cs === 'utf16le' || $cs === 'utf32') $info['maxBytes'] = 4;
            elseif ($cs === 'utf8') $info['maxBytes'] = 3;
            elseif ($cs === 'latin1' || $cs === 'ascii' || $cs === 'binary') $info['maxBytes'] = 1;
            if (in_array($dt, $mustPrefixTypes, true)) {
                if ($info['maxLen'] <= 0) $info['maxLen'] = 500;
                $info['varLen'] = true;
                $info['mustPrefix'] = !in_array($dt, $varLenTypes, true);
            } elseif (in_array($dt, $varLenTypes, true)) {
                $info['varLen'] = true;
                if ($info['maxLen'] <= 0) $info['maxLen'] = 500;
            } elseif ($dt === 'decimal' || $dt === 'numeric') {
                $info['maxLen'] = 20;   // 定宽，按 20 字节保守估
            } elseif ($info['maxLen'] <= 0) {
                $info['maxLen'] = 8;    // 整型/日期等定宽列
            }
            return $info;
        }

        /** 列元数据（type/charset/maxLen），查不到返回 null。 */
        private function colMeta($table, $col) {
            if (!$this->colLoaded) $this->loadColCache();
            $k = strtolower($table) . '.' . strtolower($col);
            return isset($this->colCache[$k]) ? $this->colCache[$k] : null;
        }

        /** 兼容旧调用点：只关心 DATA_TYPE。 */
        private function colDataType($table, $col) {
            $c = $this->colMeta($table, $col);
            return $c === null ? null : $c['type'];
        }

        private function loadColCache() {
            $this->colCache = array();
            $this->colLoaded = true;
            $r = @mysqli_query($this->m, 'SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_SET_NAME,'
                . ' CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()');
            if ($r) {
                while ($row = $r->fetch_assoc()) {
                    $this->colCache[strtolower($row['TABLE_NAME']) . '.' . strtolower($row['COLUMN_NAME'])] = array(
                        'type' => strtolower((string)$row['DATA_TYPE']),
                        'charset' => (string)$row['CHARACTER_SET_NAME'],
                        'maxLen' => (int)$row['CHARACTER_MAXIMUM_LENGTH'],
                    );
                }
                $r->free();
            }
        }

        private function invalidateColCache() { $this->colLoaded = false; $this->colCache = array(); }

        private function noteDdl($orig) {
            if (preg_match('/^\s*(CREATE\s+TABLE|ALTER\s+TABLE|DROP\s+TABLE|RENAME\s+TABLE)\b/i', (string)$orig)) {
                $this->invalidateColCache();
            }
        }

        /**
         * DDL（CREATE/ALTER/DROP INDEX|TABLE）失败时**不抛异常**，只记日志返回 false。
         * 理由：initTables 是一个事务里几十条建表语句，SQLite 侧靠 `IF NOT EXISTS` 天然幂等；
         * MySQL 侧一旦某条 DDL 报错就抛异常，会把整批建表从中断在半路（实测只建成 19/66 张，
         * 后面的表全部连带失败）。降级后：能建的全建好，坏的单独进日志，代码侧 try/catch 语义不变。
         */
        private function isDdl($sql) {
            return (bool)preg_match('/^\s*(CREATE|ALTER|DROP|RENAME|TRUNCATE)\b/i', (string)$sql);
        }

        /** 统一的错误出口：吞掉"可重试/已存在"类错误，其余按 enableExceptions 决定抛或返回 false。 */
        private function fail($where, $orig) {
            $this->lastErrno = $this->m ? (int)$this->m->errno : 0;
            $this->lastError = $this->m ? (string)$this->m->error : 'no connection';
            $errno = $this->lastErrno;
            $o = (string)$orig;
            // 我们把 CREATE INDEX 的 IF NOT EXISTS 摘掉了（MySQL 不认这个子句），重复即视为成功
            if ($errno === 1061 && preg_match('/^\s*CREATE\s+(?:UNIQUE\s+)?INDEX\b/i', $o)) {
                return true;
            }
            // ALTER TABLE ... ADD COLUMN 的重复列（1060）与重复键名（1061）：源码里本就是 try/catch 兜着，
            // 这里直接当成功，否则每次请求都会往 debug.log 灌一屏噪声
            if (($errno === 1060 || $errno === 1061) && preg_match('/^\s*ALTER\s+TABLE\b/i', $o)) {
                return true;
            }
            $msg = 'MySQL ' . $where . ' 失败 #' . $errno . ': ' . $this->lastError
                 . ' | SQL: ' . preg_replace('/\s+/', ' ', substr($o, 0, 300));
            debugLog('mysql[' . $this->side . ']', array('err' => $msg));
            // DDL 永不抛异常：一条建表失败不该拖垮同批剩下的表
            if ($this->exc && !$this->isDdl($o)) { throw new Exception($msg); }
            return false;
        }

        // ---------------- SQLite3 同形 API ----------------

        public function exec($sql) {
            if (!($this->m instanceof mysqli)) return false;
            $t = $this->translate($sql);
            if ($t === SDF_SQL_NOOP) return true;
            if (strncmp($t, SDF_SQL_SHOWCREATE, strlen(SDF_SQL_SHOWCREATE)) === 0) { $t = $this->showCreateSql($t); }
            else { $t = $this->applyIndexPrefix($t); }
            $r = @mysqli_query($this->m, $t);
            if ($r === false) return $this->fail('exec', $sql);
            if ($r instanceof mysqli_result) $r->free();
            $this->noteDdl($sql);
            return true;
        }

        public function query($sql) {
            if (!($this->m instanceof mysqli)) return false;
            $t = $this->translate($sql);
            if ($t === SDF_SQL_NOOP) return new SdfMySqlResult(null);
            if (strncmp($t, SDF_SQL_SHOWCREATE, strlen(SDF_SQL_SHOWCREATE)) === 0) {
                // sqlite_master 取建表文本的替身：SHOW CREATE TABLE 的列名要改写回 name/sql，
                // 且必须按原查询的投影裁剪（SELECT sql ... 的消费点拿首行首列，多给 name 会错位）
                $r = @mysqli_query($this->m, $this->showCreateSql($t));
                if ($r === false) {
                    if ((int)$this->m->errno === 1146) return new SdfMySqlResult(null, array());   // 表不存在 = SQLite 的空结果
                    return $this->fail('query', $sql);
                }
                $rows = array();
                if ($r instanceof mysqli_result) {
                    while ($row = $r->fetch_assoc()) {
                        $rows[] = array(
                            'name' => isset($row['Table']) ? $row['Table'] : null,
                            'type' => 'table',
                            'sql'  => isset($row['Create Table']) ? $row['Create Table'] : null,
                        );
                    }
                    $r->free();
                }
                $rows = $this->projectShowCreate($sql, $rows);
                $this->noteDdl($sql);
                return new SdfMySqlResult(null, $rows);
            }
            $t = $this->applyIndexPrefix($t);
            $r = @mysqli_query($this->m, $t);
            if ($r === false) return $this->fail('query', $sql);
            $this->noteDdl($sql);
            if (!($r instanceof mysqli_result)) return new SdfMySqlResult(null);   // UPDATE/INSERT 走 query() 也返回可 fetch 的空结果
            return new SdfMySqlResult($r);
        }

        public function prepare($sql) {
            // ★ 占位符位置表必须从**原始 SQL** 解析。sqlDialectMySql() 的最后一步已把
            //   :name / $1 全归一成 ?，若对归一后的语句再解析，named 表会是空的
            //   （只剩 ? 的计数）→ 命名绑定全部找不到位置 → 渲染成 NULL（1048 之类）。
            $ph = sqlMySqlNormalize($sql);
            $t = $this->translate($sql);
            if ($t === SDF_SQL_NOOP) { return new SdfMySqlStmt($this, $t, array('named' => array(), 'count' => 0)); }
            if (strncmp($t, SDF_SQL_SHOWCREATE, strlen(SDF_SQL_SHOWCREATE)) === 0) {
                $t = $this->showCreateSql($t);   // prepare 里取建表文本：先换成可执行语句再归一
            }
            // 翻译理论上不改变占位符数量；真对不上时以翻译后的实际数量为准（保底不越界）
            $cnt = substr_count($this->stripLiterals($t), '?');
            if ((int)$ph[1]['count'] !== $cnt) {
                debugLog('mysql[' . $this->side . ']', array('warn' => '占位符数量与原文不一致，'
                    . '原句 ' . (int)$ph[1]['count'] . ' 个 / 译后 ' . $cnt . ' 个',
                    'sql' => substr((string)$sql, 0, 200)));
            }
            $ph[1]['count'] = $cnt;
            return new SdfMySqlStmt($this, $t, $ph[1]);
        }

        /** 统计字符串字面量之外的 ? 个数（字面量里的问号不算占位符）。 */
        private function stripLiterals($sql) {
            $out = '';
            foreach (sqlMySqlSplitSegments((string)$sql) as $seg) { if (!$seg[0]) $out .= $seg[1]; }
            return $out;
        }

        /** 与 SQLite3::querySingle 同义：$assoc=false 取首行首列，true 取首行数组。 */
        public function querySingle($sql, $assoc = false) {
            if (!($this->m instanceof mysqli)) return null;
            $r = $this->query($sql);
            if ($r === false || $r === null) return null;
            $row = $r->fetchArray($assoc ? SQLITE3_ASSOC : SQLITE3_NUM);
            $r->finalize();
            if ($row === false) return null;
            if ($assoc) return $row;
            $v = reset($row);
            return $v === false ? null : $v;
        }

        /** SdfMySqlStmt::execute() 的落点。 */
        public function stmtExecute($mysqlSql, $vals, $types) {
            if (!($this->m instanceof mysqli)) return false;
            if ($mysqlSql === SDF_SQL_NOOP) return new SdfMySqlResult(null);
            $esc = function ($s) { return $this->m ? $this->m->real_escape_string($s) : addslashes($s); };
            $t = sqlMySqlRender($mysqlSql, $vals, $types, $esc);
            $r = @mysqli_query($this->m, $t);
            if ($r === false) return $this->fail('execute', $t);
            $this->noteDdl($t);
            if (!($r instanceof mysqli_result)) return new SdfMySqlResult(null);
            return new SdfMySqlResult($r);
        }

        public function lastInsertRowID() { return ($this->m instanceof mysqli) ? (int)$this->m->insert_id : 0; }
        public function changes() {
            if (!($this->m instanceof mysqli)) return 0;
            $n = (int)$this->m->affected_rows;
            return $n < 0 ? 0 : $n;
        }
        public function escapeString($v) { return ($this->m instanceof mysqli) ? $this->m->real_escape_string((string)$v) : addslashes((string)$v); }
        public function lastErrorMsg() { return $this->lastError; }
        public function lastErrorCode() { return $this->lastErrno; }

        /** busy_timeout → InnoDB 行锁等待（秒）。8000ms/15000ms 这类调用语句在 MySQL 上无害。 */
        public function busyTimeout($ms) {
            if (!($this->m instanceof mysqli)) return false;
            $sec = (int)ceil(((int)$ms) / 1000);
            if ($sec < 1) $sec = 1;
            if ($sec > 3600) $sec = 3600;   // MySQL innodb_lock_wait_timeout 上限 1024s，超了会 1232，这里先收敛
            if ($sec > 1024) $sec = 1024;
            $ok = @mysqli_query($this->m, 'SET SESSION innodb_lock_wait_timeout = ' . $sec);
            return $ok !== false;
        }

        public function enableExceptions($f = true) { $this->exc = (bool)$f; return $this->exc; }

        /** 刻意 no-op：连接由 dbOpen() 的 static 缓存共享，业务代码习惯性 close() 不该掐断它。 */
        public function close() { return true; }

        /** 供诊断脚本查看底层连接。 */
        public function getMysqli() { return $this->m; }

        public function __call($m, $a) { throw new Exception('SdfMySqlDb::' . $m . '() 未实现'); }
    }
}
// ===== 数据库初始化 =====
/**
 * 打开某一侧的底层连接（SQLite 文件或 MySQL 适配器，由 sql.md 运行模式决定）。
 * ★ 建连前先跑一次 dbSplitMigrateIfNeeded()：迁移必须发生在 Java.db 被创建之前，
 *   否则空库一建出来就被判定"已迁移"，存量数据永远搬不过去。
 */
function dbOpen($side) {
    static $cache = array();
    if (isset($cache[$side])) return $cache[$side];

    dbSplitMigrateIfNeeded();

    // ★ MySQL 模式（2026-10-09）：mode=mysql 且该侧地址齐全 → 走适配层；
    //   mysqli 缺失 / 连接失败 / 任何异常都软回退 SQLite（软依赖，全站不感知）。
    $mcfg = sqlMdDbConfig();
    if (isset($mcfg['mode']) && $mcfg['mode'] === 'mysql'
        && isset($mcfg[$side]) && is_array($mcfg[$side])) {
        // ★ 数据安全护栏（2026-10-10）：先连上、再过体检，最后才干活
        $dfVerdict = 'skip';   // skip = 连接都没建成（走 SQLite 兜底，与改造前一致）
        $mdb = null;
        try {
            $mdb = new SdfMySqlDb($mcfg[$side], $side);
            $mdb->enableExceptions(true);
            $mdb->busyTimeout(8000);
            try { $mdb->exec('ROLLBACK'); } catch (\Throwable $_) {}   // 清上一请求残留事务
            // 体检放在 initTables 之前：指错库时绝不往里建表/迁移
            try { $dfVerdict = dfGuardCheck($mdb, $mcfg[$side], $side); } catch (\Throwable $e) {
                debugLog('dbOpen[' . $side . ']: 数据护栏体检异常（按 ok 放行）: ' . $e->getMessage());
                $dfVerdict = 'ok';
            }
        } catch (\Throwable $e) {
            debugLog('dbOpen[' . $side . ']: MySQL 连接失败，回退 SQLite: ' . $e->getMessage());
        }
        if ($mdb !== null && $dfVerdict !== 'skip') {
            if ($dfVerdict === 'block') {
                // 数据安全优先：sql.md 指错库/指错机 → 硬失败，绝不软回退 SQLite
                // （软回退等于把数据写进另一个库，比停机更危险）
                throw new dfGuardBlockException(dfGuardBlockReason($side));
            }
            try { initTables($mdb, $side); } catch (\Throwable $e) {
                debugLog('dbOpen[' . $side . ']: initTables失败: ' . $e->getMessage());
            }
            try { migrateDatabase($mdb, $side); } catch (\Throwable $e) {
                debugLog('dbOpen[' . $side . ']: migrateDatabase失败: ' . $e->getMessage());
            }
            if ($dfVerdict === 'recover') {
                // 被清空的自愈：补表结构 + 空表从最近备份补推 SQL 数据，然后放行 Java 握手
                try { dfGuardRecover($mdb, $side); } catch (\Throwable $e) {
                    debugLog('dbOpen[' . $side . ']: 自愈执行失败: ' . $e->getMessage());
                }
            }
            $cache[$side] = $mdb;
            return $mdb;
        }
    }

    $file  = str_replace('\\', '/', sqlDbFile($side));
    // ★ 切分未完成期间，java 侧共用 web.db（避免建出空 Java.db 让存量数据"消失"）
    if ($side === 'java' && !empty($GLOBALS['SDF_SPLIT_FALLBACK_WEB'])) {
        $file = str_replace('\\', '/', sqlDbFile('web'));
    }
    $dbDir = dirname($file);
    if (!is_dir($dbDir)) { @mkdir($dbDir, 0755, true); }

    // 修复文件/目录/WAL 权限，防止 "attempt to write a readonly database"
    if (file_exists($file) && !is_writable($file)) {
        @chmod($file, 0666);
        if (!is_writable($file)) { debugLog('dbOpen[' . $side . ']: 数据库文件不可写: ' . $file); }
    }
    if (is_dir($dbDir) && !is_writable($dbDir)) { @chmod($dbDir, 0777); }
    foreach (array('-wal', '-shm') as $suffix) {
        $sideFile = $file . $suffix;
        if (file_exists($sideFile) && !is_writable($sideFile)) { @chmod($sideFile, 0666); }
    }

    $db = new SQLite3($file);
    $db->enableExceptions(true);
    $db->exec('PRAGMA journal_mode=WAL');
    $db->exec('PRAGMA busy_timeout=8000');    // 8秒等待（原60秒太长，Java轮询锁时PHP会卡住近1分钟）
    $db->exec('PRAGMA synchronous=NORMAL');
    $db->exec('PRAGMA cache_size=-64000');    // 64MB 缓存
    $db->exec('PRAGMA wal_autocheckpoint=100');

    // initTables/migrateDatabase 加 try-catch，防止初始化写入失败导致整个请求 500
    try {
        initTables($db, $side);
    } catch (\Throwable $e) {
        debugLog('dbOpen[' . $side . ']: initTables失败（可能数据库只读）: ' . $e->getMessage());
    }
    try {
        migrateDatabase($db, $side);
    } catch (\Throwable $e) {
        debugLog('dbOpen[' . $side . ']: migrateDatabase失败: ' . $e->getMessage());
    }

    // 安全网：仅在首次建连时清掉上一次请求可能残留的未提交事务
    $rbKey = 'rb:' . $side;
    if (empty($cache[$rbKey])) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $_) {}
        $cache[$rbKey] = true;
    }

    $cache[$side] = $db;
    return $db;
}

/**
 * 全站唯一的数据库入口。
 *   getDB()        → 按表名自动路由的外观（Java 组表走 Java.db，其余走 web.db）
 *   getDB('java')  → 直接拿 Java 专属连接
 *   getDB('web')   → 直接拿 PHP 专属连接
 * 调用方无需感知切分：224 处无参调用行为不变，只是语句被派到了正确的库。
 */
function getDB($side = null) {
    if ($side === 'java' || $side === 'web') return dbOpen($side);
    static $router = null;
    if ($router === null) { $router = new SdfDbRouter(); }
    return $router;
}

// ===== 收银台订单独立库 =====
// 订单是 PHP 收银台自己的数据，与 web.db（Java 通过 sync.php 高频写入）完全无关。
// 拆到独立 SQLite 文件后，Java 永不触碰本库，根除因 web.db 文件级锁竞争导致的订单读超时。
function getOrdersDB() {
    static $db = null;
    if ($db === null) {
        $ordersDbPath = defined('ORDERS_DB_PATH') ? ORDERS_DB_PATH : (__DIR__ . '/db/orders.db');
        $dbDir = dirname($ordersDbPath);
        if (!is_dir($dbDir)) {
            @mkdir($dbDir, 0755, true);
        }

        // 权限修复（与 getDB 一致，防止 "attempt to write a readonly database"）
        if (file_exists($ordersDbPath) && !is_writable($ordersDbPath)) {
            @chmod($ordersDbPath, 0666);
        }
        if (is_dir($dbDir) && !is_writable($dbDir)) {
            @chmod($dbDir, 0777);
        }
        foreach (['-wal', '-shm'] as $suffix) {
            $sideFile = $ordersDbPath . $suffix;
            if (file_exists($sideFile) && !is_writable($sideFile)) {
                @chmod($sideFile, 0666);
            }
        }

        $db = new SQLite3($ordersDbPath);
        $db->enableExceptions(true);
        $db->exec('PRAGMA journal_mode=WAL');
        $db->exec('PRAGMA busy_timeout=5000');   // ★ 独立库，Java 不碰，5秒足矣
        $db->exec('PRAGMA synchronous=NORMAL');
        $db->exec('PRAGMA cache_size=-64000');     // 64MB 缓存
        $db->exec('PRAGMA wal_autocheckpoint=100');

        // 建表（仅 PHP 维护）
        $db->exec("CREATE TABLE IF NOT EXISTS cashier_orders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            order_no TEXT NOT NULL UNIQUE,
            operator_type TEXT DEFAULT 'cashier',
            operator_name TEXT DEFAULT '',
            player_name TEXT NOT NULL,
            items_detail TEXT DEFAULT '',
            subtotal INTEGER DEFAULT 0,
            total_price INTEGER DEFAULT 0,
            discount_percent INTEGER DEFAULT 0,
            discount_amount INTEGER DEFAULT 0,
            settlement TEXT DEFAULT '',
            payment_method TEXT DEFAULT 'bond',
            status TEXT DEFAULT 'completed',
            created_at INTEGER DEFAULT 0
        )");
        // ★ 幂等加列：payment_method 已在上方 CREATE TABLE 中声明，此处仅作极旧库兜底补加。
        //   用 try/catch 包裹，避免 "duplicate column name" 异常经 enableExceptions(true) 上抛、
        //   导致 getOrdersDB() 首次调用即失败、订单写不进、订单页查询报错。
        try {
            $db->exec("ALTER TABLE cashier_orders ADD COLUMN payment_method TEXT DEFAULT 'bond'");
        } catch (\Throwable $e) {
            // 列已存在 → 忽略
        }

        // 首次建库时把 web.db 历史订单一次性迁过来（幂等）
        migrateCashierOrdersIfNeeded($db);
    }

    // 安全网：清理可能残留的悬挂事务（与 getDB 一致）
    try { $db->exec('ROLLBACK'); } catch (\Throwable $_) {}

    return $db;
}

/**
 * ★ 历史订单迁移：仅在 orders.db 为空、且 web.db 存在 cashier_orders 历史数据时执行一次。
 *   幂等：orders.db 已有数据则跳过；使用 INSERT OR IGNORE 防止重复导入。
 */
function migrateCashierOrdersIfNeeded($ordersDb) {
    static $done = false;
    if ($done) return;

    // orders.db 已有数据 → 永久跳过迁移
    $existing = 0;
    try {
        $existing = (int)$ordersDb->querySingle("SELECT COUNT(*) FROM cashier_orders");
    } catch (\Throwable $e) {
        return; // 表还没建好，下次请求再试
    }
    if ($existing > 0) { $done = true; return; }

    try {
        $src = getDB();
        // 仅迁移已存在的记录
        $res = $src->query("SELECT order_no, operator_type, operator_name, player_name, items_detail, subtotal, total_price, discount_percent, discount_amount, settlement, payment_method, status, created_at FROM cashier_orders ORDER BY created_at ASC");
        if (!$res) return;
        $count = 0;
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $stmt = $ordersDb->prepare("INSERT OR IGNORE INTO cashier_orders (order_no, operator_type, operator_name, player_name, items_detail, subtotal, total_price, discount_percent, discount_amount, settlement, payment_method, status, created_at) VALUES (:no,:ot,:on,:pn,:det,:sub,:tp,:dp,:da,:st,:pm,:st2,:time)");
            $stmt->bindValue(':no', $row['order_no'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':ot', $row['operator_type'] ?? 'cashier', SQLITE3_TEXT);
            $stmt->bindValue(':on', $row['operator_name'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':pn', $row['player_name'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':det', $row['items_detail'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':sub', (int)($row['subtotal'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':tp', (int)($row['total_price'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':dp', (int)($row['discount_percent'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':da', (int)($row['discount_amount'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':st', $row['settlement'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':pm', $row['payment_method'] ?? 'bond', SQLITE3_TEXT);
            $stmt->bindValue(':st2', $row['status'] ?? 'completed', SQLITE3_TEXT);
            $stmt->bindValue(':time', (int)($row['created_at'] ?? 0), SQLITE3_INTEGER);
            $stmt->execute();
            $count++;
        }
        if ($count > 0) {
            debugLog("migrateCashierOrdersIfNeeded: 从 web.db 迁移 $count 条历史订单到 orders.db");
        }
        $done = true; // 已尝试迁移（成功或无需迁移均标记，避免每次请求重复SELECT）
    } catch (\Throwable $e) {
        debugLog("migrateCashierOrdersIfNeeded 失败（不影响新订单写入，下次请求重试）: " . $e->getMessage());
        // 不设置 $done，下次请求再试（如 web.db 临时锁住）
    }
}

/**
 * ★ 远程修复数据库权限（Java调用）
 */
function fixDbPermissions() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $results = [];

    // ★ 双库切分（2026-10-09）：Java.db 与 web.db 可能在不同目录，两侧各修一遍
    foreach (array('web', 'java') as $side) {
        // MySQL 模式该侧无本地文件，chmod/mkdir 无意义也无从谈起
        $mcfg = sqlMdDbConfig();
        if (isset($mcfg['mode']) && $mcfg['mode'] === 'mysql'
            && isset($mcfg[$side]) && is_array($mcfg[$side])) {
            $results[] = "[$side] MySQL 模式：无本地数据库文件，跳过权限修复";
            continue;
        }
        $dbPath = sqlDbFile($side);
        $dbDir = dirname($dbPath);

        // 修复目录权限
        if (is_dir($dbDir)) {
            $oldPerms = substr(sprintf('%o', fileperms($dbDir)), -4);
            @chmod($dbDir, 0777);
            $newPerms = substr(sprintf('%o', fileperms($dbDir)), -4);
            $results[] = "[$side] 目录 $dbDir: $oldPerms → $newPerms (writable=" . (is_writable($dbDir) ? 'Y' : 'N') . ")";
        } else {
            @mkdir($dbDir, 0777, true);
            $results[] = "[$side] 目录 $dbDir: 已创建";
        }

        // 修复数据库文件权限
        if (file_exists($dbPath)) {
            $oldPerms = substr(sprintf('%o', fileperms($dbPath)), -4);
            @chmod($dbPath, 0666);
            $newPerms = substr(sprintf('%o', fileperms($dbPath)), -4);
            $results[] = "[$side] 数据库 $dbPath: $oldPerms → $newPerms (writable=" . (is_writable($dbPath) ? 'Y' : 'N') . ")";
        } else {
            $results[] = "[$side] 数据库 $dbPath: 不存在（首次访问时自动创建）";
        }

        // 修复WAL/SHM文件权限
        foreach (array('-wal', '-shm') as $suffix) {
            $sideFile = $dbPath . $suffix;
            if (file_exists($sideFile)) {
                $oldPerms = substr(sprintf('%o', fileperms($sideFile)), -4);
                @chmod($sideFile, 0666);
                $newPerms = substr(sprintf('%o', fileperms($sideFile)), -4);
                $results[] = "[$side] WAL文件 $sideFile: $oldPerms → $newPerms";
            }
        }
    }

    // 尝试写入测试
    try {
        $db = getDB();
        $db->exec("CREATE TABLE IF NOT EXISTS _perm_test (id INTEGER)");
        $db->exec("DROP TABLE IF EXISTS _perm_test");
        $results[] = "写入测试: 通过";
    } catch (\Throwable $e) {
        $results[] = "写入测试: 失败 - " . $e->getMessage();
    }

    success(['results' => $results], "权限修复完成，" . count($results) . " 项");
}

/**
 * ★ WAL checkpoint：释放WAL锁，减少database is locked概率
 * 在大量写操作后调用，或在请求结束时调用
 */
function walCheckpoint($db = null) {
    if ($db === null) $db = getDB();
    try {
        // TRUNCATE模式：将WAL内容合并到主库并清空WAL文件
        $db->exec('PRAGMA wal_checkpoint(TRUNCATE)');
    } catch (Exception $e) {
        // checkpoint失败不影响正常流程
    }
}

/**
 * 数据库迁移：为旧数据库添加新字段（通过 ALTER TABLE）
 * 仅在表存在但缺少新字段时执行，且只执行一次（用标记文件）
 */
function migrateDatabase($db, $side = null) {
    if ($side !== null) { $db = new SdfDbSideFilter($db, $side); }
    // 检查是否已迁移（用文件标记，避免每次都做 PRAGMA 查询）
    $migrateLock = sqlDbFile($side === 'java' ? 'java' : 'web') . '.migrated';
    if (file_exists($migrateLock)) {
        return; // 已迁移，直接跳过
    }
    
    // 检查 weblogin_credentials 表是否存在且是否有 temp_password_hash 字段
    try {
        $stmt = $db->prepare("PRAGMA table_info(weblogin_credentials)");
        $result = $stmt->execute();
        $columns = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $columns[] = $row['name'];
        }
        if (in_array('temp_password_hash', $columns)) {
            // 字段已存在，创建标记
            file_put_contents($migrateLock, time());
            return;
        }
        
        debugLog("migrateDatabase: 开始迁移 weblogin_credentials");
        // 使用事务包裹 ALTER TABLE，减少锁时间
        $db->exec('BEGIN TRANSACTION');
        try {
            $db->exec("ALTER TABLE weblogin_credentials ADD COLUMN temp_password_hash TEXT DEFAULT ''");
            $db->exec("ALTER TABLE weblogin_credentials ADD COLUMN temp_pw_expire INTEGER DEFAULT 0");
            $db->exec('COMMIT');
            // 迁移成功，创建标记文件
            file_put_contents($migrateLock, time());
            debugLog("migrateDatabase: 迁移成功，已创建标记文件");
        } catch (Exception $e) {
            $db->exec('ROLLBACK');
            debugLog("migrateDatabase: 迁移失败", ['error' => $e->getMessage()]);
            // 字段可能已存在（其他请求先执行了），也创建标记
            file_put_contents($migrateLock, time());
        }
    } catch (Exception $e) {
        // 表不存在，initTables 会创建，忽略
    }
}

function initTables($db, $side = null) {
    if ($side !== null) { $db = new SdfDbSideFilter($db, $side); }
    $db->exec('BEGIN TRANSACTION');
    try {
        // Token表
        $db->exec("CREATE TABLE IF NOT EXISTS tokens (
            token TEXT PRIMARY KEY,
            player_name TEXT NOT NULL,
            purpose TEXT DEFAULT 'general',
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            used INTEGER DEFAULT 0,
            used_at INTEGER DEFAULT 0
        )");

        // 商品表
        $db->exec("CREATE TABLE IF NOT EXISTS shop_items (
            id TEXT PRIMARY KEY,
            category TEXT NOT NULL,
            display_name TEXT NOT NULL,
            material TEXT NOT NULL,
            buy_price INTEGER DEFAULT 0,
            sell_price INTEGER DEFAULT -1,
            stock INTEGER DEFAULT -1,
            hourly_sales INTEGER DEFAULT 0,
            total_sales INTEGER DEFAULT 0,
            last_sync INTEGER DEFAULT 0,
            admin_stock INTEGER DEFAULT NULL,
            admin_buy_price INTEGER DEFAULT NULL,
            admin_sell_price INTEGER DEFAULT NULL
        )");

        // ★ 兼容升级：给shop_items加admin_stock字段（已存在则跳过）
        try {
            $db->exec("ALTER TABLE shop_items ADD COLUMN admin_stock INTEGER DEFAULT NULL");
        } catch (\Throwable $e) { /* 字段已存在，忽略 */ }
        // ★ 兼容升级：给shop_items加last_modified字段（高频轮询检测改动用）
        try {
            $db->exec("ALTER TABLE shop_items ADD COLUMN last_modified INTEGER DEFAULT 0");
        } catch (\Throwable $e) { /* 字段已存在，忽略 */ }
        // ★ 兼容升级：给shop_items加admin价格覆盖字段（PHP后台修改价格时写入，Java拉取后清除）
        try {
            $db->exec("ALTER TABLE shop_items ADD COLUMN admin_buy_price INTEGER DEFAULT NULL");
        } catch (\Throwable $e) { /* 字段已存在，忽略 */ }
        try {
            $db->exec("ALTER TABLE shop_items ADD COLUMN admin_sell_price INTEGER DEFAULT NULL");
        } catch (\Throwable $e) { /* 字段已存在，忽略 */ }

        // CDK表
        $db->exec("CREATE TABLE IF NOT EXISTS cdk (
            code TEXT PRIMARY KEY,
            amount INTEGER DEFAULT 0,
            type TEXT DEFAULT 'bond',
            used INTEGER DEFAULT 0,
            used_by TEXT DEFAULT '',
            created_at INTEGER DEFAULT 0,
            used_at INTEGER DEFAULT 0
        )");

        // 交易流水表（Web端记录）
        $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            type TEXT NOT NULL,
            amount INTEGER NOT NULL,
            operator TEXT DEFAULT '',
            reason TEXT DEFAULT '',
            detail TEXT DEFAULT '',
            status TEXT DEFAULT 'pending',
            created_at INTEGER DEFAULT 0
        )");

        // 管理员登录会话
        $db->exec("CREATE TABLE IF NOT EXISTS admin_sessions (
            session_id TEXT PRIMARY KEY,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        )");

        // 用户表（插件同步注册数据）
        $db->exec("CREATE TABLE IF NOT EXISTS users (
            player_name TEXT PRIMARY KEY,
            register_time INTEGER DEFAULT 0,
            last_login_time INTEGER DEFAULT 0,
            email TEXT DEFAULT '',
            points INTEGER DEFAULT 0,
            gift_stage INTEGER DEFAULT 0,
            total_online_time INTEGER DEFAULT 0
        )");

        // ===== 风控 / 封禁相关列（安全迁移，避免 ALTER 锁表） =====
        $userCols = [];
        $ci = $db->query("PRAGMA table_info(users)");
        while ($c = $ci->fetchArray(SQLITE3_ASSOC)) {
            $userCols[] = $c['name'];
        }
        $addCols = [
            'last_login_location' => "TEXT DEFAULT ''",
            'last_login_ip'        => "TEXT DEFAULT ''",
            'frozen'               => "INTEGER DEFAULT 0",
            'freeze_token'         => "TEXT DEFAULT ''",
        ];
        foreach ($addCols as $col => $type) {
            if (!in_array($col, $userCols)) {
                $db->exec("ALTER TABLE users ADD COLUMN $col $type");
            }
        }

        // Web登录Token表
        $db->exec("CREATE TABLE IF NOT EXISTS weblogin_tokens (
            player_name TEXT PRIMARY KEY,
            web_token TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            expire_seconds INTEGER DEFAULT 600
        )");

        // 债券缓存表
        $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (
            player_name TEXT PRIMARY KEY,
            amount INTEGER DEFAULT 0,
            updated_at INTEGER DEFAULT 0
        )");

        // ★ Web登录密码凭证表（含临时密码字段，一步到位，避免 ALTER TABLE 导致排他锁）
        $db->exec("CREATE TABLE IF NOT EXISTS weblogin_credentials (
            player_name TEXT PRIMARY KEY,
            password_hash TEXT NOT NULL,
            salt TEXT NOT NULL,
            temp_password_hash TEXT DEFAULT '',
            temp_pw_expire INTEGER DEFAULT 0,
            pushed_at INTEGER NOT NULL
        )");

        // Web会话日志表（记录成功登录，用于快速重连判定）
        $db->exec("CREATE TABLE IF NOT EXISTS web_session_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            ip_address TEXT NOT NULL,
            login_time INTEGER NOT NULL,
            session_token TEXT DEFAULT ''
        )");

        // Web登录验证记录表（玩家在Web端验证成功后，等待进游戏自动登录）
        $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (
            player_name TEXT PRIMARY KEY,
            verified_at INTEGER NOT NULL
        )");

        // 邮件验证码表
        $db->exec("CREATE TABLE IF NOT EXISTS email_codes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            email TEXT NOT NULL,
            code TEXT NOT NULL,
            expire_at INTEGER NOT NULL,
            used INTEGER DEFAULT 0,
            created_at INTEGER NOT NULL
        )");

        // 密码重置Token表
        $db->exec("CREATE TABLE IF NOT EXISTS password_reset_tokens (
            token TEXT PRIMARY KEY,
            player_name TEXT NOT NULL,
            expire_at INTEGER NOT NULL
        )");

        // 密码重置请求表（无邮箱时需要管理员审核）
        $db->exec("CREATE TABLE IF NOT EXISTS password_reset_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            requested_email TEXT DEFAULT '',
            status TEXT DEFAULT 'pending',
            admin_approved INTEGER DEFAULT 0,
            admin_email TEXT DEFAULT '',
            created_at INTEGER NOT NULL,
            processed_at INTEGER DEFAULT 0
        )");

        // 在线玩家表（Java插件同步，这里确保表存在）
        $db->exec("CREATE TABLE IF NOT EXISTS online_players (
            player_name TEXT PRIMARY KEY,
            login_time INTEGER DEFAULT 0
        )");

        // Web登录确认表（插件轮询用）
        $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (
            player_name TEXT PRIMARY KEY,
            confirmed_at INTEGER NOT NULL,
            consumed INTEGER DEFAULT 0
        )");

        // Web登录请求表（Web端提交密码登录请求）
        $db->exec("CREATE TABLE IF NOT EXISTS web_login_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            password TEXT NOT NULL,
            request_time INTEGER NOT NULL,
            status TEXT DEFAULT 'pending',
            result TEXT DEFAULT '',
            result_time INTEGER DEFAULT 0
        )");

        // Web注册请求表（Web端提交注册请求）
        $db->exec("CREATE TABLE IF NOT EXISTS web_register_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            salt TEXT NOT NULL,
            email TEXT DEFAULT '',
            ip_address TEXT DEFAULT '',
            created_at INTEGER NOT NULL,
            status TEXT DEFAULT 'pending',
            processed_at INTEGER DEFAULT 0
        )");

        // ★ 玩家IP变更历史表（admin.php 批量查IP用）
        $db->exec("CREATE TABLE IF NOT EXISTS player_ip_changes (
            player_name TEXT PRIMARY KEY,
            old_ip TEXT DEFAULT '',
            new_ip TEXT NOT NULL,
            changed_at INTEGER NOT NULL,
            synced_at INTEGER DEFAULT 0
        )");

        // ★ IP归属地缓存表（admin.php IP查询用）
        $db->exec("CREATE TABLE IF NOT EXISTS player_ip_locations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ip_address TEXT NOT NULL,
            location TEXT DEFAULT '',
            updated_at INTEGER DEFAULT 0,
            player_name TEXT DEFAULT 'global',
            UNIQUE(ip_address)
        )");

        // ★ 玩家每日登录记录表（用于计算累计在线天数）
        $db->exec("CREATE TABLE IF NOT EXISTS player_daily_logins (
            player_name TEXT NOT NULL,
            login_date TEXT NOT NULL,
            first_login INTEGER DEFAULT 0,
            last_login INTEGER DEFAULT 0,
            PRIMARY KEY (player_name, login_date)
        )");

        // ★ 玩家签到记录表（用于计算累计签到天数）
        $db->exec("CREATE TABLE IF NOT EXISTS player_checkins (
            player_name TEXT NOT NULL,
            checkin_date TEXT NOT NULL,
            checkin_time INTEGER DEFAULT 0,
            PRIMARY KEY (player_name, checkin_date)
        )");

        // ★ 创建索引以提高查询性能
        $db->exec("CREATE INDEX IF NOT EXISTS idx_player_ip_changes_player ON player_ip_changes(player_name)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_player_ip_changes_time ON player_ip_changes(changed_at)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_player_ip_changes_newip ON player_ip_changes(new_ip)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_session_log_player ON web_session_log(player_name)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_session_log_time ON web_session_log(login_time)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_player_ip_locations_ip ON player_ip_locations(ip_address)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_player_ip_locations_loc ON player_ip_locations(location)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_player_daily_logins_player ON player_daily_logins(player_name)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_player_checkins_player ON player_checkins(player_name)");

        // ★ 迁移：确保player_ip_locations表有player_name列
        try {
            $db->exec("ALTER TABLE player_ip_locations ADD COLUMN player_name TEXT DEFAULT 'global'");
        } catch (Exception $e) { /* 列已存在 */ }

        // ★ 服务商同步表（Java端推送service_providers到Web.db）
        $db->exec("CREATE TABLE IF NOT EXISTS web_service_providers (
            player_name TEXT PRIMARY KEY,
            role TEXT DEFAULT 'waiter',
            active INTEGER DEFAULT 1,
            join_time INTEGER DEFAULT 0
        )");

        // ★ 工单系统表
        $db->exec("CREATE TABLE IF NOT EXISTS web_tickets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            status TEXT DEFAULT 'submitted',
            requester TEXT NOT NULL,
            assigned_to TEXT DEFAULT '',
            title TEXT NOT NULL,
            description TEXT DEFAULT '',
            images TEXT DEFAULT '[]',
            reject_reason TEXT DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )");
        $db->exec("CREATE TABLE IF NOT EXISTS web_ticket_replies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ticket_id INTEGER NOT NULL,
            sender TEXT NOT NULL,
            role TEXT DEFAULT 'user',
            message TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_tickets_requester ON web_tickets(requester)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_tickets_status ON web_tickets(status)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_tickets_assigned ON web_tickets(assigned_to)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_ticket_replies_ticket ON web_ticket_replies(ticket_id)");

        // ★ 迁移：确保web_tickets有images列（工单附件图URL数组JSON，2026-10-04 新增）
        try {
            $db->exec("ALTER TABLE web_tickets ADD COLUMN images TEXT DEFAULT '[]'");
        } catch (Exception $e) { /* 列已存在 */ }

        // ★ 用户组相关表（从handler函数中提取到这里统一创建，避免并发请求锁竞争）
        $db->exec("CREATE TABLE IF NOT EXISTS web_user_groups (
            group_name TEXT PRIMARY KEY,
            display_name TEXT DEFAULT '',
            display_color TEXT DEFAULT '§f',
            display_emoji TEXT DEFAULT '',
            priority INTEGER DEFAULT 0,
            land_price_per_sqm INTEGER DEFAULT -1,
            max_lands INTEGER DEFAULT -1,
            max_effects INTEGER DEFAULT 5,
        max_effect_level INTEGER DEFAULT 256,
            home_limit INTEGER DEFAULT 0,
            join_price INTEGER DEFAULT 0,
            auto_renew INTEGER DEFAULT 0,
            renew_price INTEGER DEFAULT 0,
            duration_minutes INTEGER DEFAULT 0,
            default_perms TEXT DEFAULT '{}',
            synced_at INTEGER DEFAULT 0
        )");

        // 用户组成员表
        $db->exec("CREATE TABLE IF NOT EXISTS web_user_group_members (
            player_name TEXT NOT NULL,
            group_name TEXT NOT NULL,
            added_by TEXT DEFAULT 'system',
            added_time INTEGER DEFAULT 0,
            expiry_time INTEGER DEFAULT 0,
            PRIMARY KEY(player_name, group_name)
        )");

        // 管理员变更队列
        $db->exec("CREATE TABLE IF NOT EXISTS web_admin_changes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            change_type TEXT NOT NULL,
            target_id TEXT DEFAULT '',
            target_name TEXT DEFAULT '',
            change_data TEXT DEFAULT '{}',
            created_at INTEGER DEFAULT 0,
            acknowledged INTEGER DEFAULT 0,
            acked_at INTEGER DEFAULT 0,
            status TEXT DEFAULT 'pending'
        )");

        // 待验证玩家表
        $db->exec("CREATE TABLE IF NOT EXISTS pending_player_validations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            request_type TEXT DEFAULT 'general',
            request_data TEXT DEFAULT '{}',
            status TEXT DEFAULT 'pending',
            created_at INTEGER DEFAULT 0,
            validated_at INTEGER DEFAULT 0
        )");

        // 续费请求表
        $db->exec("CREATE TABLE IF NOT EXISTS web_group_renew (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            group_name TEXT NOT NULL,
            renew_price INTEGER DEFAULT 0,
            duration_minutes INTEGER DEFAULT 0,
            req_id TEXT DEFAULT '',
            status TEXT DEFAULT 'pending',
            created_at INTEGER DEFAULT 0,
            processed_at INTEGER DEFAULT 0,
            remark TEXT DEFAULT ''
        )");

        // 过户请求表
        $db->exec("CREATE TABLE IF NOT EXISTS web_land_transfers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            land_name TEXT NOT NULL,
            old_owner TEXT NOT NULL,
            new_owner TEXT NOT NULL,
            status TEXT DEFAULT 'pending',
            created_at INTEGER DEFAULT 0,
            completed_at INTEGER DEFAULT 0,
            expires_at INTEGER DEFAULT 0,
            cooldown_until INTEGER DEFAULT 0
        )");

        // 领地表
        $db->exec("CREATE TABLE IF NOT EXISTS web_area_lands (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            owner TEXT DEFAULT '',
            world TEXT DEFAULT '',
            x1 INTEGER DEFAULT 0,
            z1 INTEGER DEFAULT 0,
            x2 INTEGER DEFAULT 0,
            z2 INTEGER DEFAULT 0,
            y1 INTEGER DEFAULT 0,
            y2 INTEGER DEFAULT 0,
            area_size INTEGER DEFAULT 0,
            created_at INTEGER DEFAULT 0,
            admin_changed INTEGER DEFAULT 0,
            deny_pvp INTEGER DEFAULT 0,
            deny_fall_damage INTEGER DEFAULT 0,
            deny_hunger INTEGER DEFAULT 0,
            deny_all_damage INTEGER DEFAULT 0,
            deny_fire_spread INTEGER DEFAULT 0,
            deny_explosion INTEGER DEFAULT 0,
            deny_mob_grief INTEGER DEFAULT 0,
            deny_block_place INTEGER DEFAULT 0,
            deny_block_break INTEGER DEFAULT 0
        )");

        // 领地权限表（★ 必须含 UNIQUE(land_id, player_name)，否则 ON CONFLICT 不触发导致角色降级）
        $db->exec("CREATE TABLE IF NOT EXISTS web_area_permissions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            land_id INTEGER NOT NULL,
            land_name TEXT DEFAULT '',
            player_name TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'visitor',
            permissions TEXT DEFAULT '',
            granted_at INTEGER DEFAULT 0,
            expires_at INTEGER DEFAULT 0,
            synced_at INTEGER DEFAULT 0,
            UNIQUE(land_id, player_name)
        )");
        // ★ 迁移：旧表可能缺少 UNIQUE 约束和字段，检测并重建
        $pragma = $db->querySingle("SELECT sql FROM sqlite_master WHERE type='table' AND name='web_area_permissions'");
        if ($pragma && strpos($pragma, 'UNIQUE') === false) {
            $db->exec("BEGIN TRANSACTION");
            $db->exec("CREATE TABLE IF NOT EXISTS web_area_permissions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                land_id INTEGER NOT NULL,
                land_name TEXT DEFAULT '',
                player_name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'visitor',
                permissions TEXT DEFAULT '',
                granted_at INTEGER DEFAULT 0,
                expires_at INTEGER DEFAULT 0,
                synced_at INTEGER DEFAULT 0,
                UNIQUE(land_id, player_name)
            )");
            $db->exec("INSERT OR IGNORE INTO web_area_permissions_new (land_id, land_name, player_name, role, permissions, granted_at, synced_at)
                SELECT land_id, '', player_name, role, permissions, granted_at, synced_at FROM web_area_permissions");
            $db->exec("DROP TABLE web_area_permissions");
            $db->exec("ALTER TABLE web_area_permissions_new RENAME TO web_area_permissions");
            $db->exec("COMMIT");
            error_log("[core] web_area_permissions 表已迁移：添加 UNIQUE 约束 + land_name + expires_at 字段");
        }

        // ===== 收银员账号表 =====
        $db->exec("CREATE TABLE IF NOT EXISTS cashiers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            salt TEXT DEFAULT '',
            discount_limit_percent INTEGER DEFAULT 0,
            created_at INTEGER DEFAULT 0,
            created_by TEXT DEFAULT ''
        )");
        // 收银员现金收款权限字段迁移（已存在则静默忽略；注意：enableExceptions(true) 下
        // 必须用 try/catch 兜住，@ 抑制符挡不住 SQLite3Exception，否则会触发 ROLLBACK 并导致
        // 后续建表语句（如 cashier_orders）全部跳过，且每条请求都写一条 duplicate column 日志刷爆磁盘）
        try {
            $db->exec("ALTER TABLE cashiers ADD COLUMN can_cash INTEGER DEFAULT 0");
        } catch (\Throwable $e) { /* 列已存在，忽略 */ }

        // ===== 收银台订单表（代购/收银员操作记录）=====
        $db->exec("CREATE TABLE IF NOT EXISTS cashier_orders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            order_no TEXT NOT NULL UNIQUE,
            operator_type TEXT DEFAULT 'cashier',
            operator_name TEXT DEFAULT '',
            player_name TEXT NOT NULL,
            items_detail TEXT DEFAULT '',
            subtotal INTEGER DEFAULT 0,
            total_price INTEGER DEFAULT 0,
            discount_percent INTEGER DEFAULT 0,
            discount_amount INTEGER DEFAULT 0,
            settlement TEXT DEFAULT '',
            payment_method TEXT DEFAULT 'bond',
            status TEXT DEFAULT 'completed',
            created_at INTEGER DEFAULT 0
        )");
        // 收款模式字段迁移（现金/债券）：payment_method 已在 CREATE TABLE 声明，此处仅极旧库兜底，
        // 用 try/catch 包裹避免 "duplicate column name" 异常中断事务提交。
        try {
            $db->exec("ALTER TABLE cashier_orders ADD COLUMN payment_method TEXT DEFAULT 'bond'");
        } catch (\Throwable $e) {
            // 列已存在 → 忽略
        }


        // ===== SN 防刷系统（PHP 端镜像 + 报失/命令队列）=====
        // 物品 SN 全量镜像：由 Java 定时 push_catalog 同步
        $db->exec("CREATE TABLE IF NOT EXISTS web_item_sn (
            sn TEXT PRIMARY KEY,
            item_type TEXT DEFAULT '',
            owner TEXT DEFAULT '',
            status TEXT DEFAULT 'active',
            issue_time INTEGER DEFAULT 0,
            loc_type TEXT DEFAULT '',
            loc_player TEXT DEFAULT '',
            loc_world TEXT DEFAULT '',
            loc_x INTEGER DEFAULT 0,
            loc_y INTEGER DEFAULT 0,
            loc_z INTEGER DEFAULT 0,
            container_type TEXT DEFAULT '',
            in_land INTEGER DEFAULT 0,
            land_name TEXT DEFAULT '',
            last_seen INTEGER DEFAULT 0,
            cancel_time INTEGER DEFAULT 0,
            lost_count INTEGER DEFAULT 0,
            lost_state TEXT DEFAULT '',
            remark TEXT DEFAULT '',
            bind_reason TEXT DEFAULT '',
            bind_time INTEGER DEFAULT 0,
            updated_at INTEGER DEFAULT 0
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_item_sn_owner ON web_item_sn(owner, item_type)");
        // 脱离自身管控起始时刻（秒；0=仍在本人管辖内）/ 容器是否在本人名下领地
        // ——注销、报失、补办统一三条件判定的数据基础，由 Java push_catalog 同步
        foreach (['detached_at INTEGER DEFAULT 0',
                  'own_chest INTEGER DEFAULT 0'] as $snCustodyCol) {
            try {
                $db->exec("ALTER TABLE web_item_sn ADD COLUMN " . $snCustodyCol);
            } catch (\Throwable $e) {
                // 列已存在 → 忽略
            }
        }

        // SN 操作日志尾部（Java sn_log 镜像）
        $db->exec("CREATE TABLE IF NOT EXISTS web_sn_log (
            id INTEGER PRIMARY KEY,
            sn TEXT DEFAULT '',
            item_type TEXT DEFAULT '',
            action TEXT DEFAULT '',
            player TEXT DEFAULT '',
            detail TEXT DEFAULT '',
            time INTEGER DEFAULT 0
        )");

        // 出入库登记（容器坐标 / 是否在领地 / 领地名）
        $db->exec("CREATE TABLE IF NOT EXISTS web_sn_stock (
            id INTEGER PRIMARY KEY,
            sn TEXT DEFAULT '',
            item_type TEXT DEFAULT '',
            action TEXT DEFAULT '',
            world TEXT DEFAULT '',
            x INTEGER DEFAULT 0,
            y INTEGER DEFAULT 0,
            z INTEGER DEFAULT 0,
            container_type TEXT DEFAULT '',
            in_land INTEGER DEFAULT 0,
            land_name TEXT DEFAULT '',
            player TEXT DEFAULT '',
            time INTEGER DEFAULT 0
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_sn_stock_sn ON web_sn_stock(sn, time)");

        // 手持/背包 SN 快照（每 10~30 秒上报）
        $db->exec("CREATE TABLE IF NOT EXISTS web_sn_held (
            sn TEXT PRIMARY KEY,
            player TEXT DEFAULT '',
            holder TEXT DEFAULT '',
            owner TEXT DEFAULT '',
            item_type TEXT DEFAULT '',
            held INTEGER DEFAULT 0,
            time INTEGER DEFAULT 0
        )");

        // Java -> PHP 事件流（拾取归属不符 / 销毁 / 非法处理）
        $db->exec("CREATE TABLE IF NOT EXISTS web_sn_event (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            action TEXT DEFAULT '',
            sn TEXT DEFAULT '',
            player TEXT DEFAULT '',
            detail TEXT DEFAULT '',
            created_at INTEGER DEFAULT 0
        )");

        // 玩家报失工单
        $db->exec("CREATE TABLE IF NOT EXISTS web_sn_lost (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sn TEXT DEFAULT '',
            player TEXT DEFAULT '',
            reason TEXT DEFAULT '',
            status TEXT DEFAULT 'checking',
            result TEXT DEFAULT '',
            report_count INTEGER DEFAULT 1,
            created_at INTEGER DEFAULT 0,
            handled_at INTEGER DEFAULT 0
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_sn_lost_sn ON web_sn_lost(sn, created_at)");

        // PHP -> Java 命令队列（注销 / 补发 / 报失核查 / 永久绑定）
        // is_force=1 → 管理员强制操作，Java 侧仅本次绕过冷静期
        $db->exec("CREATE TABLE IF NOT EXISTS web_sn_commands (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            cmd TEXT DEFAULT '',
            sn TEXT DEFAULT '',
            player TEXT DEFAULT '',
            item_type TEXT DEFAULT '',
            reason TEXT DEFAULT '',
            is_force INTEGER DEFAULT 0,
            link_type TEXT DEFAULT '',
            link_id INTEGER DEFAULT 0,
            status TEXT DEFAULT 'pending',
            result TEXT DEFAULT '',
            created_at INTEGER DEFAULT 0,
            sent_at INTEGER DEFAULT 0,
            done_at INTEGER DEFAULT 0
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_web_sn_cmd_status ON web_sn_commands(status, id)");

        // 冷静期（注销后 1 小时禁止申领，展示用；真正生效在 Java 侧）
        $db->exec("CREATE TABLE IF NOT EXISTS web_sn_cooldown (
            player TEXT PRIMARY KEY,
            until INTEGER DEFAULT 0
        )");
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        throw $e;
    }
}

// ===== IP归属地格式校验（统一函数，所有脚本共用） =====
// 支持格式：
//   标准：广东省广州市 移动、北京市 海淀区 电信
//   无后缀：湖北武汉 移通、广东广州 联通
//   直辖市：北京市 海淀区、上海 移动
//   自治区：广西南宁 移动、内蒙古呼和浩特市 移动
//   自治州：四川省凉山州 移通
function isValidIpLocationFormat($loc) {
    if (!$loc || $loc === '' || $loc === '-' || $loc === '--' || $loc === 'NULL') return false;
    if ($loc === '内网IP' || $loc === '查询失败' || $loc === '查询中') return false;
    if (stripos($loc, 'China') === 0) return false;
    if (strpos($loc, '中国 - -') === 0 || strpos($loc, '中国 -  -') === 0) return false;
    if (preg_match('/^中国\s*$/u', $loc) || preg_match('/^中国\s+[^\s省市区州盟]+$/u', $loc)) return false;
    if (strpos($loc, ' ') === false) return false;

    // ★ 拆分为"位置部分"和"运营商部分"（以第一个空格分隔）
    $parts = explode(' ', $loc, 2);
    $locationPart = trim($parts[0]);
    $operatorPart = trim($parts[1] ?? '');

    // 位置部分不能为空
    if (empty($locationPart)) return false;

    // 直辖市特殊处理（4个直辖市名称只有2个字）
    $directControlledCities = ['北京', '天津', '上海', '重庆'];
    foreach ($directControlledCities as $city) {
        if ($locationPart === $city || $locationPart === $city . '市') {
            return true; // "北京 移动" 或 "北京市 海淀区" 都有效
        }
    }

    // ★ 有后缀格式验证
    // 城市级后缀：武汉市、凉山州、呼和浩特市、朝阳区、安新县 → 直接有效
    if (preg_match('/(市|州|盟|地区|区|县)$/', $locationPart)) {
        return true;
    }
    // 省级后缀：广东省、湖北省 → 必须同时有城市级后缀（广东省广州市 ✅，湖北省 ❌）
    if (preg_match('/(省|自治区|内蒙古)$/', $locationPart)) {
        // 检查"省"后面是否还有城市名（如"广东省广州市"中的"广州市"）
        if (preg_match('/(省|自治区|内蒙古)(.*?(市|州|盟|地区|区|县))$/', $locationPart)) {
            return true; // "广东省广州市"、"四川省凉山州" → 有效
        }
        return false; // "湖北省"、"广东省" → 只有省份无城市，无效
    }

    // ★ 无后缀格式：如"湖北武汉"、"广东广州"、"四川凉山"
    // 省份名(2-3字) + 城市名(2-4字)，至少4字
    // 匹配：湖北武汉、广东广州、四川凉山、山东济南、河北石家庄
    if (preg_match('/^[\x{4e00}-\x{9fa5}]{2,3}[\x{4e00}-\x{9fa5}]{2,4}$/u', $locationPart) && mb_strlen($locationPart) >= 4) {
        return true;
    }

    return false;
}

// ===== Token操作 =====

/**
 * 验证Token
 * @return array|false  成功返回['player'=>..., 'purpose'=>...]，失败返回false
 */
function validateToken($token) {
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM tokens WHERE token = :token");
    $stmt->bindValue(':token', $token, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        @error_log("[validateToken] Token不存在: " . substr($token, 0, 8) . "...");
        return false;
    }

    $now = time();

    // 检查是否过期
    if ($row['expires_at'] < $now) {
        @error_log("[validateToken] Token已过期: " . substr($token, 0, 8) . "..., expires_at=" . $row['expires_at'] . ", now=" . $now);
        return false;
    }

    // 检查是否已使用
    if ($row['used'] == 1) {
        @error_log("[validateToken] Token已使用: " . substr($token, 0, 8) . "...");
        return false;
    }

    return [
        'player' => $row['player_name'],
        'purpose' => $row['purpose'],
        'created_at' => $row['created_at'],
        'expires_at' => $row['expires_at']
    ];
}

/**
 * 验证weblogin token（游戏内登录token，用于web登录流程）
 */
function validateWebloginToken($webToken) {
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
        $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if (!$row) return false;

        $createdAt = (int)$row['created_at'];
        $expireSeconds = (int)$row['expire_seconds'];
        if (time() - $createdAt > $expireSeconds) return false;

        return [
            'player' => $row['player_name'],
            'purpose' => 'weblogin',
            'created_at' => $createdAt,
            'expires_at' => $createdAt + $expireSeconds
        ];
    } catch (Exception $e) {
        return false;
    }
}

/**
 * 验证Token并标记已使用（一次性）
 */
function validateAndUseToken($token) {
    $info = validateToken($token);
    if (!$info) return false;

    $db = getDB();
    $stmt = $db->prepare("UPDATE tokens SET used = 1, used_at = :now WHERE token = :token");
    $stmt->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':token', $token, SQLITE3_TEXT);
    $stmt->execute();

    return $info;
}

/**
 * 静默验证Token（不报错，返回bool）
 * 同时支持普通token和weblogin token
 */
function validateTokenSilent($token) {
    if (empty($token)) {
        return false;
    }

    // 先尝试普通token
    $info = validateToken($token);
    if ($info) return $info;

    // 再尝试weblogin token
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
        $stmt->bindValue(':token', $token, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if (!$row) {
            return false;
        }

        $createdAt = (int)$row['created_at'];
        $expireSeconds = (int)$row['expire_seconds'];
        if (time() - $createdAt > $expireSeconds) {
            return false;
        }

        return [
            'player' => $row['player_name'],
            'purpose' => 'weblogin',
            'created_at' => $createdAt,
            'expires_at' => $createdAt + $expireSeconds
        ];
    } catch (\Throwable $e) {
        debugLog("[validateTokenSilent] EXCEPTION: " . $e->getMessage());
        return false;
    }
}

/**
 * 创建Token
 */
function createToken($playerName, $purpose = 'general', $expireSeconds = null) {
    if ($expireSeconds === null) $expireSeconds = TOKEN_EXPIRE_SECONDS;
    $token = bin2hex(random_bytes(32));
    $now = time();

    $db = getDB();
    $stmt = $db->prepare("INSERT INTO tokens (token, player_name, purpose, created_at, expires_at) VALUES (:token, :player, :purpose, :created, :expires)");
    $stmt->bindValue(':token', $token, SQLITE3_TEXT);
    $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $stmt->bindValue(':purpose', $purpose, SQLITE3_TEXT);
    $stmt->bindValue(':created', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expires', $now + $expireSeconds, SQLITE3_INTEGER);
    $stmt->execute();

    return $token;
}

/**
 * 清理过期Token
 */
function cleanExpiredTokens() {
    $db = getDB();
    $db->exec("DELETE FROM tokens WHERE expires_at < " . time());
}

// ===== JSON响应 =====

function jsonResponse($data, $code = 200) {
    // ★ 清空所有输出缓冲区（清除Deprecated警告等HTML污染）
    while (ob_get_level() > 0) { ob_end_clean(); }
    header('Content-Type: application/json; charset=utf-8');
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
    exit;
}

function success($data = null, $message = 'ok') {
    jsonResponse(['success' => true, 'message' => $message, 'data' => $data]);
}

function error($message, $code = 400) {
    jsonResponse(['success' => false, 'message' => $message], $code);
}

function preview($data, $message = 'preview mode') {
    jsonResponse(['success' => true, 'preview' => true, 'message' => $message, 'data' => $data]);
}

/**
 * HTML 转义（PHP 模板输出用，防止 XSS）
 * 注意：与前端 JS 里的 esc() 是不同作用域，互不冲突。
 */
function esc($s) {
    return htmlspecialchars($s ?? '', ENT_QUOTES | ENT_HTML5, 'UTF-8');
}

// ===== 请求辅助 =====

function getParam($key, $default = null) {
    if (isset($_GET[$key])) return $_GET[$key];
    if (isset($_POST[$key])) return $_POST[$key];
    // ★ 缓存php://input解析结果（PHP 7.0+中流只能读取一次）
    static $cachedJson = null;
    static $cached = false;
    if (!$cached) {
        $cached = true;
        $raw = file_get_contents('php://input');
        if ($raw) {
            $cachedJson = json_decode($raw, true);
        }
    }
    if ($cachedJson && isset($cachedJson[$key])) return $cachedJson[$key];
    return $default;
}

function requireToken($purpose = 'general') {
    $token = getParam('token');
    if (!$token) {
        error('缺少token参数', 401);
    }
    $info = validateToken($token);
    if (!$info) {
        error('token无效或已过期', 401);
    }
    if ($info['purpose'] !== $purpose && $info['purpose'] !== 'admin' && $info['purpose'] !== 'all') {
        error('token用途不匹配: 需要' . $purpose . '，实际' . $info['purpose'], 403);
    }
    return $info;
}

function requireAdminSession() {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    if (!isset($_SESSION['admin_auth']) || !$_SESSION['admin_auth']) {
        error('未登录管理后台', 401);
    }
    // ★ 紧急加固：安全纪元之前的会话一律作废（部署时同步踢掉入侵者的活会话）
    if (function_exists('secSessionEpochOk') && !secSessionEpochOk()) {
        $_SESSION = array();
        @session_destroy();
        error('登录已失效，请重新登录', 401);
    }
    // ★ 紧急加固：已启用二次验证但本会话未通过 6 位动态码 → 拒绝一切管理操作
    if (function_exists('secSessionNeed2FA') && secSessionNeed2FA()) {
        error('需要二次验证：请先完成6位动态码验证', 403);
    }
}

// ===== 管理员认证 =====

function adminLogin($password) {
    // ★ 首次部署：ADMIN_PASS 还是出厂占位符 = 密码根本没配置，
    //   绝不允许拿占位符当密码登录（配置页会引导用户先设自己的密码）。
    if (function_exists('secAdminPassConfigured') && !secAdminPassConfigured()) {
        return false;
    }
    if ($password !== ADMIN_PASS) {
        return false;
    }
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    $_SESSION['admin_auth'] = true;
    $_SESSION['admin_login_time'] = time();
    // ★ 紧急加固：每次重新登录都必须重新过二次验证
    unset($_SESSION['admin_2fa_ok'], $_SESSION['admin_2fa_ip']);
    return true;
}

function isAdminLoggedIn() {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    if (!isset($_SESSION['admin_auth']) || !$_SESSION['admin_auth']) {
        return false;
    }
    // ★ 紧急加固：安全纪元之前的旧会话视为未登录（只读展示场景，不销毁）
    if (function_exists('secSessionEpochOk') && !secSessionEpochOk()) {
        return false;
    }
    return true;
}

// ===== 收银员认证 =====

/**
 * 收银员登录：校验用户名+密码，成功写入会话
 */
function cashierLogin($username, $password) {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM cashiers WHERE username = :u");
    $stmt->bindValue(':u', $username, SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$row) return false;
    if (!password_verify($password, $row['password_hash'])) return false;
    $_SESSION['cashier_auth'] = true;
    $_SESSION['cashier_id'] = (int)$row['id'];
    $_SESSION['cashier_name'] = $row['username'];
    $_SESSION['cashier_discount_limit'] = (int)$row['discount_limit_percent'];
    $_SESSION['cashier_can_cash'] = (int)($row['can_cash'] ?? 0);
    $_SESSION['cashier_login_time'] = time();
    return true;
}

function isCashierLoggedIn() {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    return isset($_SESSION['cashier_auth']) && $_SESSION['cashier_auth'];
}

function getCurrentCashier() {
    if (!isCashierLoggedIn()) return null;
    return [
        'id' => $_SESSION['cashier_id'] ?? 0,
        'username' => $_SESSION['cashier_name'] ?? '',
        'discount_limit_percent' => (int)($_SESSION['cashier_discount_limit'] ?? 0),
        'can_cash' => (int)($_SESSION['cashier_can_cash'] ?? 0)
    ];
}

/**
 * 收银台操作鉴权：管理员或收银员会话均可，返回操作者类型('admin'/'cashier')
 */
function requireCashierOrAdminSession() {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    if (isAdminLoggedIn()) return 'admin';
    if (isCashierLoggedIn()) return 'cashier';
    error('未登录收银台', 401);
}

function cashierLogout() {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    unset($_SESSION['cashier_auth'], $_SESSION['cashier_id'], $_SESSION['cashier_name'], $_SESSION['cashier_discount_limit'], $_SESSION['cashier_can_cash'], $_SESSION['cashier_login_time']);
}

/**
 * 记录一笔收银台订单（写入独立的 orders.db，与 web.db 事务完全解耦）
 * ★ 设计要点：
 *   - 订单是 PHP 收银台自己的数据，Java 永不读写本库，彻底消除 web.db 文件锁竞争
 *   - 写入失败仅记录日志、返回空单号，绝不阻断购买主流程（库存/发药依赖 web.db）
 *   - $db 参数为历史兼容保留，但实际一律路由到 orders.db，避免误写 web.db 死表
 * @return string 订单号（失败返回空串）
 */
function recordCashierOrder($data, $db = null) {
    try {
        $odb = getOrdersDB();
    } catch (\Throwable $e) {
        debugLog("recordCashierOrder: 无法获取 orders.db 连接（订单未记录）: " . $e->getMessage());
        return '';
    }
    $orderNo = 'C' . date('YmdHis') . str_pad(mt_rand(0, 999), 3, '0', STR_PAD_LEFT);
    try {
        $stmt = $odb->prepare("INSERT INTO cashier_orders (order_no, operator_type, operator_name, player_name, items_detail, subtotal, total_price, discount_percent, discount_amount, settlement, payment_method, status, created_at) VALUES (:no,:ot,:on,:pn,:det,:sub,:tp,:dp,:da,:st,:pm,'completed',:time)");
        $stmt->bindValue(':no', $orderNo, SQLITE3_TEXT);
        $stmt->bindValue(':ot', $data['operator_type'] ?? 'cashier', SQLITE3_TEXT);
        $stmt->bindValue(':on', $data['operator_name'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':pn', $data['player_name'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':det', json_encode($data['items_detail'] ?? [], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
        $stmt->bindValue(':sub', (int)($data['subtotal'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':tp', (int)($data['total_price'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':dp', (int)($data['discount_percent'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':da', (int)($data['discount_amount'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':st', $data['settlement'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':pm', $data['payment_method'] ?? 'bond', SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
    } catch (\Throwable $e) {
        debugLog("recordCashierOrder: 订单写入失败（不影响购买主流程）: " . $e->getMessage());
        return '';
    }
    return $orderNo;
}

// ===== 定时清理（仅清理过期token，每次请求执行一次） =====
function cleanExpiredTokensLazy() {
    // 频率限制：每5分钟最多清理一次
    $lockFile = sqlDbFile('web') . '.clean_lock';
    if (file_exists($lockFile)) {
        $lastClean = (int)file_get_contents($lockFile);
        if (time() - $lastClean < 300) return;
    }
    file_put_contents($lockFile, (string)time());

    try {
        $db = getDB();
        $db->exec("DELETE FROM tokens WHERE expires_at < " . time());
        $db->exec("DELETE FROM weblogin_tokens WHERE created_at < " . (time() - 86400));
        // 清理30分钟前的会话日志
        $db->exec("DELETE FROM web_session_log WHERE login_time < " . (time() - 1800));
    } catch (Exception $e) {
        // 静默处理数据库锁定错误，不影响正常请求
    }
}
cleanExpiredTokensLazy();

// ===== Web登录安全验证（双保险机制）=====

/**
 * 存储插件推送的玩家密码凭证
 */
function storeWebLoginCredentials($playerName, $passwordHash, $salt) {
    $db = getDB();
    
    // ★ 先尝试插入（新表结构，包含 temp 字段）
    try {
        $stmt = $db->prepare("INSERT OR REPLACE INTO weblogin_credentials (player_name, password_hash, salt, temp_password_hash, temp_pw_expire, pushed_at) VALUES (:player, :hash, :salt, :temp, :tempExp, :time)");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $stmt->bindValue(':hash', $passwordHash, SQLITE3_TEXT);
        $stmt->bindValue(':salt', $salt, SQLITE3_TEXT);
        $stmt->bindValue(':temp', '', SQLITE3_TEXT);
        $stmt->bindValue(':tempExp', 0, SQLITE3_INTEGER);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
        return; // 成功则返回
    } catch (Exception $e) {
        // ★ 如果是因为缺少 temp_password_hash 字段（旧数据库），降级为老字段插入
        debugLog("storeWebLoginCredentials: 降级兼容旧数据库", ['error' => $e->getMessage()]);
    }
    
    // 降级方案：只插入老字段
    try {
        $stmt = $db->prepare("INSERT OR REPLACE INTO weblogin_credentials (player_name, password_hash, salt, pushed_at) VALUES (:player, :hash, :salt, :time)");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $stmt->bindValue(':hash', $passwordHash, SQLITE3_TEXT);
        $stmt->bindValue(':salt', $salt, SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
    } catch (Exception $e) {
        debugLog("storeWebLoginCredentials: 降级插入也失败", ['error' => $e->getMessage()]);
    }
}

/**
 * 存储插件推送的临时密码（独立于主密码）
 */
function storeTempPassword($playerName, $tempHash, $expire) {
    $db = getDB();
    // ★ 先尝试更新（新表结构）
    try {
        $stmt = $db->prepare("UPDATE weblogin_credentials SET temp_password_hash = :temp, temp_pw_expire = :expire WHERE player_name = :player");
        $stmt->bindValue(':temp', $tempHash, SQLITE3_TEXT);
        $stmt->bindValue(':expire', (int)$expire, SQLITE3_INTEGER);
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $stmt->execute();
        return; // 成功则返回
    } catch (Exception $e) {
        // 缺少字段则忽略（旧数据库，不支持临时密码）
        debugLog("storeTempPassword: 字段不存在，跳过", ['error' => $e->getMessage()]);
        return;
    }
}

/**
 * 验证Web登录密码（优先校验临时密码，再校验主密码）
 * 返回: 'temp' 表示临时密码, 'main' 表示主密码, false 表示失败
 */
function verifyWebLoginPassword($playerName, $password) {
    // 确保数据库已迁移
    $db = getDB();
    migrateDatabase($db);

    try {
        $stmt = $db->prepare("SELECT password_hash, salt, temp_password_hash, temp_pw_expire FROM weblogin_credentials WHERE player_name = :player");
    } catch (Exception $e) {
        // 表不存在，返回 false
        debugLog("verifyWebLoginPassword: 表不存在", ['player' => $playerName]);
        return false;
    }
    $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) return false;

    // 1. 先检查临时密码
    if (!empty($row['temp_password_hash'])) {
        $tempExpire = (int)$row['temp_pw_expire'];
        if (time() < $tempExpire && $row['temp_password_hash'] !== '') {
            $salt = $row['salt'];
            $saltBytes = base64_decode($salt, true);
            if ($saltBytes !== false && $saltBytes !== '') {
                $rawHash = hash('sha256', $saltBytes . $password, true);
                $computedHash = base64_encode($rawHash);
                if ($computedHash === $row['temp_password_hash']) {
                    return 'temp'; // 临时密码匹配
                }
            }
        }
    }

    // 2. 再检查主密码
    $storedHash = $row['password_hash'];
    $salt = $row['salt'];
    $saltBytes = base64_decode($salt, true);
    if ($saltBytes === false || $saltBytes === '') return false;

    $rawHash = hash('sha256', $saltBytes . $password, true);
    $computedHash = base64_encode($rawHash);

    return ($computedHash === $storedHash) ? 'main' : false;
}

/**
 * 记录Web登录成功会话
 */
function recordWebSession($playerName, $ipAddress) {
    $db = getDB();
    $sessionToken = bin2hex(random_bytes(16));

    $stmt = $db->prepare("INSERT INTO web_session_log (player_name, ip_address, login_time, session_token) VALUES (:player, :ip, :time, :token)");
    $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $stmt->bindValue(':ip', $ipAddress, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':token', $sessionToken, SQLITE3_TEXT);
    $stmt->execute();

    return $sessionToken;
}

/**
 * 检查是否有3分钟内的快速重连记录（同IP）
 * @return bool 是否可以快速重连
 */
function canQuickReconnect($playerName, $ipAddress) {
    $db = getDB();
    $threeMinutesAgo = time() - 180;

    $stmt = $db->prepare("SELECT id FROM web_session_log WHERE player_name = :player AND ip_address = :ip AND login_time > :time LIMIT 1");
    $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $stmt->bindValue(':ip', $ipAddress, SQLITE3_TEXT);
    $stmt->bindValue(':time', $threeMinutesAgo, SQLITE3_INTEGER);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    return $row !== false;
}

/**
 * 检查玩家是否在游戏里注册过
 * ★ 修复：优先检查weblogin_credentials表（由插件推送，最准确），
 * 同时检查users表（由sync_login同步，可能有滞后）
 * @return bool 是否已注册
 */
function isPlayerRegistered($playerName) {
    $db = getDB();
    migrateDatabase($db); // 确保表结构正确

    // ★ 关键修复：只检查 users 表（由插件同步，最权威）
    // weblogin_credentials 只存凭证，不用于判断注册状态
    try {
        $stmt = $db->prepare("SELECT 1 FROM users WHERE player_name = :player");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray();
        $found = $row !== false;
        
        if (!$found) {
            // ★ 玩家不在 users 表中，清理 weblogin_credentials 中的孤立记录
            try {
                $delStmt = $db->prepare("DELETE FROM weblogin_credentials WHERE player_name = :player");
                $delStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
                $delStmt->execute();
                debugLog("isPlayerRegistered: 清理了 weblogin_credentials 中的孤立记录", ['player' => $playerName]);
            } catch (Exception $e2) {
                // 忽略
            }
        }
        
        debugLog("isPlayerRegistered: 检查users表", ['player' => $playerName, 'found' => $found, 'source' => 'users']);
        return $found;
    } catch (Exception $e) {
        debugLog("isPlayerRegistered: users表查询失败", ['error' => $e->getMessage()]);
        return false;
    }
}

/**
 * Web请求安全验证入口
 * 返回格式：['ok' => bool, 'mode' => 'full'|'quick'|'denied', 'message' => string]
 *
 * @param string $webToken  weblogin token
 * @param string $action    操作类型：'view'|'buy'|'recharge'|'cdk'
 * @param string $password  用户输入的密码（关键操作时需要）
 * @param string $ipAddress 客户端IP
 */
/**
 * ★ 封禁拦截：查询玩家是否处于【名字封禁】生效期（Java /ban 通过 sync_bans 同步的 web_player_bans）
 * 返回 null = 未封禁（或数据不可用时放行）；否则返回封禁原因字符串（供前端提示）。
 * expire_time 单位兼容：Java 推送的是毫秒（BanEntry.getExpiration().getTime()），0 = 永久。
 * 覆盖范围：token登录 / 密码登录 / 邮箱验证码登录 / 商城·CDK·余额等 validateWebAccess 入口。
 */
function playerWebBanReason($player, $db = null) {
    if ($player === null || $player === '') return null;
    try {
        if ($db === null) $db = getDB();
        // ★ 第33批(#212)：禁用 sqlite_master 存在性探测 —— 该句抽不出 web_player_bans，
        //   SdfDbRouter 按「非 Java 表」判成 web 侧，而本表在 java 侧 → 恒判「表不存在」
        //   → 封禁拦截全放行（与 sync.php 第16批公示同坑）。改为直接查目标表：
        //   SQL 自带表名 → 路由到 java 侧；表不存在 → prepare 失败 → 放行（原语义不变）。
        $stmt = $db->prepare("SELECT reason, expire_time FROM web_player_bans WHERE ban_type = 'name' AND LOWER(target) = LOWER(:p)");
        if (!$stmt) return null;
        $stmt->bindValue(':p', $player, SQLITE3_TEXT);
        $res = $stmt->execute();
        if (!$res) return null;
        $now = time();
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $exp = (int)$row['expire_time'];
            if ($exp > 100000000000) $exp = intdiv($exp, 1000); // 毫秒 → 秒
            if ($exp === 0 || $exp > $now) {
                $reason = trim((string)($row['reason'] ?? ''));
                return $reason !== '' ? $reason : '违反服务器规则';
            }
        }
    } catch (\Throwable $e) {
        @error_log('[playerWebBanReason] ' . $e->getMessage());
    }
    return null;
}

/**
 * ★ 第32批拉黑拦截：查询玩家是否处于【拉黑】生效期（Java BlacklistManager
 * 通过 sync_blacklist 同步的 web_player_blacklist）。
 * 返回 null = 未拉黑（或表不存在 → 放行）；否则返回拉黑理由字符串（供前端提示）。
 * expire_ms 单位：Java 推送的是毫秒（BlackEntry.expiresMs），0 = 永久。
 */
function playerWebBlacklistReason($player, $db = null) {
    if ($player === null || $player === '') return null;
    try {
        if ($db === null) $db = getDB();
        // ★ 第33批(#212)：禁用 sqlite_master 存在性探测（同 playerWebBanReason 的坑）——
        //   该句抽不出 web_player_blacklist → 路由判 web 侧（本表在 java 侧）→ 恒判
        //   「表不存在」→ 拉黑玩家全部放行进后台（线上实测：表存在 0 行假象即此因）。
        //   改为直接查目标表：SQL 自带表名 → 路由 java 侧；表不存在 → prepare 失败 → 放行。
        $stmt = $db->prepare("SELECT reason, expire_ms FROM web_player_blacklist WHERE LOWER(name) = LOWER(:p)");
        if (!$stmt) return null;
        $stmt->bindValue(':p', $player, SQLITE3_TEXT);
        $res = $stmt->execute();
        if (!$res) return null;
        $nowMs = time() * 1000;
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $exp = (int)$row['expire_ms'];
            if ($exp === 0 || $exp > $nowMs) {
                $reason = trim((string)($row['reason'] ?? ''));
                return $reason !== '' ? $reason : '违反服务器规则';
            }
        }
    } catch (\Throwable $e) {
        @error_log('[playerWebBlacklistReason] ' . $e->getMessage());
    }
    return null;
}

function validateWebAccess($webToken, $action = 'view', $password = null, $ipAddress = null) {
    if (!$ipAddress) $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    $db = getDB();

    // 1. 验证token
    $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        @error_log("[validateWebAccess] DENIED: token not found in DB. Token prefix: " . substr($webToken, 0, 20) . "...");
        return ['ok' => false, 'mode' => 'denied', 'message' => '无效的登录Token'];
    }

    $createdAt = (int)$row['created_at'];
    $expireSeconds = (int)$row['expire_seconds'];
    $age = time() - $createdAt;
    @error_log("[validateWebAccess] FOUND: player=" . $row['player_name'] . ", age={$age}s, expire={$expireSeconds}s");

    if (time() - $createdAt > $expireSeconds) {
        @error_log("[validateWebAccess] DENIED: token expired. Age: $age, Expire: $expireSeconds");
        return ['ok' => false, 'mode' => 'denied', 'message' => '登录Token已过期'];
    }

    $playerName = $row['player_name'];

    // ★ 封禁拦截：被封禁玩家禁止使用Web端（商城/CDK/余额/登录校验一律拒绝）
    $banReason = playerWebBanReason($playerName, $db);
    if ($banReason !== null) {
        @error_log("[validateWebAccess] DENIED: player banned. player=$playerName");
        return ['ok' => false, 'mode' => 'denied', 'message' => '账号已被封禁：' . $banReason];
    }

    // ★ 第32批拉黑拦截：被拉黑玩家禁止使用Web端（Java BlacklistManager 同步）
    $blackReason = playerWebBlacklistReason($playerName, $db);
    if ($blackReason !== null) {
        @error_log("[validateWebAccess] DENIED: player blacklisted. player=$playerName");
        return ['ok' => false, 'mode' => 'blacklisted', 'player' => $playerName,
                'message' => '你已被拉黑：' . $blackReason];
    }

    // 在线玩家表（Java插件同步，这里确保表存在）
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER DEFAULT 0)");
    } catch (Exception $e) {
        // 忽略
    }

    // 2. Token有效 → 检查玩家是否在游戏中登录（双重检查机制）— 回档9bae160版本
    $isOnline = false;

    // ★ 详细调试：先查online_players表总数和内容
    try {
        $countStmt = $db->query("SELECT COUNT(*) as cnt FROM online_players");
        $countRow = $countStmt->fetchArray(SQLITE3_ASSOC);
        $totalOnline = $countRow ? (int)$countRow['cnt'] : 0;
        
        $allStmt = $db->query("SELECT player_name FROM online_players");
        $allNames = [];
        while ($r = $allStmt->fetchArray(SQLITE3_ASSOC)) {
            $allNames[] = $r['player_name'];
        }
        
        // 查心跳
        $hbStmt = $db->query("SELECT last_seen FROM online_player_hb LIMIT 1");
        $hbRow = $hbStmt->fetchArray(SQLITE3_ASSOC);
        $hbLastSeen = $hbRow ? (int)$hbRow['last_seen'] : 0;
        $hbAgo = $hbLastSeen > 0 ? time() - $hbLastSeen : -1;
        
        @error_log("[validateWebAccess] online_players查询: total=$totalOnline, names=" . implode(',', $allNames) . ", heartbeat_ago={$hbAgo}s, searchName=" . $playerName);
    } catch (Exception $e) {
        @error_log("[validateWebAccess] online_players查询异常: " . $e->getMessage());
        $totalOnline = 0;
        $allNames = [];
    }

    // 检查1: online_players表（Java插件推送）
    try {
        $onlineStmt = $db->prepare("SELECT 1 FROM online_players WHERE player_name = :player");
        $onlineStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $onlineResult = $onlineStmt->execute();
        $onlineRow = $onlineResult->fetchArray();
        $isOnline = ($onlineRow !== false);
        @error_log("[validateWebAccess] 玩家 " . $playerName . " 在online_players中: " . ($isOnline ? '找到' : '未找到'));
    } catch (Exception $e) {
        $isOnline = false;
        @error_log("[validateWebAccess] online_players查询异常: " . $e->getMessage());
    }

    // 检查2: web_session_log — ★ 不再用于判断在线状态
    // web_session_log 仅记录会话历史，不决定权限
    // 在线状态完全由Java插件推送的 online_players 表决定

    // 3. 检查玩家注册状态
    $isRegistered = isPlayerRegistered($playerName);

    debugLog("validateWebAccess: 状态检查", [
        'player' => $playerName,
        'action' => $action,
        'online' => $isOnline,
        'registered' => $isRegistered,
        'ip' => $ipAddress
    ]);

    // 4. view操作：Token有效 + 在线 + Java推送web_login_verified → 允许访问
    if ($action === 'view') {
        if (!$isRegistered) {
            // ★ 玩家未在游戏中注册 → 允许Web注册，录入注册请求等待插件同步
            debugLog("validateWebAccess: 需要注册", ['player' => $playerName]);
            return ['ok' => false, 'mode' => 'need_register', 'player' => $playerName, 'registered' => false, 'online' => $isOnline, 'message' => '玩家未注册，可以在Web端注册账号'];
        }
        if (!$isOnline) {
            // 玩家已注册但在游戏中未登录 → 需要密码
            debugLog("validateWebAccess: 需要密码(不在线)", ['player' => $playerName]);
            return ['ok' => false, 'mode' => 'need_password', 'player' => $playerName, 'registered' => true, 'online' => false, 'message' => '请输入游戏登录密码以同步登录游戏'];
        }
        // ★ 在线但未通过Java推送web_login_verified → 也需要密码（防止偷token自动登录）
        $isWebVerified = false;
        try {
            // ★ 使用token的有效期作为窗口（而非硬编码5分钟），确保与token生命周期一致
            $verifiedWindow = time() - $expireSeconds;
            $wvStmt = $db->prepare("SELECT 1 FROM web_login_verified WHERE player_name = :player AND verified_at >= :expire");
            $wvStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $wvStmt->bindValue(':expire', $verifiedWindow, SQLITE3_INTEGER);
            $wvResult = $wvStmt->execute();
            $wvRow = $wvResult->fetchArray();
            if ($wvRow) $isWebVerified = true;
        } catch (\Throwable $e) {}
        if (!$isWebVerified) {
            debugLog("validateWebAccess: 需要密码(在线但未验证)", ['player' => $playerName]);
            return ['ok' => false, 'mode' => 'need_password', 'player' => $playerName, 'registered' => true, 'online' => true, 'message' => '请输入游戏登录密码以验证身份'];
        }
        $sessionToken = recordWebSession($playerName, $ipAddress);
        debugLog("validateWebAccess: 验证成功(Java已确认)", ['player' => $playerName, 'mode' => 'full']);
        return ['ok' => true, 'mode' => 'full', 'player' => $playerName, 'session' => $sessionToken, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '验证成功'];
    }

    // 5. 关键操作（buy/recharge/cdk）：需要玩家在游戏中登录或已通过PHP密码验证
    if (!$isOnline) {
        // ★ 检查web_login_verified：PHP密码登录成功后5分钟内允许关键操作
        $isVerified = false;
        try {
            $fiveMinAgo = time() - 300;
            $verifiedStmt = $db->prepare("SELECT 1 FROM web_login_verified WHERE player_name = :player AND verified_at >= :expire");
            $verifiedStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $verifiedStmt->bindValue(':expire', $fiveMinAgo, SQLITE3_INTEGER);
            $verifiedResult = $verifiedStmt->execute();
            $verifiedRow = $verifiedResult->fetchArray();
            if ($verifiedRow) {
                $isVerified = true;
            }
        } catch (Exception $e) {}
        if (!$isVerified) {
            debugLog("validateWebAccess: 需要游戏登录", ['player' => $playerName, 'action' => $action]);
            return ['ok' => false, 'mode' => 'need_game_login', 'player' => $playerName, 'registered' => $isRegistered, 'online' => false, 'message' => '请先在游戏中登录'];
        }
        // PHP密码验证有效，放行
        $sessionToken = recordWebSession($playerName, $ipAddress);
        debugLog("validateWebAccess: PHP密码验证放行（关键操作）", ['player' => $playerName, 'action' => $action]);
    }
    
    // 6. 关键操作（buy/recharge/cdk）：需要玩家已注册
    if (!$isRegistered) {
        debugLog("validateWebAccess: 需要注册", ['player' => $playerName, 'action' => $action]);
        return ['ok' => false, 'mode' => 'need_register', 'player' => $playerName, 'registered' => false, 'online' => $isOnline, 'message' => '玩家未注册，请先在游戏中注册'];
    }
    
    // 7. 关键操作（buy/recharge/cdk）：敏感操作二次验证密码/邮箱验证码，≤1000债券交易不再验证密码
    if (in_array($action, ['buy', 'recharge', 'cdk'])) {
        // CDK兑换不需要额外密码验证（CDK本身就是验证）
        if ($action === 'cdk') {
            $sessionToken = recordWebSession($playerName, $ipAddress);
            debugLog("validateWebAccess: CDK兑换免验证", ['player' => $playerName]);
            return ['ok' => true, 'mode' => 'full_verified', 'player' => $playerName, 'session' => $sessionToken, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => 'CDK兑换免验证'];
        }

        // 查询交易金额
        $amount = 0;
        if ($action === 'buy') {
            $itemId = getParam('item_id');
            if ($itemId) {
                $itemStmt = $db->prepare("SELECT buy_price FROM shop_items WHERE id = :id");
                $itemStmt->bindValue(':id', $itemId, SQLITE3_TEXT);
                $itemResult = $itemStmt->execute();
                $itemRow = $itemResult->fetchArray(SQLITE3_ASSOC);
                if ($itemRow) {
                    $amount = (int)$itemRow['buy_price'];
                }
            }
        } elseif ($action === 'recharge') {
            $amount = (int)getParam('amount', 0);
        }

        // ≤1000债券交易不再验证密码
        if ($amount > 0 && $amount <= 1000) {
            $sessionToken = recordWebSession($playerName, $ipAddress);
            debugLog("validateWebAccess: 小额交易免验证", ['player' => $playerName, 'amount' => $amount]);
            return ['ok' => true, 'mode' => 'full_verified', 'player' => $playerName, 'session' => $sessionToken, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '交易金额≤1000，免验证'];
        }

        // ★ 安全修复：大额交易密码验证必须通过游戏服务器（web_login_verified），禁止PHP自验证
        // 检查web_login_verified表：最近10分钟内通过Java验证过 → 允许
        $db = getDB();
        try {
            $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
            $stmt = $db->prepare("SELECT verified_at FROM web_login_verified WHERE player_name = :player");
            $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $result = $stmt->execute();
            $row = $result->fetchArray();
            $verifiedAt = $row ? (int)$row['verified_at'] : 0;
            $validWindow = 600; // 10分钟内有效
            if ($verifiedAt > 0 && (time() - $verifiedAt) < $validWindow) {
                $sessionToken = recordWebSession($playerName, $ipAddress);
                debugLog("validateWebAccess: Java验证有效", ['player' => $playerName, 'amount' => $amount, 'age' => time() - $verifiedAt]);
                return ['ok' => true, 'mode' => 'full_verified', 'player' => $playerName, 'session' => $sessionToken, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '密码验证通过'];
            }
        } catch (\Throwable $e) {
            debugLog("validateWebAccess: 查询web_login_verified异常", ['error' => $e->getMessage()]);
        }
        debugLog("validateWebAccess: 需要密码", ['player' => $playerName, 'amount' => $amount]);
        return ['ok' => false, 'mode' => 'need_password', 'player' => $playerName, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '大额交易需要在游戏中重新验证密码'];
    }

    // 8. 其他操作：Token有效，直接允许访问
    $sessionToken = recordWebSession($playerName, $ipAddress);
    debugLog("validateWebAccess: 验证成功", ['player' => $playerName, 'mode' => 'full']);
    return ['ok' => true, 'mode' => 'full', 'player' => $playerName, 'session' => $sessionToken, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '验证成功'];
}

// =====================================================================
// 异地登录检测 / 账号冻结 公共函数（security_alert.php / freeze.php 复用）
// =====================================================================

/**
 * SMTP 邮件发送（已从 api/sync.php 上移至此处，统一复用，避免重复定义）
 */
function smtpSendEmail($host, $port, $user, $pass, $to, $subject, $htmlBody, $headers, $useSSL = true) {
    $errno = 0;
    $errstr = '';

    // 创建socket连接
    if ($useSSL) {
        $socket = @fsockopen('ssl://' . $host, $port, $errno, $errstr, 30);
    } else {
        $socket = @fsockopen($host, $port, $errno, $errstr, 30);
    }

    if (!$socket) {
        throw new Exception("SMTP连接失败: $errstr ($errno)");
    }

    // 读取服务器响应
    $response = fgets($socket);
    if (strpos($response, '220') !== 0) {
        fclose($socket);
        throw new Exception("SMTP服务器拒绝连接: $response");
    }

    // 发送EHLO
    fwrite($socket, "EHLO localhost\r\n");
    $response = fread($socket, 1024);

    // 登录认证
    fwrite($socket, "AUTH LOGIN\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) {
        fclose($socket);
        throw new Exception("SMTP认证失败: $response");
    }

    // 发送用户名
    fwrite($socket, base64_encode($user) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) {
        fclose($socket);
        throw new Exception("SMTP用户名错误: $response");
    }

    // 发送密码
    fwrite($socket, base64_encode($pass) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '235') !== 0) {
        fclose($socket);
        throw new Exception("SMTP密码错误: $response");
    }

    // 发送邮件
    fwrite($socket, "MAIL FROM:<$user>\r\n");
    fgets($socket);

    fwrite($socket, "RCPT TO:<$to>\r\n");
    fgets($socket);

    fwrite($socket, "DATA\r\n");
    fgets($socket);

    // 发送邮件头和正文
    $emailData = "Subject: $subject\r\n";
    $emailData .= $headers;
    $emailData .= "\r\n";
    $emailData .= $htmlBody;
    $emailData .= "\r\n.\r\n";

    fwrite($socket, $emailData);
    $response = fgets($socket);

    // 退出
    fwrite($socket, "QUIT\r\n");
    fgets($socket);

    fclose($socket);

    return true;
}

/**
 * 查询登录 IP 归属（ip9.com.cn）
 * 注意：与 admin.php 的 queryIpLocation 区分命名，避免重复定义冲突。
 * @return string|null 归属字符串（如 "广东深圳"），失败或内网返回 null
 */
function queryLoginIpLocation($ip) {
    if (empty($ip) || $ip === '-' || $ip === '127.0.0.1'
        || strpos($ip, '10.') === 0 || strpos($ip, '192.168.') === 0
        || !filter_var($ip, FILTER_VALIDATE_IP)) {
        return null;
    }
    $url = 'https://ip9.com.cn/get?ip=' . urlencode($ip);
    $ctx = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 3,
            'ignore_errors' => true,
            'header' => "Accept: application/json, text/plain, */*\r\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\r\nReferer: https://ip9.com.cn/\r\nOrigin: https://ip9.com.cn"
        ],
        'ssl' => ['verify_peer' => false, 'verify_peer_name' => false]
    ]);
    $response = @file_get_contents($url, false, $ctx);
    if ($response === false) return null;
    $response = trim($response);
    if ($response === '') return null;
    $data = json_decode($response, true);
    if (!$data || !is_array($data)) return null;
    $item = null;
    if (isset($data['data'][0]) && is_array($data['data'][0])) {
        $item = $data['data'][0];
    } elseif (isset($data['data']) && is_array($data['data']) && isset($data['data']['prov'])) {
        $item = $data['data'];
    }
    if (!$item) return null;
    $loc = '';
    if (isset($item['prov'])) $loc .= $item['prov'];
    if (isset($item['city'])) $loc .= $item['city'];
    if (isset($item['area']) && ($item['area'] !== ($item['city'] ?? '')) && ($item['area'] !== ($item['prov'] ?? ''))) {
        $loc .= $item['area'];
    }
    $loc = trim($loc);
    return $loc === '' ? null : $loc;
}

/**
 * 构建站点绝对基址（用于邮件链接）
 */
function getBaseUrl() {
    $host = $_SERVER['HTTP_HOST'] ?? '';
    if ($host === '' || $host === '127.0.0.1' || $host === 'localhost') {
        $host = 'caoyuan.ypshidifu.cn';
    }
    // 站点统一使用 HTTPS
    $base = 'https://' . $host;
    $sub = trim(WEBSUB_DIR, '/');
    if ($sub !== '') $base .= '/' . $sub;
    return rtrim($base, '/');
}

/**
 * 发送异地登录提醒邮件（附带冻结 / 改密链接）
 * @param string $freezeToken 已持久化到 users.freeze_token 的令牌
 */
function sendLocationAlertEmail($name, $email, $ip, $location, $lastLoc, $freezeToken) {
    $db = getDB();
    // 生成改密 token（30分钟内有效）
    $resetToken = bin2hex(random_bytes(16));
    $expire = time() + 1800;
    $ins = $db->prepare("INSERT INTO password_reset_tokens (token, player_name, expire_at) VALUES (:t, :n, :e)");
    $ins->bindValue(':t', $resetToken, SQLITE3_TEXT);
    $ins->bindValue(':n', $name, SQLITE3_TEXT);
    $ins->bindValue(':e', $expire, SQLITE3_INTEGER);
    $ins->execute();

    $base = getBaseUrl();
    $freezeLink = $base . '/freeze.php?token=' . urlencode($freezeToken);
    $resetLink  = $base . '/reset_password.php?token=' . urlencode($resetToken);

    $subject = '[Sdf1] 异地登录提醒：您的账号于 ' . $location . ' 登录';

    $html = '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">'
        . '<meta name="viewport" content="width=device-width, initial-scale=1.0">'
        . '<title>异地登录提醒</title></head><body style="margin:0;padding:0;background:#f4f6f9;font-family:Segoe UI,Tahoma,sans-serif;">'
        . '<div style="max-width:560px;margin:0 auto;padding:24px;">'
        . '<div style="background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 6px 24px rgba(0,0,0,0.08);">'
        . '<div style="background:linear-gradient(135deg,#e0533d,#c0392b);padding:28px 32px;color:#fff;">'
        . '<h1 style="margin:0;font-size:22px;">⚠️ 异地登录提醒</h1>'
        . '<p style="margin:8px 0 0;opacity:.9;font-size:14px;">Sdf1 Minecraft 账号安全中心</p></div>'
        . '<div style="padding:32px;">'
        . '<p style="margin:0 0 16px;font-size:15px;color:#333;">亲爱的 <b>' . htmlspecialchars($name) . '</b>：</p>'
        . '<p style="margin:0 0 16px;font-size:15px;color:#555;line-height:1.7;">我们检测到您的账号于 <b style="color:#e0533d;">' . htmlspecialchars($location) . '</b> 发起了登录，'
        . '而您上一次的登录地点为 <b>' . htmlspecialchars($lastLoc ?: '未知') . '</b>。如果该登录并非您本人操作，账号可能已泄露。</p>'
        . '<div style="background:#fff7f5;border:1px solid #f3c4ba;border-radius:8px;padding:14px 16px;margin:0 0 20px;font-size:14px;color:#8a4b3a;">'
        . '本次登录 IP：' . htmlspecialchars($ip) . '<br>登录时间：' . date('Y-m-d H:i:s') . '</div>'
        . '<p style="margin:0 0 12px;font-size:14px;color:#555;">如确认存在风险，请点击下方按钮<b>冻结账号</b>（将立即封禁游戏登录，需改密后解冻）：</p>'
        . '<div style="text-align:center;margin:0 0 24px;"><a href="' . htmlspecialchars($freezeLink) . '" style="display:inline-block;background:linear-gradient(135deg,#e0533d,#c0392b);color:#fff;text-decoration:none;padding:13px 36px;border-radius:8px;font-size:15px;font-weight:600;">立即冻结账号</a></div>'
        . '<p style="margin:0 0 12px;font-size:14px;color:#555;">如确认是本人操作，也建议您定期修改密码以保障安全：</p>'
        . '<div style="text-align:center;margin:0 0 8px;"><a href="' . htmlspecialchars($resetLink) . '" style="display:inline-block;background:#fff;color:#e0533d;border:1px solid #e0533d;text-decoration:none;padding:12px 28px;border-radius:8px;font-size:14px;font-weight:600;">修改密码</a></div>'
        . '<p style="margin:24px 0 0;font-size:12px;color:#999;border-top:1px solid #eee;padding-top:16px;">本邮件由系统自动发送，请勿直接回复。若您未触发任何登录，请尽快冻结账号并修改密码。</p>'
        . '</div></div></div></body></html>';

    $headers = "From: " . SMTP_SENDER_NAME . " <" . SMTP_USER . ">\r\n"
        . "Reply-To: " . SMTP_USER . "\r\n"
        . "MIME-Version: 1.0\r\n"
        . "Content-Type: text/html; charset=UTF-8\r\n";
    smtpSendEmail(SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, $email, $subject, $html, $headers, SMTP_USE_SSL);
}

// =====================================================================================
// ===== IP黑名单（游戏内封禁IP → Java推送 → Web端拦截） =====
// 数据来源：api/sync.php?action=sync_bans 携带的 ips 负载写入 web_ip_blacklist 表。
// 拦截规则（核心要求）：被封IP访问Web【必须】携带游戏内 /web 命令签发的有效token
//                      （weblogin_tokens 表，未过期），否则一律 403。
// 放行白名单：① Java插件 SECRET_KEY 同步调用 ② 已登录的管理后台/收银台会话
//             （防止管理员把自己的IP锁在站外）③ 有效 /web token。
// 任何异常一律 fail-open 放行，避免误伤整站。
// =====================================================================================

/** 该IP是否在Web黑名单中（含过期时间判断） */
function isIpBlacklisted($ip) {
    if (!$ip) return false;
    try {
        $db = getDB();
        // ★ 第33批(#212)：禁用 sqlite_master 探测（同 playerWebBanReason 的坑：
        //   抽不出 web_ip_blacklist → 路由判 web 侧 → 恒空 → IP黑名单失效）。
        //   直接查目标表：表不存在 → prepare 失败 → 走 catch 放行（fail-open 原语义）。
        $stmt = $db->prepare("SELECT 1 FROM web_ip_blacklist WHERE ip = :ip AND (expire = 0 OR expire > :now) LIMIT 1");
        if (!$stmt) return false;
        $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
        $stmt->bindValue(':now', time(), SQLITE3_INTEGER);
        $row = $stmt->execute()->fetchArray();
        return $row !== false;
    } catch (\Throwable $e) {
        @error_log("[ip_blacklist] 查询失败(放行): " . $e->getMessage());
        return false;
    }
}

/** token 是否为游戏内 /web 签发且未过期 */
function hasWebTokenForBlacklist($token) {
    if (!$token) return false;
    try {
        $db = getDB();
        $stmt = $db->prepare("SELECT created_at, expire_seconds FROM weblogin_tokens WHERE web_token = :t LIMIT 1");
        $stmt->bindValue(':t', $token, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        if (!$row) return false;
        return (time() - (int)$row['created_at']) <= (int)$row['expire_seconds'];
    } catch (\Throwable $e) {
        return false;
    }
}

/** 入口拦截：命中黑名单且没有放行凭据 → 403 */
function enforceIpBlacklist() {
    if (PHP_SAPI === 'cli') return;
    $ip = $_SERVER['REMOTE_ADDR'] ?? '';
    if ($ip === '' || !isIpBlacklisted($ip)) return;

    // ① Java插件同步（SECRET_KEY）
    $secret = getParam('secret');
    if ($secret && $secret === SECRET_KEY) return;

    // ② 已登录的管理后台 / 收银台会话（仅在浏览器带了会话cookie时才查，避免给API请求种session）
    if (!empty($_COOKIE[session_name()])) {
        try { if (isAdminLoggedIn()) return; } catch (\Throwable $e) {}
        try { if (isCashierLoggedIn()) return; } catch (\Throwable $e) {}
    }

    // ③ 游戏内 /web 签发的有效token
    $token = getParam('web_token');
    if (hasWebTokenForBlacklist($token)) {
        @error_log("[ip_blacklist] 放行(持/webtoken): ip=$ip uri=" . ($_SERVER['REQUEST_URI'] ?? ''));
        return;
    }

    @error_log("[ip_blacklist] 拦截: ip=$ip uri=" . ($_SERVER['REQUEST_URI'] ?? ''));

    $uri = $_SERVER['REQUEST_URI'] ?? '';
    $accept = $_SERVER['HTTP_ACCEPT'] ?? '';
    $isApi = strpos($uri, '/api/') !== false || strpos($accept, 'application/json') !== false;
    if ($isApi) {
        header('Content-Type: application/json; charset=utf-8');
        http_response_code(403);
        echo json_encode([
            'success' => false,
            'blocked_ip' => true,
            'message' => '该IP已被服务器封禁，需在游戏内执行 /web 获取授权token后才能访问'
        ], JSON_UNESCAPED_UNICODE);
    } else {
        http_response_code(403);
        echo '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">'
           . '<meta name="viewport" content="width=device-width, initial-scale=1.0">'
           . '<title>403 - IP已被封禁</title><style>'
           . 'body{background:#0d1117;color:#e6edf3;font-family:"Segoe UI",system-ui,sans-serif;'
           . 'min-height:100vh;display:flex;align-items:center;justify-content:center;margin:0}'
           . '.c{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:40px;'
           . 'max-width:460px;text-align:center}'
           . 'h1{color:#f85149;font-size:48px;margin:0 0 12px}'
           . 'p{color:#8b949e;line-height:1.8;font-size:14px}'
           . 'code{color:#79c0ff;background:#0d1117;padding:2px 6px;border-radius:4px}'
           . '</style></head><body><div class="c">'
           . '<h1>403</h1><p><b>当前IP已被服务器封禁</b></p>'
           . '<p>如需访问网页，请先在游戏内执行 <code>/web</code> 获取授权token，'
           . '再携带 <code>web_token</code> 访问。</p></div></body></html>';
    }
    exit;
}

// core.php 被所有页面/API引入 → 在此统一执行入口拦截
enforceIpBlacklist();
