<?php
/**
 * 充值订单对账 — 已合并到管理后台，此页面自动跳转
 */
require_once __DIR__ . '/core.php';
if (session_status() === PHP_SESSION_NONE) session_start();
if (isAdminLoggedIn()) {
    header('Location: admin.php#recharge_orders');
    exit;
}
header('Location: admin.php');
exit;
