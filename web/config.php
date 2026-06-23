<?php
/**
 * Web通信系统 - 配置文件
 */

// ===== 安全密钥（插件和Web端必须一致） =====
define('SECRET_KEY', 'sdf1_web_comm_2026_ypshidifu');

// ===== Token配置 =====
define('TOKEN_EXPIRE_SECONDS', 600);  // 10分钟

// ===== 数据库路径 =====
define('DB_PATH', __DIR__ . '/db/web.db');

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
