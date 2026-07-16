<?php
/**
 * 诊断脚本：检查shop_configs表中的discount_price字段
 */

// 加载支付密钥
$secretsFile = __DIR__ . '/pay_secrets.php';
if (@is_file($secretsFile) && @is_readable($secretsFile)) {
    @require_once $secretsFile;
}

// 获取数据库路径 - 尝试多种可能的路径
$possiblePaths = [
    dirname(__DIR__) . '/db/orders.db',
    dirname(__DIR__) . '/data/orders.db',
    dirname(__DIR__) . '/orders.db',
    dirname(__DIR__) . '/../db/orders.db',
    dirname(__DIR__) . '/../data/orders.db',
    dirname(__DIR__) . '/../orders.db',
];

$dbPath = null;
foreach ($possiblePaths as $path) {
    if (is_file($path)) {
        $dbPath = $path;
        break;
    }
}

if (!$dbPath) {
    echo "数据库文件不存在，尝试的路径:\n";
    foreach ($possiblePaths as $path) {
        echo "  - $path\n";
    }
    // 列出可能的目录内容
    echo "\n当前目录内容:\n";
    $dir = dirname(__DIR__);
    $items = scandir($dir);
    foreach ($items as $item) {
        if ($item != '.' && $item != '..') {
            $fullPath = $dir . '/' . $item;
            $type = is_dir($fullPath) ? '[目录]' : '[文件]';
            echo "  $type $item\n";
        }
    }
    exit(1);
}
if (!is_file($dbPath)) {
    echo "数据库文件不存在: $dbPath\n";
    exit(1);
}

$db = new SQLite3($dbPath);
$db->busyTimeout(5000);
$db->exec('PRAGMA journal_mode=WAL');

// 检查表结构
echo "=== shop_configs表结构 ===\n";
$result = $db->query("PRAGMA table_info(shop_configs)");
while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
    echo "{$row['cid']}: {$row['name']} ({$row['type']})" . (isset($row['notnull']) && $row['notnull'] ? ' NOT NULL' : '') . (isset($row['dflt_value']) ? " DEFAULT {$row['dflt_value']}" : '') . "\n";
}

// 检查现有数据
echo "\n=== 现有商品数据 ===\n";
$result = $db->query("SELECT * FROM shop_configs ORDER BY id DESC LIMIT 5");
while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
    echo "ID: {$row['id']}, 名称: {$row['item_name']}, 原价: {$row['price']}, 折扣价: " . (isset($row['discount_price']) ? $row['discount_price'] : '字段不存在') . "\n";
}

// 测试插入（带折扣价格）
echo "\n=== 测试插入带折扣价格的商品 ===\n";
$stmt = $db->prepare("INSERT INTO shop_configs (item_name, stock, temporary_offer, offer_expire, price, discount_price, bond_reward, is_active, created_at, updated_at) VALUES (:name, :stock, :offer, :expire, :price, :discount_price, :bond, :active, :created, :updated)");
$stmt->bindValue(':name', '测试折扣商品', SQLITE3_TEXT);
$stmt->bindValue(':stock', -1, SQLITE3_INTEGER);
$stmt->bindValue(':offer', '', SQLITE3_TEXT);
$stmt->bindValue(':expire', '', SQLITE3_TEXT);
$stmt->bindValue(':price', 10.00, SQLITE3_FLOAT);
$stmt->bindValue(':discount_price', 8.50, SQLITE3_FLOAT);
$stmt->bindValue(':bond', '1-10', SQLITE3_TEXT);
$stmt->bindValue(':active', 1, SQLITE3_INTEGER);
$stmt->bindValue(':created', time(), SQLITE3_INTEGER);
$stmt->bindValue(':updated', time(), SQLITE3_INTEGER);
$stmt->execute();

$testId = $db->lastInsertRowID();
echo "测试商品ID: $testId\n";

// 验证插入
$result = $db->query("SELECT * FROM shop_configs WHERE id = $testId");
$row = $result->fetchArray(SQLITE3_ASSOC);
if ($row) {
    echo "验证: 原价={$row['price']}, 折扣价={$row['discount_price']}\n";
    if ($row['discount_price'] == 8.50) {
        echo "✅ 折扣价格保存成功！\n";
    } else {
        echo "❌ 折扣价格保存失败！\n";
    }
}

// 清理测试数据
$db->exec("DELETE FROM shop_configs WHERE id = $testId");
echo "已清理测试数据\n";

$db->close();
