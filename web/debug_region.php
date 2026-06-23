<?php
/**
 * 调试地区搜索问题
 * 显示 player_ip_locations 和 player_ip_changes 表内容，以及地区搜索SQL测试结果
 */
header('Content-Type: text/html; charset=utf-8');
require_once 'core.php';

$db = getDB();
echo "<h2>调试地区搜索</h2>";

// 1. player_ip_locations 表内容
echo "<h3>player_ip_locations 表</h3>";
try {
    $result = $db->query("SELECT COUNT(*) as cnt FROM player_ip_locations");
    $row = $result->fetchArray(SQLITE3_ASSOC);
    echo "总记录数: " . $row['cnt'] . "<br>";
    
    if ($row['cnt'] > 0) {
        echo "<table border='1' cellpadding='5'>";
        echo "<tr><th>ip_address</th><th>location</th><th>player_name</th><th>updated_at</th></tr>";
        $result = $db->query("SELECT * FROM player_ip_locations ORDER BY updated_at DESC LIMIT 20");
        while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
            echo "<tr>";
            echo "<td>" . htmlspecialchars($r['ip_address']) . "</td>";
            echo "<td>" . htmlspecialchars($r['location']) . "</td>";
            echo "<td>" . htmlspecialchars($r['player_name']) . "</td>";
            echo "<td>" . date('Y-m-d H:i:s', $r['updated_at']) . "</td>";
            echo "</tr>";
        }
        echo "</table>";
    } else {
        echo "表为空!<br>";
    }
} catch (Exception $e) {
    echo "错误: " . $e->getMessage() . "<br>";
}

// 2. player_ip_changes 表内容
echo "<h3>player_ip_changes 表</h3>";
try {
    $result = $db->query("SELECT COUNT(*) as cnt FROM player_ip_changes");
    $row = $result->fetchArray(SQLITE3_ASSOC);
    echo "总记录数: " . $row['cnt'] . "<br>";
    
    if ($row['cnt'] > 0) {
        echo "<table border='1' cellpadding='5'>";
        echo "<tr><th>player_name</th><th>new_ip</th><th>changed_at</th></tr>";
        $result = $db->query("SELECT * FROM player_ip_changes ORDER BY changed_at DESC LIMIT 20");
        while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
            echo "<tr>";
            echo "<td>" . htmlspecialchars($r['player_name']) . "</td>";
            echo "<td>" . htmlspecialchars($r['new_ip']) . "</td>";
            echo "<td>" . date('Y-m-d H:i:s', $r['changed_at']) . "</td>";
            echo "</tr>";
        }
        echo "</table>";
    } else {
        echo "表为空!<br>";
    }
} catch (Exception $e) {
    echo "错误: " . $e->getMessage() . "<br>";
}

// 3. 地区搜索SQL测试
echo "<h3>地区搜索SQL测试</h3>";
$search = '长沙';
$regionKey = $search;

// 测试JOIN
$sql = "SELECT c.player_name FROM player_ip_changes c
        INNER JOIN player_ip_locations l ON c.new_ip = l.ip_address
        WHERE l.location LIKE '%$regionKey%' AND c.new_ip != '' AND c.new_ip != '-'
        GROUP BY c.player_name";
echo "SQL: <pre>" . htmlspecialchars($sql) . "</pre>";

try {
    $result = $db->query($sql);
    $count = 0;
    $players = [];
    while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
        $count++;
        $players[] = $r['player_name'];
    }
    echo "匹配玩家数: $count<br>";
    if ($count > 0) {
        echo "匹配玩家: " . implode(', ', $players) . "<br>";
    }
} catch (Exception $e) {
    echo "SQL错误: " . $e->getMessage() . "<br>";
}

// 4. 检查所有location中包含"长沙"的
echo "<h3>包含'长沙'的location记录</h3>";
try {
    $result = $db->query("SELECT ip_address, location, player_name FROM player_ip_locations WHERE location LIKE '%长沙%'");
    $found = false;
    while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
        $found = true;
        echo "IP: " . htmlspecialchars($r['ip_address']) . " → " . htmlspecialchars($r['location']) . "<br>";
    }
    if (!$found) {
        echo "没有找到包含'长沙'的location记录<br>";
    }
} catch (Exception $e) {
    echo "错误: " . $e->getMessage() . "<br>";
}

// 5. 检查users表中是否有来自长沙的玩家（通过IP推断）
echo "<h3>测试：直接搜索users表</h3>";
try {
    $result = $db->query("SELECT player_name, email FROM users LIMIT 10");
    while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
        echo "玩家: " . htmlspecialchars($r['player_name']) . " (email: " . htmlspecialchars($r['email']) . ")<br>";
    }
} catch (Exception $e) {
    echo "错误: " . $e->getMessage() . "<br>";
}

// 6. 尝试手动查询一个IP的归属地
echo "<h3>测试：手动查询IP归属地</h3>";
try {
    $result = $db->query("SELECT DISTINCT new_ip FROM player_ip_changes WHERE new_ip != '' AND new_ip != '-' AND new_ip NOT LIKE '10.%' AND new_ip NOT LIKE '192.168.%' AND new_ip != '127.0.0.1' LIMIT 5");
    $ips = [];
    while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
        $ips[] = $r['new_ip'];
    }
    
    if (!empty($ips)) {
        echo "测试查询以下IP的归属地: " . implode(', ', $ips) . "<br>";
        require_once 'api/admin.php';
        foreach ($ips as $ip) {
            $loc = queryIpLocationWithTimeout($ip, 3);
            echo "IP: $ip → " . ($loc ? $loc['location'] : 'null') . "<br>";
        }
    } else {
        echo "没有找到可用的外部IP<br>";
    }
} catch (Exception $e) {
    echo "错误: " . $e->getMessage() . "<br>";
}
?>
