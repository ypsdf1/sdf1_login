<?php
/**
 * 自更新脚本 - 通过HTTP POST上传文件内容
 * 用法: curl -X POST -d "secret=KEY&action=update_file&target=api/admin.php" --data-binary @admin.php https://.../self_update.php
 * 或: curl -X POST -F "secret=KEY" -F "action=update_file" -F "target=api/admin.php" -F "file=@admin.php" https://.../self_update.php
 */
header('Content-Type: text/plain; charset=utf-8');
@ob_start();

require_once __DIR__ . '/config.php';

$secret = $_POST['secret'] ?? $_GET['secret'] ?? '';
$action = $_POST['action'] ?? $_GET['action'] ?? '';

if ($secret !== SECRET_KEY) {
    echo "ERROR: Invalid secret key\n";
    exit;
}

$timestamp = date('Y-m-d H:i:s');

if ($action === 'update_file') {
    $target = $_POST['target'] ?? '';
    $allowedTargets = ['api/admin.php', 'api/sync.php', 'core.php', 'config.php'];
    
    if (!in_array($target, $allowedTargets)) {
        echo "ERROR: Target '$target' not in allowed list: " . implode(', ', $allowedTargets) . "\n";
        exit;
    }
    
    $targetPath = __DIR__ . '/' . $target;
    
    // Handle file upload
    if (isset($_FILES['file']) && $_FILES['file']['error'] === UPLOAD_ERR_OK) {
        $content = file_get_contents($_FILES['file']['tmp_name']);
    } elseif (isset($_POST['content'])) {
        $content = $_POST['content'];
    } else {
        // Read from raw POST body
        $content = file_get_contents('php://input');
    }
    
    if (empty($content)) {
        echo "ERROR: No file content received\n";
        exit;
    }
    
    // Backup
    $backupPath = $targetPath . '.bak';
    if (file_exists($targetPath)) {
        copy($targetPath, $backupPath);
    }
    
    // Write
    $bytesWritten = file_put_contents($targetPath, $content);
    
    if ($bytesWritten !== false) {
        echo "SUCCESS: Updated $target ($bytesWritten bytes) at $timestamp\n";
        echo "Backup: $backupPath\n";
        
        // Verify
        echo "Verify MD5: " . md5_file($targetPath) . "\n";
        
        // Clear opcache
        if (function_exists('opcache_reset')) {
            @opcache_reset();
            echo "OPCache: cleared\n";
        }
        if (function_exists('opcache_invalidate')) {
            @opcache_invalidate($targetPath, true);
            echo "OPCache: invalidated $target\n";
        }
    } else {
        echo "ERROR: Failed to write file\n";
    }
    
} elseif ($action === 'verify') {
    echo "=== File Verification ===\n";
    $files = ['api/admin.php', 'api/sync.php', 'core.php'];
    foreach ($files as $f) {
        $path = __DIR__ . '/' . $f;
        if (file_exists($path)) {
            $size = filesize($path);
            $md5 = md5_file($path);
            $mtime = date('Y-m-d H:i:s', filemtime($path));
            echo "$f: $size bytes, MD5: $md5, modified: $mtime\n";
        } else {
            echo "$f: NOT FOUND\n";
        }
    }
    
} else {
    echo "Usage: ?action=update_file|verify&secret=KEY\n";
}

echo "\nDone at $timestamp\n";
@ob_end_flush();
?>