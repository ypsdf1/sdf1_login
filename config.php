<?php
/**
 * Web通信系统 - 配置文件
 */

// ===== 安全密钥（插件和Web端必须一致） =====
define('SECRET_KEY', 'sdf1_web_comm_2026_ypshidifu');

// ===== Token配置 =====
define('TOKEN_EXPIRE_SECONDS', 86400);  // 24小时

// ===== 数据库路径 =====
define('DB_PATH', __DIR__ . '/db/web.db');
// ★ 收银台订单独立库：与 web.db（Java 高频写入）彻底解耦，消除 SQLite 文件锁竞争
//   订单记录纯属 PHP 收银台自己的数据，Java 永不读写此库
define('ORDERS_DB_PATH', __DIR__ . '/db/orders.db');

// ===== 管理员认证 =====
define('ADMIN_USER', 'admin');
define('ADMIN_PASS', 'ypshidifu2026');  // 管理员密码

// ===== 游戏数据库路径（用于同步） =====
define('GAME_BOND_DB', 'D:/服务器/插件/bond.db');
define('GAME_LOGIN_DB', 'D:/服务器/插件/login.db');
define('GAME_SHOP_DIR', 'D:/服务器/插件/shop/');

// ===== Web子目录路径（如 /plugin 或 /test1，根目录则留空） =====
define('WEBSUB_DIR', '/plugin');

// ===== Java插件回调端口 =====
define('CALLBACK_PORT', 9090);

// ===== Java游戏服务器地址（PHP回调Java用） =====
define('GAME_SERVER_HOST', '127.0.0.1');

// ===== 辅助函数：构建相对路径 =====
function webPath($path = '') {
    return trim(WEBSUB_DIR, '/') . ($path ? '/' . trim($path, '/') : '');
}

// ===== SMTP邮件配置 =====
define('SMTP_HOST', 'hwsmtp.exmail.qq.com');
define('SMTP_PORT', '465');
define('SMTP_USER', 'mcserver@ypshidifu.cn');
define('SMTP_PASS', 'sQ2ZiCZGq96xi9Sv');
define('SMTP_SENDER_NAME', 'Sdf1_login');
define('SMTP_USE_SSL', true);

// ===== 紧急安全加固（2026-09-29 入侵事件响应，实现见 security.php）=====
// 第一层：TOTP 二次验证 —— 1Password / Microsoft Authenticator 等标准 6 位动态码（仅校验，无推送）。
// 第二层：管理面 IP 白名单 —— ADMIN_IP_WHITELIST 为空数组时，仅服务器本机 127.0.0.1
//         与 boot 引导令牌可访问后台，其余任何人一律 403。
// 第三层：admin.php 动态访问令牌 —— 只有 https://域名/plugin/admin.php?token=<SEC_ACCESS_TOKEN>
//         才能打开后台；不带令牌（或令牌错）的请求一律返回 nginx 原生 404 页，
//         与"服务器上根本没有这个文件"表现完全一致，探测者无从判断。
// 被锁在外面时的恢复方法：宝塔/SSH 查看本文件的 SEC_BOOT_TOKEN，访问
//         https://你的域名/plugin/admin_2fa_setup.php?boot=<令牌>
//         进入配置页把自己当前 IP 加入白名单（boot 令牌本身也是配置页入口）。
// >>>SEC-UPDATE-BEGIN 本区段由 admin_2fa_setup.php 自动维护，请勿手工改动标记行
define('SEC_BOOT_TOKEN', '2132ac30a611aa6df3a8a78d17396385');
define('SEC_ACCESS_TOKEN', '7c40ad41e5a384ef4f496088a955f8f0');
define('SEC_EPOCH', 1790687661);
define('ADMIN_IP_WHITELIST', array('119.39.100.21'));
define('SEC_2FA_ENABLED', false);
define('SEC_2FA_SECRET', '');
// >>>SEC-UPDATE-END
