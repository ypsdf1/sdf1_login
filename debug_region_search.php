<?php
// 地区搜索诊断脚本 - 直接测试PHP两步法
while (ob_get_level() > 0) { @ob_end_clean(); }
header('Content-Type: text/plain; charset=utf-8');
error_reporting(E_ALL);
ini_set('display_errors', '1');

echo "=== Region Search Debug v2 ===\n\n";

require_once __DIR__ . '/core.php';

try {
    $db = getDB();
    echo "DB opened OK\n\n";
    
    // 1. Check tables and data
    echo "--- Data Overview ---\n";
    $tables = ['users', 'player_ip_changes', 'player_ip_locations', 'web_session_log'];
    foreach ($tables as $table) {
        try {
            $cnt = $db->query("SELECT COUNT(*) as c FROM $table")->fetchArray(SQLITE3_ASSOC)['c'];
            echo "$table: $cnt rows\n";
        } catch (\Throwable $e) {
            echo "$table: ERROR - " . $e->getMessage() . "\n";
        }
    }
    
    // 2. Check player_ip_locations samples
    echo "\n--- player_ip_locations Samples ---\n";
    try {
        $result = $db->query("SELECT ip_address, location, player_name FROM player_ip_locations LIMIT 10");
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            echo "  {$row['ip_address']} => '{$row['location']}' (player: {$row['player_name']})\n";
        }
    } catch (\Throwable $e) {
        echo "  ERROR: " . $e->getMessage() . "\n";
    }
    
    // 3. Test region search for "长沙"
    $regionKey = '长沙';
    echo "\n--- Testing Region Search: '$regionKey' ---\n";
    
    // Step 1: Find IPs with matching location
    try {
        $locStmt = $db->prepare("SELECT ip_address FROM player_ip_locations WHERE location LIKE :region");
        $locStmt->bindValue(':region', "%$regionKey%", SQLITE3_TEXT);
        $locResult = $locStmt->execute();
        $matchedIps = [];
        while ($locRow = $locResult->fetchArray(SQLITE3_ASSOC)) {
            $matchedIps[] = $locRow['ip_address'];
        }
        echo "Step 1: Found " . count($matchedIps) . " IPs with '$regionKey' location\n";
        if (!empty($matchedIps)) {
            echo "  IPs: " . implode(', ', array_slice($matchedIps, 0, 10)) . "\n";
        }
    } catch (\Throwable $e) {
        echo "Step 1 ERROR: " . $e->getMessage() . "\n";
        $matchedIps = [];
    }
    
    // Step 2: Find players using those IPs
    $matchedPlayers = [];
    if (!empty($matchedIps)) {
        $ipPlaceholders = implode(',', array_fill(0, count($matchedIps), '?'));
        
        // From player_ip_changes
        try {
            $pStmt = $db->prepare("SELECT DISTINCT player_name FROM player_ip_changes WHERE new_ip IN ($ipPlaceholders)");
            $idx = 1;
            foreach ($matchedIps as $ip) { $pStmt->bindValue($idx++, $ip, SQLITE3_TEXT); }
            $pResult = $pStmt->execute();
            while ($pRow = $pResult->fetchArray(SQLITE3_ASSOC)) {
                $matchedPlayers[] = $pRow['player_name'];
            }
            echo "Step 2a (player_ip_changes): Found " . count($matchedPlayers) . " players\n";
        } catch (\Throwable $e) {
            echo "Step 2a ERROR: " . $e->getMessage() . "\n";
        }
        
        // From web_session_log
        try {
            $wStmt = $db->prepare("SELECT DISTINCT player_name FROM web_session_log WHERE ip_address IN ($ipPlaceholders)");
            $idx = 1;
            foreach ($matchedIps as $ip) { $wStmt->bindValue($idx++, $ip, SQLITE3_TEXT); }
            $wResult = $wStmt->execute();
            $beforeCount = count($matchedPlayers);
            while ($wRow = $wResult->fetchArray(SQLITE3_ASSOC)) {
                if (!in_array($wRow['player_name'], $matchedPlayers)) {
                    $matchedPlayers[] = $wRow['player_name'];
                }
            }
            echo "Step 2b (web_session_log): Found " . (count($matchedPlayers) - $beforeCount) . " new players\n";
        } catch (\Throwable $e) {
            echo "Step 2b ERROR: " . $e->getMessage() . "\n";
        }
        
        echo "Step 2 Total: " . count($matchedPlayers) . " players\n";
        if (!empty($matchedPlayers)) {
            echo "  Players: " . implode(', ', array_slice($matchedPlayers, 0, 10)) . "\n";
        }
    }
    
    // Step 3: Query users
    if (!empty($matchedPlayers)) {
        $namePlaceholders = implode(',', array_fill(0, count($matchedPlayers), '?'));
        try {
            $uStmt = $db->prepare("SELECT player_name, email FROM users WHERE player_name IN ($namePlaceholders) LIMIT 5");
            $idx = 1;
            foreach ($matchedPlayers as $pn) { $uStmt->bindValue($idx++, $pn, SQLITE3_TEXT); }
            $uResult = $uStmt->execute();
            $users = [];
            while ($uRow = $uResult->fetchArray(SQLITE3_ASSOC)) {
                $users[] = $uRow;
            }
            echo "Step 3: Found " . count($users) . " users\n";
            foreach ($users as $u) {
                echo "  {$u['player_name']} ({$u['email']})\n";
            }
        } catch (\Throwable $e) {
            echo "Step 3 ERROR: " . $e->getMessage() . "\n";
        }
    } else {
        echo "Step 3: Skipped (no matching players)\n";
    }
    
    // 4. Also check what batchGetPlayerIps would return for all users
    echo "\n--- batchGetPlayerIps Check ---\n";
    try {
        $allUsers = $db->query("SELECT player_name FROM users")->fetchArray(SQLITE3_ASSOC);
        // Get a sample user
        $sampleUser = 'youpaishidifu';
        $stmt = $db->prepare("SELECT player_name, new_ip FROM player_ip_changes WHERE player_name = :name ORDER BY changed_at DESC LIMIT 1");
        $stmt->bindValue(':name', $sampleUser, SQLITE3_TEXT);
        $result = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        if ($result) {
            echo "$sampleUser IP from player_ip_changes: {$result['new_ip']}\n";
            // Check if this IP has location
            $locStmt = $db->prepare("SELECT location FROM player_ip_locations WHERE ip_address = :ip");
            $locStmt->bindValue(':ip', $result['new_ip'], SQLITE3_TEXT);
            $locResult = $locStmt->execute()->fetchArray(SQLITE3_ASSOC);
            echo "  Location: " . ($locResult ? $locResult['location'] : 'NOT FOUND') . "\n";
        } else {
            echo "$sampleUser NOT in player_ip_changes\n";
            // Check web_session_log
            $stmt2 = $db->prepare("SELECT ip_address FROM web_session_log WHERE player_name = :name ORDER BY login_time DESC LIMIT 1");
            $stmt2->bindValue(':name', $sampleUser, SQLITE3_TEXT);
            $result2 = $stmt2->execute()->fetchArray(SQLITE3_ASSOC);
            if ($result2) {
                echo "$sampleUser IP from web_session_log: {$result2['ip_address']}\n";
                $locStmt2 = $db->prepare("SELECT location FROM player_ip_locations WHERE ip_address = :ip");
                $locStmt2->bindValue(':ip', $result2['ip_address'], SQLITE3_TEXT);
                $locResult2 = $locStmt2->execute()->fetchArray(SQLITE3_ASSOC);
                echo "  Location: " . ($locResult2 ? $locResult2['location'] : 'NOT FOUND') . "\n";
            } else {
                echo "$sampleUser NOT in web_session_log either\n";
            }
        }
    } catch (\Throwable $e) {
        echo "batchGetPlayerIps check ERROR: " . $e->getMessage() . "\n";
    }
    
} catch (\Throwable $e) {
    echo "\nFATAL ERROR: " . $e->getMessage() . "\n";
}

echo "\n=== Done ===\n";
?>