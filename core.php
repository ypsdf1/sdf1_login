<?php
// ===== Web通信系统 - 核心引擎 =====
require_once __DIR__ . '/config.php';

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
}

// ===== 数据库初始化 =====
function getDB() {
    static $db = null;
    if ($db === null) {
        // 确保db目录存在
        $dbDir = dirname(DB_PATH);
        if (!is_dir($dbDir)) {
            @mkdir($dbDir, 0755, true);
        }

        // ★ 检查并修复数据库文件/目录权限（防止 "attempt to write a readonly database"）
        if (file_exists(DB_PATH)) {
            if (!is_writable(DB_PATH)) {
                @chmod(DB_PATH, 0666);
                if (!is_writable(DB_PATH)) {
                    debugLog("getDB: 数据库文件不可写: " . DB_PATH);
                }
            }
        }
        if (is_dir($dbDir) && !is_writable($dbDir)) {
            @chmod($dbDir, 0777);
        }
        // ★ 同时修复WAL/SHM文件权限
        foreach (['-wal', '-shm'] as $suffix) {
            $sideFile = DB_PATH . $suffix;
            if (file_exists($sideFile) && !is_writable($sideFile)) {
                @chmod($sideFile, 0666);
            }
        }

        $db = new SQLite3(DB_PATH);
        $db->enableExceptions(true);
        // WAL模式 + 长等待超时防止database is locked
        $db->exec('PRAGMA journal_mode=WAL');
        $db->exec('PRAGMA busy_timeout=8000');   // ★ 8秒等待（原60秒太长，Java轮询锁时PHP会卡住近1分钟）
        $db->exec('PRAGMA synchronous=NORMAL');
        $db->exec('PRAGMA cache_size=-64000');     // 64MB 缓存
        $db->exec('PRAGMA wal_autocheckpoint=100');// ★ 更频繁checkpoint（原1000），更快释放WAL锁减少竞争

        // ★ initTables/migrateDatabase加try-catch，防止初始化写入失败导致整个请求500
        try {
            initTables($db);
        } catch (\Throwable $e) {
            debugLog("getDB: initTables失败（可能数据库只读）: " . $e->getMessage());
        }
        try {
            migrateDatabase($db); // 迁移旧数据库
        } catch (\Throwable $e) {
            debugLog("getDB: migrateDatabase失败: " . $e->getMessage());
        }
    }

    // ★ 安全网：仅在首次创建连接时清理上一次请求可能残留的未提交事务
    // （PHP-FPM/opcache复用进程时，前一个请求异常退出可能遗留BEGIN但无COMMIT/ROLLBACK）
    // 注意：不能每次调用都ROLLBACK，否则会中断当前请求正在进行的事务
    static $rollbackDone = false;
    if (!$rollbackDone) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $_) {}
        $rollbackDone = true;
    }

    return $db;
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
    $dbPath = DB_PATH;
    $dbDir = dirname($dbPath);

    // 修复目录权限
    if (is_dir($dbDir)) {
        $oldPerms = substr(sprintf('%o', fileperms($dbDir)), -4);
        @chmod($dbDir, 0777);
        $newPerms = substr(sprintf('%o', fileperms($dbDir)), -4);
        $results[] = "目录 $dbDir: $oldPerms → $newPerms (writable=" . (is_writable($dbDir) ? 'Y' : 'N') . ")";
    } else {
        @mkdir($dbDir, 0777, true);
        $results[] = "目录 $dbDir: 已创建";
    }

    // 修复数据库文件权限
    if (file_exists($dbPath)) {
        $oldPerms = substr(sprintf('%o', fileperms($dbPath)), -4);
        @chmod($dbPath, 0666);
        $newPerms = substr(sprintf('%o', fileperms($dbPath)), -4);
        $results[] = "数据库 $dbPath: $oldPerms → $newPerms (writable=" . (is_writable($dbPath) ? 'Y' : 'N') . ")";
    } else {
        $results[] = "数据库 $dbPath: 不存在（首次访问时自动创建）";
    }

    // 修复WAL/SHM文件权限
    foreach (['-wal', '-shm'] as $suffix) {
        $sideFile = $dbPath . $suffix;
        if (file_exists($sideFile)) {
            $oldPerms = substr(sprintf('%o', fileperms($sideFile)), -4);
            @chmod($sideFile, 0666);
            $newPerms = substr(sprintf('%o', fileperms($sideFile)), -4);
            $results[] = "WAL文件 $sideFile: $oldPerms → $newPerms";
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
function migrateDatabase(SQLite3 $db) {
    // 检查是否已迁移（用文件标记，避免每次都做 PRAGMA 查询）
    $migrateLock = DB_PATH . '.migrated';
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

function initTables(SQLite3 $db) {
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

        // ★ 用户组相关表（从handler函数中提取到这里统一创建，避免并发请求锁竞争）
        $db->exec("CREATE TABLE IF NOT EXISTS web_user_groups (
            group_name TEXT PRIMARY KEY,
            display_name TEXT DEFAULT '',
            display_color TEXT DEFAULT '§f',
            display_emoji TEXT DEFAULT '',
            priority INTEGER DEFAULT 0,
            land_price_per_sqm INTEGER DEFAULT -1,
            max_lands INTEGER DEFAULT -1,
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
}

// ===== 管理员认证 =====

function adminLogin($password) {
    if ($password !== ADMIN_PASS) {
        return false;
    }
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    $_SESSION['admin_auth'] = true;
    $_SESSION['admin_login_time'] = time();
    return true;
}

function isAdminLoggedIn() {
    if (session_status() === PHP_SESSION_NONE) {
        session_start();
    }
    return isset($_SESSION['admin_auth']) && $_SESSION['admin_auth'];
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
    $lockFile = DB_PATH . '.clean_lock';
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
        $host = defined('DEFAULT_HOST') ? DEFAULT_HOST : 'your-domain.com';
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
