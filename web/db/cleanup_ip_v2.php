<?php
// ★ IP归属地清理脚本 v2 - 使用统一校验函数
error_reporting(E_ALL);
ini_set('display_errors', '1');
ini_set('max_execution_time', 300);

require_once __DIR__ . '/../core.php';

header('Content-Type: text/html; charset=utf-8');
?>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>IP归属地清理 v2</title>
    <style>
        body { font-family: monospace; padding: 20px; background: #1a1a1a; color: #0f0; }
        .ok { color: #0f0; }
        .err { color: #f00; }
        .warn { color: #ff0; }
        .info { color: #00f; }
        pre { background: #333; padding: 10px; overflow-x: auto; max-height: 300px; overflow-y: auto; }
        table { border-collapse: collapse; width: 100%; margin: 10px 0; }
        th, td { border: 1px solid #444; padding: 4px 8px; text-align: left; font-size: 12px; }
        th { background: #333; }
        .row-invalid { background: #300; }
        .row-valid { background: #030; }
    </style>
</head>
<body>
<h1>🔍 IP归属地清理工具 v2</h1>

<?php
try {
    $db = getDB();

    echo "<h2>0. 当前数据库状态</h2>";
    $totalBefore = $db->query("SELECT COUNT(*) as cnt FROM player_ip_locations")->fetchArray(SQLITE3_ASSOC)['cnt'] ?? 0;
    echo "<p>清理前总记录数: $totalBefore</p>";

    // ★ 列出所有记录 + 逐条验证
    echo "<h2>📋 全部记录逐条验证（使用统一校验函数 isValidIpLocationFormat）</h2>";
    echo "<table><tr><th>IP</th><th>位置</th><th>验证结果</th><th>删除原因</th></tr>";

    $allStmt = $db->query("SELECT ip_address, location FROM player_ip_locations ORDER BY ip_address");
    $records = [];
    while ($row = $allStmt->fetchArray(SQLITE3_ASSOC)) {
        $records[] = $row;
    }

    $deletedCount = 0;
    $checkedCount = 0;

    foreach ($records as $row) {
        $ip = $row['ip_address'];
        $loc = $row['location'];
        $checkedCount++;

        // ★ 直接使用统一校验函数
        $isValid = isValidIpLocationFormat($loc);

        // 判断删除原因（用于显示）
        $reason = '';
        if (!$isValid) {
            if ($loc === '' || $loc === null || $loc === 'NULL' || $loc === '-' || $loc === '--') {
                $reason = "空值/短杠";
            } elseif ($loc === '查询失败' || $loc === '查询中' || $loc === '内网IP') {
                $reason = "特殊标记";
            } elseif (stripos($loc, 'China') === 0) {
                $reason = "英文格式";
            } elseif (strpos($loc, '中国 - -') === 0 || strpos($loc, '中国 -  -') === 0) {
                $reason = "不完整中文格式";
            } elseif (preg_match('/^中国\s*$/u', $loc) || preg_match('/^中国\s+[^\s省市区州盟]+$/u', $loc)) {
                $reason = "不完整中文格式";
            } elseif (strpos($loc, ' ') === false) {
                $reason = "无空格";
            } else {
                $reason = "省市格式不符合";
            }
        }

        $shouldDelete = !$isValid;
        $funcStr = $isValid ? '✅' : '❌';
        $rowClass = $shouldDelete ? 'row-invalid' : 'row-valid';

        echo "<tr class='$rowClass'>";
        echo "<td>$ip</td>";
        echo "<td>" . htmlspecialchars($loc) . "</td>";
        echo "<td>$funcStr</td>";
        echo "<td>" . ($shouldDelete ? "<b class='err'>$reason</b>" : '-') . "</td>";
        echo "</tr>";

        if ($shouldDelete) {
            $delStmt = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address = :ip");
            $delStmt->bindValue(':ip', $ip, SQLITE3_TEXT);
            $delStmt->execute();
            $deletedCount++;
        }
    }

    echo "</table>";

    echo "<p class='info'>检查了 $checkedCount 条记录</p>";
    if ($deletedCount > 0) {
        echo "<p class='ok'>✅ 删除 $deletedCount 条无效记录</p>";
    } else {
        echo "<p class='ok'>无无效记录</p>";
    }

    // 统计
    $totalAfter = $db->query("SELECT COUNT(*) as cnt FROM player_ip_locations")->fetchArray(SQLITE3_ASSOC)['cnt'] ?? 0;
    echo "<p>清理前: $totalBefore → 清理后: $totalAfter</p>";

    // 需要重新查询的IP（过滤掉短杠和空值）
    echo "<hr><h2>6. 需要重新查询的IP（player_ip_changes 中有但 player_ip_locations 中没有的）</h2>";
    $needQuery = $db->query("
        SELECT DISTINCT pic.new_ip
        FROM player_ip_changes pic
        LEFT JOIN player_ip_locations pil ON pic.new_ip = pil.ip_address
        WHERE pil.ip_address IS NULL
           AND pic.new_ip IS NOT NULL
           AND pic.new_ip != ''
           AND pic.new_ip NOT IN ('-', '--')
           AND pic.new_ip LIKE '%.%'
        ORDER BY pic.new_ip
        LIMIT 50
    ");
    $needQueryList = [];
    while ($row = $needQuery->fetchArray(SQLITE3_ASSOC)) {
        $needQueryList[] = $row['new_ip'];
    }
    echo "<p>找到 " . count($needQueryList) . " 个需要查询的IP</p>";
    if (!empty($needQueryList)) {
        echo "<pre>";
        foreach ($needQueryList as $ip) {
            echo $ip . "\n";
        }
        echo "</pre>";
    }

    // 额外：player_ip_locations 中存储了短杠位置的记录
    echo "<h2>7. 数据库中存储了短杠位置的记录</h2>";
    $dashLocQuery = $db->query("SELECT ip_address, location FROM player_ip_locations WHERE location IN ('-', '--', '') OR location IS NULL");
    $dashLocCount = 0;
    while ($row = $dashLocQuery->fetchArray(SQLITE3_ASSOC)) {
        echo "<p>" . $row['ip_address'] . " => " . htmlspecialchars($row['location'] ?? 'NULL') . "</p>";
        $dashLocCount++;
    }
    if ($dashLocCount === 0) {
        echo "<p class='ok'>无短杠位置记录</p>";
    } else {
        echo "<p class='warn'>找到 $dashLocCount 条短杠位置记录，将清理</p>";
        $db->exec("DELETE FROM player_ip_locations WHERE location IN ('-', '--', '') OR location IS NULL");
        echo "<p class='ok'>已清理短杠位置记录</p>";
    }

    echo "<hr><p class='ok'>✅ 清理完成！请刷新用户管理页面查看效果。</p>";

} catch (Exception $e) {
    echo "<p class='err'>错误: " . $e->getMessage() . "</p>";
    echo "<pre>" . $e->getTraceAsString() . "</pre>";
}
?>

<hr>
<p>💡 清理规则（使用统一校验函数 isValidIpLocationFormat）：</p>
<ol>
    <li>空值、短杠、双短杠、NULL</li>
    <li>查询失败、查询中、内网IP</li>
    <li>英文格式（China - xxx）</li>
    <li>不完整的中文格式（中国 - -xxx）</li>
    <li>无空格（缺少运营商分隔）</li>
    <li>直辖市缺少"市"（如"北京 移动"→应为"北京市 移动"）</li>
    <li>非直辖市缺少城市后缀（如"湖北省 移动"→应为"湖北省武汉市 移动"）</li>
    <li>支持无后缀格式（如"湖北武汉 移通"）</li>
</ol>
</body>
</html>
