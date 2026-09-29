<?php
/**
 * 充值订单对账 — 已合并到管理后台，此页面自动跳转
 * ★ 第三层（2026-09-29）：admin.php 必须带入口令牌，否则会 404，这里由服务端补上。
 */
require_once __DIR__ . '/core.php';
if (session_status() === PHP_SESSION_NONE) session_start();
$to = function_exists('secTokenUrl') ? secTokenUrl('admin.php') : 'admin.php';
if (isAdminLoggedIn()) {
    header('Location: ' . $to . '#recharge_orders');
    exit;
}
header('Location: ' . $to);
exit;
