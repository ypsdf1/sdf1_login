<?php
// 诊断脚本 - 测试PHP响应是否干净
error_reporting(E_ALL);
ini_set('display_errors', 1);
ini_set('log_errors', 1);
ini_set('error_log', __DIR__ . '/debug_error.log');

// 开始输出缓冲
ob_start();

// 引入 core.php
require_once __DIR__ . '/core.php';

// 清理缓冲
if (ob_get_level() > 0) {
    ob_end_clean();
}

// 设置正确的响应头
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-cache, no-store, must-revalidate');
header('Pragma: no-cache');
header('Expires: 0');

// 输出 JSON
echo json_encode([
    'status' => 'ok',
    'core_loaded' => function_exists('validateWebAccess'),
    'db_path' => defined('DB_PATH') ? DB_PATH : 'undefined',
    'php_version' => phpversion(),
    'time' => date('Y-m-d H:i:s')
]);
