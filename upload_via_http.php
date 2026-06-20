<?php
/**
 * HTTP文件上传器 - 用于在FTP不可用时通过HTTP上传文件
 * 访问方式: upload_via_http.php?secret=YOUR_SECRET&action=upload
 * 
 * 安全措施：
 * 1. 需要SECRET_KEY认证
 * 2. 只允许覆盖特定文件
 * 3. 记录所有操作
 */
header('Content-Type: text/plain; charset=utf-8');
@ob_start();

require_once __DIR__ . '/config.php';
require_once __DIR__ . '/core.php';

$secret = getParam('secret', '');
$action = getParam('action', '');

if ($secret !== SECRET_KEY) {
    echo "ERROR: Invalid secret key\n";
    exit;
}

$logFile = __DIR__ . '/upload_log.txt';
file_put_contents($logFile, date('Y-m-d H:i:s') . " | Action: $action | IP: " . $_SERVER['REMOTE_ADDR'] . "\n", FILE_APPEND);

if ($action === 'status') {
    echo "=== Server Status ===\n";
    echo "PHP Version: " . phpversion() . "\n";
    echo "SQLite Version: " . SQLite3::version()['versionString'] . "\n";
    echo "Time: " . date('Y-m-d H:i:s') . "\n";
    echo "DB Path: " . (defined('DB_PATH') ? DB_PATH : 'undefined') . "\n";
    
    if (defined('DB_PATH') && file_exists(DB_PATH)) {
        $db = new SQLite3(DB_PATH);
        $db->enableExceptions(false);
        
        echo "\n=== Table Row Counts ===\n";
        $tables = ['users', 'player_ip_changes', 'player_ip_locations', 'web_session_log', 'online_players', 'player_daily_logins', 'player_checkins'];
        foreach ($tables as $t) {
            try {
                $cnt = $db->querySingle("SELECT COUNT(*) FROM $t");
                echo "$t: $cnt rows\n";
            } catch (\Throwable $e) {
                echo "$t: ERROR - " . $e->getMessage() . "\n";
            }
        }
        
        // 检查 player_ip_locations 中的格式
        echo "\n=== IP Location Samples (first 10) ===\n";
        try {
            $r = $db->query("SELECT ip_address, location, player_name FROM player_ip_locations LIMIT 10");
            while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
                echo $row['ip_address'] . " => " . $row['location'] . " (player: " . $row['player_name'] . ")\n";
            }
        } catch (\Throwable $e) {
            echo "ERROR: " . $e->getMessage() . "\n";
        }
        
        // 检查 player_ip_changes 中的玩家IP
        echo "\n=== Player IP Changes Samples (first 10) ===\n";
        try {
            $r = $db->query("SELECT player_name, new_ip, changed_at FROM player_ip_changes ORDER BY changed_at DESC LIMIT 10");
            while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
                echo $row['player_name'] . " => " . $row['new_ip'] . " (at: " . date('Y-m-d H:i:s', $row['changed_at']) . ")\n";
            }
        } catch (\Throwable $e) {
            echo "ERROR: " . $e->getMessage() . "\n";
        }
        
        // 检查 web_session_log 中的玩家IP
        echo "\n=== Web Session Log Samples (first 10) ===\n";
        try {
            $r = $db->query("SELECT player_name, ip_address, login_time FROM web_session_log ORDER BY login_time DESC LIMIT 10");
            while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
                echo $row['player_name'] . " => " . $row['ip_address'] . " (at: " . date('Y-m-d H:i:s', $row['login_time']) . ")\n";
            }
        } catch (\Throwable $e) {
            echo "ERROR: " . $e->getMessage() . "\n";
        }
        
        // 检查 region 搜索模拟
        echo "\n=== Region Search Simulation for '长沙' ===\n";
        echo "Step 1: player_ip_locations with '长沙':\n";
        try {
            $r = $db->query("SELECT ip_address, location FROM player_ip_locations WHERE location LIKE '%长沙%'");
            $cnt = 0;
            while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
                echo "  " . $row['ip_address'] . " => " . $row['location'] . "\n";
                $cnt++;
            }
            echo "  Found: $cnt IPs\n";
        } catch (\Throwable $e) {
            echo "  ERROR: " . $e->getMessage() . "\n";
        }
        
        // 查找有长沙IP的玩家
        echo "\nStep 2: Players with 长沙 IPs:\n";
        try {
            $r = $db->query("
                SELECT DISTINCT c.player_name 
                FROM player_ip_changes c
                INNER JOIN player_ip_locations l ON c.new_ip = l.ip_address
                WHERE l.location LIKE '%长沙%'
                UNION
                SELECT DISTINCT w.player_name
                FROM web_session_log w
                INNER JOIN player_ip_locations l ON w.ip_address = l.ip_address
                WHERE l.location LIKE '%长沙%'
            ");
            $cnt = 0;
            while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
                echo "  " . $row['player_name'] . "\n";
                $cnt++;
            }
            echo "  Found: $cnt players\n";
        } catch (\Throwable $e) {
            echo "  ERROR: " . $e->getMessage() . "\n";
        }
        
        $db->close();
    } else {
        echo "Database not found!\n";
    }
    
    echo "\n=== admin.php Version ===\n";
    $adminFile = __DIR__ . '/api/admin.php';
    if (file_exists($adminFile)) {
        $content = file_get_contents($adminFile);
        echo "File size: " . filesize($adminFile) . " bytes\n";
        echo "Has _region_debug: " . (strpos($content, '_region_debug') !== false ? 'YES' : 'NO') . "\n";
        echo "Has regionDebugEnabled: " . (strpos($content, 'regionDebugEnabled') !== false ? 'YES' : 'NO') . "\n";
        echo "Has batchGetPlayerIps: " . (strpos($content, 'function batchGetPlayerIps') !== false ? 'YES' : 'NO') . "\n";
    } else {
        echo "admin.php not found!\n";
    }
    
    // ★ 模拟 admin 的精确 region search 逻辑
    echo "\n=== Simulate Admin Region Search Logic ===\n";
    $db = new SQLite3(DB_PATH);
    $db->enableExceptions(false);
    
    $regionKey = '长沙';
    
    // Step 1: Get all player names
    $allPlayerNames = [];
    $r = $db->query("SELECT player_name FROM users");
    while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
        $allPlayerNames[] = $row['player_name'];
    }
    echo "All players: " . count($allPlayerNames) . "\n";
    
    // Step 2: Build playerIpMap from player_ip_changes
    $playerIpMap = [];
    $stmt = $db->prepare("SELECT player_name, new_ip FROM player_ip_changes WHERE new_ip != '' AND new_ip != '-' AND new_ip NOT LIKE '10.%' AND new_ip NOT LIKE '192.168.%' AND new_ip != '127.0.0.1'");
    $r = $stmt->execute();
    $changesCount = 0;
    while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
        $changesCount++;
        $pn = $row['player_name'];
        $ip = trim($row['new_ip']);
        if (!isset($playerIpMap[$pn])) $playerIpMap[$pn] = [];
        if (!in_array($ip, $playerIpMap[$pn])) $playerIpMap[$pn][] = $ip;
    }
    echo "player_ip_changes rows: $changesCount\n";
    echo "Players with IPs from changes: " . count($playerIpMap) . "\n";
    
    // web_session_log is empty, skip
    echo "web_session_log rows: 0 (skipped)\n";
    
    // Step 3: Get IP locations
    $ipLocMap = [];
    $r = $db->query("SELECT ip_address, location FROM player_ip_locations");
    while ($row = $r->fetchArray(SQLITE3_ASSOC)) {
        $loc = trim($row['location'] ?? '');
        $ip = trim($row['ip_address'] ?? '');
        if ($loc && $ip) {
            $ipLocMap[$ip] = $loc;
        }
    }
    echo "IP locations cached: " . count($ipLocMap) . "\n";
    
    // Show some sample IP from playerIpMap vs ipLocMap
    echo "\n--- IP Comparison ---\n";
    $samplePlayers = array_slice(array_keys($playerIpMap), 0, 5);
    foreach ($samplePlayers as $pn) {
        $ips = $playerIpMap[$pn];
        echo "Player: $pn => IPs: " . implode(', ', $ips) . "\n";
        foreach ($ips as $ip) {
            if (isset($ipLocMap[$ip])) {
                echo "  FOUND in cache: $ip => " . $ipLocMap[$ip] . "\n";
            } else {
                echo "  NOT in cache: $ip\n";
            }
        }
    }
    
    // Step 4: PHP matching
    $matchedPlayers = [];
    $matchDebug = [];
    foreach ($playerIpMap as $pn => $ips) {
        foreach ($ips as $ip) {
            if (isset($ipLocMap[$ip])) {
                $loc = $ipLocMap[$ip];
                if (mb_stripos($loc, $regionKey) !== false) {
                    if (!in_array($pn, $matchedPlayers)) $matchedPlayers[] = $pn;
                    $matchDebug[] = "$ip=>$loc ($pn)";
                    break;
                }
            }
        }
    }
    echo "\n=== PHP Match Result ===\n";
    echo "Matched players: " . count($matchedPlayers) . "\n";
    foreach ($matchedPlayers as $pn) {
        echo "  $pn\n";
    }
    
    // Check if any 长沙 IPs are NOT in the playerIpMap
    echo "\n--- All 长沙 IPs in cache ---\n";
    foreach ($ipLocMap as $ip => $loc) {
        if (mb_stripos($loc, $regionKey) !== false) {
            echo "  $ip => $loc\n";
            // Check which player has this IP
            $foundPlayer = false;
            foreach ($playerIpMap as $pn => $ips) {
                if (in_array($ip, $ips)) {
                    echo "    Has player: $pn\n";
                    $foundPlayer = true;
                    break;
                }
            }
            if (!$foundPlayer) {
                echo "    NO player found with this IP!\n";
                // Try partial match
                foreach ($playerIpMap as $pn => $ips) {
                    foreach ($ips as $pip) {
                        if (strpos($ip, $pip) !== false || strpos($pip, $ip) !== false) {
                            echo "    Partial match: $pip (player: $pn)\n";
                        }
                    }
                }
            }
        }
    }
    
    $db->close();
    
} elseif ($action === 'upload') {
    // 通过 POST 上传文件
    $target = $_POST['target'] ?? '';
    $allowed = ['api/admin.php', 'api/sync.php', 'api/cdk.php', 'core.php', 'config.php'];
    
    if (!in_array($target, $allowed)) {
        echo "ERROR: Target not allowed. Use: " . implode(', ', $allowed) . "\n";
        exit;
    }
    
    $targetPath = __DIR__ . '/' . $target;
    
    if (isset($_FILES['file']) && $_FILES['file']['error'] === UPLOAD_ERR_OK) {
        $content = file_get_contents($_FILES['file']['tmp_name']);
    } elseif (isset($_POST['content'])) {
        $content = base64_decode($_POST['content']);
    } else {
        $content = file_get_contents('php://input');
    }
    
    if (empty($content)) {
        echo "ERROR: No content\n";
        exit;
    }
    
    if (file_exists($targetPath)) {
        copy($targetPath, $targetPath . '.bak');
    }
    
    $bytes = file_put_contents($targetPath, $content);
    echo "Wrote $bytes bytes to $target\n";
    echo "MD5: " . md5_file($targetPath) . "\n";
    
    if (function_exists('opcache_invalidate')) {
        @opcache_invalidate($targetPath, true);
    }
    if (function_exists('opcache_reset')) {
        @opcache_reset();
    }
    echo "OPCache cleared\n";
}

echo "\nDone.\n";
@ob_end_flush();
?>