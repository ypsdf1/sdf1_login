<?php
// 版本标记文件 - 用于验证上传是否成功
header('Content-Type: text/plain');
echo "admin.php version: 2026-06-19-1527\n";
echo "File size: " . filesize(__DIR__ . '/api/admin.php') . " bytes\n";
echo "MD5: " . md5_file(__DIR__ . '/api/admin.php') . "\n";
echo "Time: " . date('Y-m-d H:i:s') . "\n";
?>