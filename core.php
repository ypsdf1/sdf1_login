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
        $db = new SQLite3(DB_PATH);
        $db->enableExceptions(true);
        // 减少 PRAGMA 调用，避免可能的锁问题
        $db->exec('PRAGMA journal_mode=WAL');
        $db->exec('PRAGMA busy_timeout=15000');  // 数据库锁等待15秒
        $db->exec('PRAGMA synchronous=NORMAL');
        $db->exec('PRAGMA cache_size=-64000');     // 64MB 缓存
        initTables($db);
        migrateDatabase($db); // 迁移旧数据库
    }
    return $db;
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
            last_sync INTEGER DEFAULT 0
        )");

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
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
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

    // 4. view操作：Token有效 → 允许Web密码登录（即使游戏里未登录）
    if ($action === 'view') {
        if (!$isRegistered) {
            // ★ 玩家未在游戏中注册 → 允许Web注册，录入注册请求等待插件同步
            debugLog("validateWebAccess: 需要注册", ['player' => $playerName]);
            return ['ok' => false, 'mode' => 'need_register', 'player' => $playerName, 'registered' => false, 'online' => $isOnline, 'message' => '玩家未注册，可以在Web端注册账号'];
        }
        if (!$isOnline) {
            // 玩家已注册但在游戏中未登录 → 允许Web密码登录
            debugLog("validateWebAccess: 需要密码", ['player' => $playerName]);
            return ['ok' => false, 'mode' => 'need_password', 'player' => $playerName, 'registered' => true, 'online' => false, 'message' => '请输入游戏登录密码以同步登录游戏'];
        }
        $sessionToken = recordWebSession($playerName, $ipAddress);
        debugLog("validateWebAccess: 验证成功", ['player' => $playerName, 'mode' => 'full']);
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

        // >1000债券交易需要密码验证
        if ($password) {
            if (verifyWebLoginPassword($playerName, $password)) {
                $sessionToken = recordWebSession($playerName, $ipAddress);
                debugLog("validateWebAccess: 密码验证通过", ['player' => $playerName, 'amount' => $amount]);
                return ['ok' => true, 'mode' => 'full_verified', 'player' => $playerName, 'session' => $sessionToken, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '密码验证通过'];
            }
            debugLog("validateWebAccess: 密码错误", ['player' => $playerName]);
            return ['ok' => false, 'mode' => 'denied', 'message' => '密码错误'];
        }
        debugLog("validateWebAccess: 需要密码", ['player' => $playerName, 'amount' => $amount]);
        return ['ok' => false, 'mode' => 'need_password', 'player' => $playerName, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '大额交易需要输入密码确认'];
    }

    // 8. 其他操作：Token有效，直接允许访问
    $sessionToken = recordWebSession($playerName, $ipAddress);
    debugLog("validateWebAccess: 验证成功", ['player' => $playerName, 'mode' => 'full']);
    return ['ok' => true, 'mode' => 'full', 'player' => $playerName, 'session' => $sessionToken, 'registered' => $isRegistered, 'online' => $isOnline, 'message' => '验证成功'];
}
