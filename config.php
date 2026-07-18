<?php
/**
 * Web通信系统 - 配置文件
 */

// ===== 安全密钥（插件和Web端必须一致） =====
define('SECRET_KEY', 'YOUR_SECRET_KEY');

// ===== Token配置 =====
define('TOKEN_EXPIRE_SECONDS', 86400);  // 24小时

// ===== 数据库路径 =====
define('DB_PATH', __DIR__ . '/db/web.db');
// ★ 收银台订单独立库：与 web.db（Java 高频写入）彻底解耦，消除 SQLite 文件锁竞争
//   订单记录纯属 PHP 收银台自己的数据，Java 永不读写此库
define('ORDERS_DB_PATH', __DIR__ . '/db/orders.db');

// ===== 管理员认证 =====
define('ADMIN_USER', 'admin');
define('ADMIN_PASS', 'YOUR_ADMIN_PASSWORD');  // 管理员密码

// ===== 游戏数据库路径（用于同步，需在本地配置文件中定义） =====
define('GAME_BOND_DB', defined('GAME_BOND_DB') ? GAME_BOND_DB : '/path/to/bond.db');
define('GAME_LOGIN_DB', defined('GAME_LOGIN_DB') ? GAME_LOGIN_DB : '/path/to/login.db');
define('GAME_SHOP_DIR', defined('GAME_SHOP_DIR') ? GAME_SHOP_DIR : '/path/to/shop/');

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

// ===== SMTP邮件配置（需在本地配置文件中定义） =====
define('SMTP_HOST', defined('SMTP_HOST') ? SMTP_HOST : 'smtp.example.com');
define('SMTP_PORT', defined('SMTP_PORT') ? SMTP_PORT : '465');
define('SMTP_USER', defined('SMTP_USER') ? SMTP_USER : 'your-email@example.com');
define('SMTP_PASS', defined('SMTP_PASS') ? SMTP_PASS : 'your-email-password');
define('SMTP_SENDER_NAME', defined('SMTP_SENDER_NAME') ? SMTP_SENDER_NAME : 'YourApp');
define('SMTP_USE_SSL', defined('SMTP_USE_SSL') ? SMTP_USE_SSL : true);
