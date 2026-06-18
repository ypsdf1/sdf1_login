<?php
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

require_once __DIR__ . '/../inc/function.php';

/** @var PDO $db */
function getPlayerList() {
    global $db;
    $stmt = $db->prepare("SELECT player_name FROM users");
    $stmt->execute();
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
    $players = [];
    foreach ($rows as $row) {
        $players[] = $row['player_name'];
    }
    exit(json_encode(['success' => true, 'data' => $players], JSON_UNESCAPED_UNICODE));
}

if (!isLogin()) error('请先登录', 401);
if ($_SERVER['REQUEST_METHOD'] !== 'GET') error('方法不允许');
getPlayerList();
