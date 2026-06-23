<?php
// 管理后台API诊断脚本
while (ob_get_level() > 0) { @ob_end_clean(); }
header('Content-Type: text/plain; charset=utf-8');
error_reporting(E_ALL);
ini_set('display_errors', '1');

echo "=== Admin API Diagnostic ===\n\n";

require_once __DIR__ . '/core.php';

echo "1. Core loaded: " . (function_exists('getDB') ? 'YES' : 'NO') . "\n";
echo "2. ADMIN_PASS defined: " . (defined('ADMIN_PASS') ? 'YES' : 'NO') . "\n";

// Test database connection
echo "\n--- Database ---\n";
try {
    $db = getDB();
    echo "DB opened OK\n";

    // List all tables
    $result = $db->query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
    $tables = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $tables[] = $row['name'];
    }
    echo "Tables (" . count($tables) . "): " . implode(', ', $tables) . "\n";

    // Check player_ip_changes structure
    if (in_array('player_ip_changes', $tables)) {
        $result = $db->query("PRAGMA table_info(player_ip_changes)");
        echo "\nplayer_ip_changes columns:\n";
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            echo "  - {$row['name']} ({$row['type']}) PK=" . ($row['pk'] ? 'YES' : 'no') . "\n";
        }
        $cnt = $db->query("SELECT COUNT(*) as c FROM player_ip_changes")->fetchArray(SQLITE3_ASSOC)['c'];
        echo "  Rows: $cnt\n";
    }

    // Check player_ip_locations structure
    if (in_array('player_ip_locations', $tables)) {
        $result = $db->query("PRAGMA table_info(player_ip_locations)");
        echo "\nplayer_ip_locations columns:\n";
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            echo "  - {$row['name']} ({$row['type']}) PK=" . ($row['pk'] ? 'YES' : 'no') . "\n";
        }
        $cnt = $db->query("SELECT COUNT(*) as c FROM player_ip_locations")->fetchArray(SQLITE3_ASSOC)['c'];
        echo "  Rows: $cnt\n";
        // Show a sample
        $result = $db->query("SELECT ip_address, location FROM player_ip_locations LIMIT 5");
        if ($result) {
            while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
                echo "  Sample: {$row['ip_address']} => {$row['location']}\n";
            }
        }
    }

    // Check users table
    if (in_array('users', $tables)) {
        $cnt = $db->query("SELECT COUNT(*) as c FROM users")->fetchArray(SQLITE3_ASSOC)['c'];
        echo "\nusers rows: $cnt\n";
    }

    // Check online_players
    if (in_array('online_players', $tables)) {
        $cnt = $db->query("SELECT COUNT(*) as c FROM online_players")->fetchArray(SQLITE3_ASSOC)['c'];
        echo "online_players rows: $cnt\n";
    }

    // Check web_session_log
    if (in_array('web_session_log', $tables)) {
        $cnt = $db->query("SELECT COUNT(*) as c FROM web_session_log")->fetchArray(SQLITE3_ASSOC)['c'];
        echo "web_session_log rows: $cnt\n";
    }

    // Test batchGetPlayerIps
    echo "\n--- Test batchGetPlayerIps ---\n";
    if (function_exists('batchGetPlayerIps') || class_exists('adminListUsersPaginated')) {
        echo "batchGetPlayerIps function NOT available in this scope\n";
    } else {
        echo "Function not available (defined in api/admin.php, not core.php)\n";
    }

    // Test simple queries that adminListUsersPaginated would run
    echo "\n--- Test Query Simulation ---\n";
    try {
        $sql = "SELECT player_name, register_time, last_login_time, points, total_online_time, email FROM users ORDER BY register_time DESC LIMIT 10 OFFSET 0";
        $result = $db->query($sql);
        $count = 0;
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $count++;
        }
        echo "Users query OK, got $count rows\n";
    } catch (\Throwable $e) {
        echo "Users query FAILED: " . $e->getMessage() . "\n";
    }

    // Test player_ip_changes query
    try {
        $stmt = $db->prepare("SELECT player_name, new_ip AS ip_address FROM player_ip_changes WHERE player_name IN ('test') ORDER BY changed_at DESC");
        echo "player_ip_changes simple query: OK (prepared)\n";
    } catch (\Throwable $e) {
        echo "player_ip_changes query FAILED: " . $e->getMessage() . "\n";
    }

    // Test player_ip_locations query
    try {
        $stmt = $db->prepare("SELECT ip_address, location FROM player_ip_locations WHERE ip_address IN ('1.2.3.4')");
        echo "player_ip_locations query: OK (prepared)\n";
    } catch (\Throwable $e) {
        echo "player_ip_locations query FAILED: " . $e->getMessage() . "\n";
    }

    // Test location search query
    try {
        $stmt = $db->prepare("SELECT player_name, new_ip FROM player_ip_changes WHERE new_ip != '' AND new_ip != '-' AND new_ip NOT LIKE '10.%' ORDER BY changed_at DESC LIMIT 5");
        $result = $stmt->execute();
        $ips = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $ips[] = $row['new_ip'];
        }
        echo "Location search pre-query: OK, got " . count($ips) . " IPs\n";
    } catch (\Throwable $e) {
        echo "Location search pre-query FAILED: " . $e->getMessage() . "\n";
    }

    // Test region search SQL
    try {
        $searchBound = "%长沙%";
        $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM player_ip_locations WHERE location LIKE :region");
        $stmt->bindValue(':region', $searchBound, SQLITE3_TEXT);
        $cnt = $stmt->execute()->fetchArray(SQLITE3_ASSOC)['cnt'];
        echo "Region cache check: $cnt matching records\n";
    } catch (\Throwable $e) {
        echo "Region cache check FAILED: " . $e->getMessage() . "\n";
    }

} catch (\Throwable $e) {
    echo "\nFATAL ERROR: " . $e->getMessage() . "\n";
    echo "File: " . $e->getFile() . ":" . $e->getLine() . "\n";
    echo "Stack: " . $e->getTraceAsString() . "\n";
}

echo "\n=== Done ===\n";
