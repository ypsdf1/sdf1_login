<?php
// ★ IP查询调试页面 v2 - 完整追踪查询流程
error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');
ini_set('max_execution_time', 120);

// ★ 关键：在任何输出之前设置缓冲区
ob_start();

// 不要包含 admin.php（它会输出JSON并exit）
// 只包含 core.php
require_once __DIR__ . '/../core.php';

// 清空输出缓冲区
while (ob_get_level() > 0) {
    ob_end_clean();
}

// 现在开始输出HTML
header('Content-Type: text/html; charset=utf-8');
?>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>IP查询调试 v2</title>
    <style>
        body { font-family: monospace; padding: 20px; background: #1a1a1a; color: #0f0; }
        .ok { color: #0f0; }
        .err { color: #f00; }
        .warn { color: #ff0; }
        .info { color: #00f; }
        pre { background: #333; padding: 10px; overflow-x: auto; max-height: 400px; overflow-y: auto; white-space: pre-wrap; word-wrap: break-word; }
        .step { margin: 10px 0; padding: 10px; border-left: 3px solid #0f0; }
        .step.error { border-left-color: #f00; }
        .step.warn { border-left-color: #ff0; }
        .api-result { margin: 5px 0; padding: 5px; background: #222; }
    </style>
</head>
<body>
<h1>🔍 IP查询调试页面 v2</h1>

<?php
// 获取要测试的IP（支持逗号分隔多IP）
$allIps = [];
$ipParam = trim($_GET['ip'] ?? '');
if ($ipParam) {
    $allIps = array_map('trim', explode(',', $ipParam));
}
$ip2Param = trim($_GET['ip2'] ?? '');
if ($ip2Param) {
    $allIps = array_merge($allIps, array_map('trim', explode(',', $ip2Param)));
}
// 去重并过滤空值
$allIps = array_unique(array_filter($allIps));
if (empty($allIps)) {
    $allIps = ['36.148.150.193', '120.231.236.80'];
}

echo "<h2>测试IP (" . count($allIps) . "个): " . implode(', ', $allIps) . "</h2>";

// ===== 第1步：检查数据库中的当前状态 =====
echo "<div class='step'>";
echo "<h3>📊 步骤1: 检查数据库当前状态</h3>";

try {
    $db = getDB();
    
    foreach ($allIps as $ip) {
        echo "<p><strong>$ip:</strong></p>";
        
        $stmt = $db->prepare("SELECT * FROM player_ip_locations WHERE ip_address = :ip");
        $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        
        if ($row) {
            echo "<pre>" . json_encode($row, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . "</pre>";
        } else {
            echo "<p class='warn'>数据库中无此IP记录</p>";
        }
    }
} catch (Exception $e) {
    echo "<p class='err'>错误: " . $e->getMessage() . "</p>";
}
echo "</div>";

// ===== 第2步：测试4个API接口（直接调用，不包含admin.php） =====
echo "<div class='step'>";
echo "<h3>🌐 步骤2: 测试4个API接口</h3>";

// ★ 直接定义函数，不包含 admin.php
function testFetchPconline($ip) {
    $url = 'https://whois.pconline.com.cn/ipJson.jsp?ip=' . urlencode($ip) . '&json=true';
    $ctx = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 5,
            'ignore_errors' => true,
            'header' => "Accept: application/json\r\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 SDF1_IP_Lookup/1.0"
        ]
    ]);
    $response = @file_get_contents($url, false, $ctx);
    
    $result = [
        'api' => 'PConline',
        'url' => $url,
        'raw_response' => $response ?: 'FAILED',
        'parsed' => null,
        'location' => null,
        'error' => null
    ];
    
    if ($response === false || empty(trim($response))) {
        $result['error'] = '请求失败';
        return $result;
    }
    
    // ★ 修复编码问题：太平洋返回GBK编码，需要转换为UTF-8
    $response = trim($response);
    if (mb_detect_encoding($response, 'UTF-8', true) === false) {
        $response = mb_convert_encoding($response, 'UTF-8', 'GBK');
        $result['raw_response'] = $response; // 更新原始响应为转换后的
    }
    
    $json = $response;
    if (preg_match('/^\s*\(?[\'"]?(.*?)[\'"]?\)?\s*$/s', $json, $matches)) {
        $json = $matches[1];
    }
    
    $data = json_decode($json, true);
    $result['parsed'] = $data;
    
    if (!$data || !is_array($data)) {
        $result['error'] = 'JSON解析失败';
        return $result;
    }
    
    // 优先使用 addr 字段
    if (isset($data['addr']) && trim($data['addr']) !== '') {
        $addr = trim($data['addr']);
        if (strpos($addr, '中国 ') !== 0) {
            $result['location'] = $addr;
            return $result;
        }
    }
    
    // 尝试用 pro + city 手动拼接
    if (isset($data['pro']) && trim($data['pro']) !== '') {
        $loc = trim($data['pro']);
        if (isset($data['city']) && trim($data['city']) !== '') {
            $loc .= trim($data['city']);
        }
        
        if (isset($data['addr'])) {
            $parts = preg_split('/\s+/', trim($data['addr']), -1, PREG_SPLIT_NO_EMPTY);
            if (!empty($parts)) {
                $op = end($parts);
                if ($op !== '中国') {
                    $loc .= ' ' . $op;
                }
            }
        }
        
        $result['location'] = strlen($loc) > 1 ? $loc : null;
    }
    
    return $result;
}

function testFetchBaidu($ip) {
    $url = 'https://opendata.baidu.com/api.php?query=' . urlencode($ip) . '&resource_id=6006&oe=utf8&format=json';
    $ctx = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 5,
            'ignore_errors' => true,
            'header' => "Accept: application/json\r\nUser-Agent: Mozilla/5.0"
        ]
    ]);
    $response = @file_get_contents($url, false, $ctx);
    
    $result = [
        'api' => 'Baidu',
        'url' => $url,
        'raw_response' => $response ?: 'FAILED',
        'parsed' => null,
        'location' => null,
        'error' => null
    ];
    
    if ($response === false || empty(trim($response))) {
        $result['error'] = '请求失败';
        return $result;
    }
    
    $data = json_decode(trim($response), true);
    $result['parsed'] = $data;
    
    if ($data && isset($data['data']) && is_array($data['data']) && !empty($data['data'])) {
        $loc = $data['data'][0]['location'] ?? null;
        if ($loc && $loc !== '--' && strpos($loc, '中国 ') !== 0) {
            $result['location'] = $loc;
        }
    }
    
    return $result;
}

function testFetchAa1($ip) {
    $url = 'https://v.api.aa1.cn/api/ipcha-baidu/?ip=' . urlencode($ip);
    $ctx = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 5,
            'ignore_errors' => true,
            'header' => "Accept: application/json\r\nUser-Agent: Mozilla/5.0"
        ]
    ]);
    $response = @file_get_contents($url, false, $ctx);
    
    $result = [
        'api' => 'aa1.cn',
        'url' => $url,
        'raw_response' => $response ?: 'FAILED',
        'parsed' => null,
        'location' => null,
        'error' => null
    ];
    
    if ($response === false || empty(trim($response))) {
        $result['error'] = '请求失败';
        return $result;
    }
    
    $data = json_decode(trim($response), true);
    $result['parsed'] = $data;
    
    if ($data && isset($data['code']) && $data['code'] == 1) {
        $loc = $data['ip_add'] ?? null;
        if ($loc && $loc !== '未知' && strpos($loc, '中国 ') !== 0) {
            $result['location'] = $loc;
        }
    }
    
    return $result;
}

function testFetchIp9($ip) {
    // ★ 使用正确的 API 地址：ip9.com.cn/get
    $url = 'https://ip9.com.cn/get?ip=' . urlencode($ip);
    $ctx = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 5,
            'ignore_errors' => true,
            'header' => "Accept: application/json, text/plain, */*\r\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\r\nReferer: https://ip9.com.cn/\r\nOrigin: https://ip9.com.cn"
        ],
        'ssl' => [
            'verify_peer' => false,
            'verify_peer_name' => false
        ]
    ]);
    $response = @file_get_contents($url, false, $ctx);
    
    $result = [
        'api' => 'IP9',
        'url' => $url,
        'raw_response' => $response ?: 'FAILED',
        'parsed' => null,
        'location' => null,
        'error' => null
    ];
    
    if ($response === false || empty(trim($response))) {
        $result['error'] = '请求失败';
        return $result;
    }
    
    $data = json_decode(trim($response), true);
    $result['parsed'] = $data;

    $debug = [];

    // IP9 返回格式有两种可能:
    //   旧版: {"ret":"200","data":[{"ip":"...","prov":"广东","city":"深圳","isp":"中国移动"}]}
    //   新版: {"ret":"200","data":{"ip":"...","prov":"广东","city":"深圳","isp":"中国移动"}}
    // ★ 兼容两种格式
    $item = null;
    if (isset($data['data'][0]) && is_array($data['data'][0])) {
        $item = $data['data'][0];
        $debug['format'] = 'array';
    } elseif (isset($data['data']) && is_array($data['data']) && isset($data['data']['prov'])) {
        $item = $data['data'];
        $debug['format'] = 'object';
    } else {
        $debug['format'] = 'unknown';
        $debug['data_type'] = gettype($data['data'] ?? null);
    }

    if ($item) {
        $loc = '';
        $debug['item_prov'] = $item['prov'] ?? 'NOT_SET';
        $debug['item_city'] = $item['city'] ?? 'NOT_SET';
        $debug['item_isp'] = $item['isp'] ?? 'NOT_SET';

        if (isset($item['prov'])) $loc .= $item['prov'];
        if (isset($item['city'])) $loc .= $item['city'];
        if (isset($item['isp'])) $loc .= ' ' . $item['isp'];

        $debug['loc'] = $loc;
        $debug['loc_len'] = strlen($loc);

        if (strlen($loc) > 0) {
            $result['location'] = $loc;
        }
    }

    $result['debug'] = $debug;

    return $result;
}

// 测试每个IP
foreach ($allIps as $ip) {
    echo "<h4>测试IP: $ip</h4>";
    
    // API1: PConline
    echo "<div class='api-result'>";
    echo "<p><strong>API1 - PConline:</strong></p>";
    $start = microtime(true);
    $r1 = testFetchPconline($ip);
    $time1 = (microtime(true) - $start) * 1000;
    echo "<pre>";
    echo "耗时: " . round($time1, 2) . "ms\n";
    echo "原始响应: " . substr($r1['raw_response'], 0, 500) . "\n";
    echo "解析结果: " . json_encode($r1['parsed'], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . "\n";
    echo "提取位置: " . ($r1['location'] ?? 'null') . "\n";
    echo "错误: " . ($r1['error'] ?? '无');
    echo "</pre>";
    echo "</div>";
    
    // API2: Baidu
    echo "<div class='api-result'>";
    echo "<p><strong>API2 - Baidu:</strong></p>";
    $start = microtime(true);
    $r2 = testFetchBaidu($ip);
    $time2 = (microtime(true) - $start) * 1000;
    echo "<pre>";
    echo "耗时: " . round($time2, 2) . "ms\n";
    echo "原始响应: " . substr($r2['raw_response'], 0, 500) . "\n";
    echo "解析结果: " . json_encode($r2['parsed'], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . "\n";
    echo "提取位置: " . ($r2['location'] ?? 'null') . "\n";
    echo "错误: " . ($r2['error'] ?? '无');
    echo "</pre>";
    echo "</div>";
    
    // API3: aa1.cn
    echo "<div class='api-result'>";
    echo "<p><strong>API3 - aa1.cn:</strong></p>";
    $start = microtime(true);
    $r3 = testFetchAa1($ip);
    $time3 = (microtime(true) - $start) * 1000;
    echo "<pre>";
    echo "耗时: " . round($time3, 2) . "ms\n";
    echo "原始响应: " . substr($r3['raw_response'], 0, 500) . "\n";
    echo "解析结果: " . json_encode($r3['parsed'], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . "\n";
    echo "提取位置: " . ($r3['location'] ?? 'null') . "\n";
    echo "错误: " . ($r3['error'] ?? '无');
    echo "</pre>";
    echo "</div>";
    
    // API4: IP9
    echo "<div class='api-result'>";
    echo "<p><strong>API4 - IP9:</strong></p>";
    $start = microtime(true);
    $r4 = testFetchIp9($ip);
    $time4 = (microtime(true) - $start) * 1000;
    echo "<pre>";
    echo "耗时: " . round($time4, 2) . "ms\n";
    echo "原始响应: " . substr($r4['raw_response'], 0, 500) . "\n";
    echo "解析结果: " . json_encode($r4['parsed'], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . "\n";
    echo "提取位置: " . ($r4['location'] ?? 'null') . "\n";
    echo "错误: " . ($r4['error'] ?? '无') . "\n";
    echo "调试信息: " . json_encode($r4['debug'] ?? [], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
    echo "</pre>";
    echo "</div>";
    
    echo "<hr>";
}
echo "</div>";

// ===== 第3步：模拟入库逻辑 =====
echo "<div class='step'>";
echo "<h3>🔄 步骤3: 模拟入库逻辑</h3>";

echo "<p>以下是服务器处理IP归属地的完整流程：</p>";

// 模拟 queryIpLocation 函数的逻辑
foreach ($allIps as $ip) {
    echo "<h4>模拟IP: $ip</h4>";
    
    echo "<p><strong>1. 检查是否是内网IP</strong></p>";
    if (!$ip || $ip === '-' || $ip === '127.0.0.1' || strpos($ip, '10.') === 0 || strpos($ip, '192.168.') === 0) {
        echo "<p class='warn'>是内网IP，返回 '内网IP'</p>";
        continue;
    }
    echo "<p>不是内网IP，继续查询</p>";
    
    echo "<p><strong>2. 检查数据库缓存</strong></p>";
    $stmt = $db->prepare("SELECT location FROM player_ip_locations WHERE ip_address = :ip LIMIT 1");
    $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    
    if ($row && isset($row['location'])) {
        $cachedLoc = $row['location'];
        echo "<p>找到缓存: $cachedLoc</p>";
        
        // ★ 使用统一校验函数
        $isValidFormat = isValidIpLocationFormat($cachedLoc);
        if (!$isValidFormat) {
            echo "<p class='err'>无效格式: " . htmlspecialchars($cachedLoc) . "</p>";
        }
        
        echo "<p>格式验证结果: " . ($isValidFormat ? "✅ 有效" : "❌ 无效") . "</p>";
        
        if (!$isValidFormat) {
            echo "<p>将删除此缓存并重新查询</p>";
            $stmt2 = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address = :ip");
            $stmt2->bindValue(':ip', $ip, SQLITE3_TEXT);
            $stmt2->execute();
        } else {
            echo "<p class='ok'>格式有效，直接使用缓存</p>";
            continue;
        }
    } else {
        echo "<p>无缓存，需要查询</p>";
    }
    
    echo "<p><strong>3. 调用API查询（格式无效则继续下一个）</strong></p>";
    
    // ★ 与 admin.php 的 doQueryIpLocation() 一致：每个API返回后先验证格式
    $location = null;
    $bestResult = null;
    
    // API1: PConline
    echo "<p>尝试 API1 (PConline)...</p>";
    $r1 = testFetchPconline($ip);
    if ($r1['location']) {
        echo "<p>返回: " . htmlspecialchars($r1['location']) . "</p>";
        if (isValidIpLocationFormat($r1['location'])) {
            $location = $r1['location'];
            echo "<p class='ok'>✅ 格式有效: $location</p>";
        } else {
            echo "<p class='warn'>⚠️ 格式无效，继续尝试下一个API</p>";
            $bestResult = $r1['location'];
        }
    } else {
        echo "<p class='err'>❌ API1 无结果</p>";
    }
    
    // API2: Baidu（仅当前面没有有效结果时才尝试）
    if (!$location) {
        echo "<p>尝试 API2 (Baidu)...</p>";
        $r2 = testFetchBaidu($ip);
        if ($r2['location']) {
            echo "<p>返回: " . htmlspecialchars($r2['location']) . "</p>";
            if (isValidIpLocationFormat($r2['location'])) {
                $location = $r2['location'];
                echo "<p class='ok'>✅ 格式有效: $location</p>";
            } else {
                echo "<p class='warn'>⚠️ 格式无效，继续尝试下一个API</p>";
                if (!$bestResult) $bestResult = $r2['location'];
            }
        } else {
            echo "<p class='err'>❌ API2 无结果</p>";
        }
    }
    
    // API3: aa1.cn（仅当前面没有有效结果时才尝试）
    if (!$location) {
        echo "<p>尝试 API3 (aa1.cn)...</p>";
        $r3 = testFetchAa1($ip);
        if ($r3['location']) {
            echo "<p>返回: " . htmlspecialchars($r3['location']) . "</p>";
            if (isValidIpLocationFormat($r3['location'])) {
                $location = $r3['location'];
                echo "<p class='ok'>✅ 格式有效: $location</p>";
            } else {
                echo "<p class='warn'>⚠️ 格式无效，继续尝试下一个API</p>";
                if (!$bestResult) $bestResult = $r3['location'];
            }
        } else {
            echo "<p class='err'>❌ API3 无结果</p>";
        }
    }
    
    // API4: IP9（仅当前面没有有效结果时才尝试）
    if (!$location) {
        echo "<p>尝试 API4 (IP9)...</p>";
        $r4 = testFetchIp9($ip);
        if ($r4['location']) {
            echo "<p>返回: " . htmlspecialchars($r4['location']) . "</p>";
            if (isValidIpLocationFormat($r4['location'])) {
                $location = $r4['location'];
                echo "<p class='ok'>✅ 格式有效: $location</p>";
            } else {
                echo "<p class='warn'>⚠️ 格式无效，继续尝试下一个API</p>";
                if (!$bestResult) $bestResult = $r4['location'];
            }
        } else {
            echo "<p class='err'>❌ API4 无结果</p>";
        }
    }
    
    // 所有API都没返回有效格式：标记为查询失败
    if (!$location) {
        $location = '查询失败';
        echo '<p class="err">❌ 所有API均失败或格式无效，标记为"查询失败"</p>';
    }
    
    echo "<p><strong>4. 格式验证和修复（与 admin.php 一致）</strong></p>";
    echo "<p>原始位置: $location</p>";

    // 格式修复逻辑
    if ($location && $location !== '查询失败') {
        $loc = $location;

        // 1. 过滤英文格式
        if (strpos($loc, 'China - ') === 0 || strpos($loc, 'China -') === 0) {
            echo "<p class='warn'>拒绝英文格式: $loc</p>";
            $location = null;
        }

        // 2. 过滤不完整的中文格式
        if (strpos($loc, '中国 - -') === 0 || strpos($loc, '中国 -  -') === 0) {
            echo "<p class='warn'>拒绝不完整格式: $loc</p>";
            $location = null;
        }

        // 3. 格式修复：确保"位置"和"运营商"之间有空格
        // 仅在没有空格时修复（如"广东省移动"→"广东省 移动"）
        if ($location && strpos($loc, ' ') === false) {
            // 无空格，尝试添加空格
            if (preg_match('/^(.*?(?:省|市|州|盟|地区|区|县))(.+)$/u', $loc, $matches)) {
                $loc = $matches[1] . ' ' . $matches[2];
                echo "<p>格式修复: $location => $loc</p>";
                $location = $loc;
            }
        }

        // ★ 4. 存储前格式验证（使用统一校验函数）
        if ($location) {
            $shouldStore = isValidIpLocationFormat($location);
            if (!$shouldStore) {
                echo "<p class='err'>格式无效（不满足入库条件）: " . htmlspecialchars($location) . "</p>";
                $location = null;
            }
        }
    }

    echo "<p><strong>5. 存入数据库</strong></p>";
    if ($location && $location !== '查询失败') {
        echo "<p class='ok'>将存储: $location</p>";

        // 模拟存储（不实际执行）
        echo "<pre>INSERT OR REPLACE INTO player_ip_locations (player_name, ip_address, location, updated_at) VALUES ('global', '$ip', '$location', " . time() . ")</pre>";
    } else {
        echo "<p class='err'>不存储（查询失败或格式无效）</p>";
    }
    
    echo "<hr>";
}
echo "</div>";

// ===== 第4步：显示所有无效格式的记录 =====
echo "<div class='step'>";
echo "<h3>❌ 步骤4: 显示所有无效格式的记录</h3>";

$invalidStmt = $db->query("
    SELECT ip_address, location 
    FROM player_ip_locations 
    WHERE location LIKE 'China%'
       OR location = '-'
       OR location = '--'
       OR location = '查询失败'
       OR location = ''
       OR ip_address LIKE '127.%'
       OR ip_address LIKE '192.168.%'
       OR ip_address LIKE '10.%'
       OR location LIKE '中国 - -%'
       OR location LIKE '中国 -  -%'
       OR (location NOT LIKE '% %' AND (location LIKE '%省%' OR location LIKE '%市%'))
");
$invalidCount = 0;
while ($row = $invalidStmt->fetchArray(SQLITE3_ASSOC)) {
    echo "<p>" . $row['ip_address'] . " => " . $row['location'] . "</p>";
    $invalidCount++;
}

if ($invalidCount == 0) {
    echo "<p class='ok'>没有发现无效格式的记录</p>";
} else {
    echo "<p class='warn'>发现 $invalidCount 条无效格式的记录</p>";
}
echo "</div>";

echo "<hr>";
echo "<p>💡 此页面用于调试IP查询流程，检查每个API的返回结果和入库逻辑。</p>";
?>

</body>
</html>
