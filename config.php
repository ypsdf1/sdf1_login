<?php
/**
 * Web通信系统 - 配置文件
 *
 * ★ 本文件随仓库分发，里面的值全部是占位符 —— 请改成你自己的。
 *   任何真实口令 / 密钥 / IP / 令牌都严禁写进仓库（2026-09-29 用户指令）。
 *   部署脚本也不会上传本文件，线上 config.php 只在服务器上由 admin_2fa_setup.php 改写。
 *
 * 拿到代码第一次要改的东西：
 *   1. SECRET_KEY       —— 插件(Java)与本 Web 端必须一字不差地相同，否则所有同步接口 401
 *   2. ADMIN_PASS       —— 后台管理密码
 *   3. SMTP_*           —— 邮件发信用途（不发信可以不管）
 *   4. GAME_*_DB / DIR  —— 你服务器上游戏数据库的实际路径
 *   最下面 SEC-UPDATE 区故意是空的：后台访问令牌、IP 白名单、二次验证这三项
 *   在你自己的服务器上首次生成 / 由你自己填，不会进仓库。
 */

// ===== 安全密钥（插件和Web端必须一致） =====
define('SECRET_KEY', 'REPLACE_ME_SECRET_KEY');

// ===== Token配置 =====
define('TOKEN_EXPIRE_SECONDS', 86400);  // 24小时

// ===== 数据库路径 =====
define('DB_PATH', __DIR__ . '/db/web.db');
// ★ 收银台订单独立库：与 web.db（Java 高频写入）彻底解耦，消除 SQLite 文件锁竞争
//   订单记录纯属 PHP 收银台自己的数据，Java 永不读写此库
define('ORDERS_DB_PATH', __DIR__ . '/db/orders.db');

// ===== 管理员认证 =====
define('ADMIN_USER', 'admin');
define('ADMIN_PASS', 'REPLACE_ME_ADMIN_PASSWORD');  // 管理员密码

// ===== 游戏数据库路径（用于同步） =====
define('GAME_BOND_DB', 'REPLACE_ME_GAME_BOND_DB_PATH');
define('GAME_LOGIN_DB', 'REPLACE_ME_GAME_LOGIN_DB_PATH');
define('GAME_SHOP_DIR', 'REPLACE_ME_GAME_SHOP_DIR_PATH');

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
define('SMTP_HOST', 'REPLACE_ME_SMTP_HOST');
define('SMTP_PORT', '465');
define('SMTP_USER', 'REPLACE_ME_SMTP_USER');
define('SMTP_PASS', 'REPLACE_ME_SMTP_PASSWORD');
define('SMTP_SENDER_NAME', 'Sdf1_login');
define('SMTP_USE_SSL', true);

// ===== 人机验证码选择（2026-10-02，实现见 captcha_guard.php）=====
// 覆盖三个场景：管理员登录 / 用户后台账密登录 / 正版验证提交前。
// 取值不区分大小写，兼容全名：
//   'CF' 或 'cloudflare' 或 'turnstile' → Cloudflare Turnstile（默认，体验最好基本无感）
//   'VA' 或 'vaptcha'                   → VAPTCHA V4
//   '' 或 'off' 或 'none'               → 关闭人机验证
// 本文件未定义本常量时（线上 config.php 是老版本），一律按默认 'CF' 处理。
// 密钥本身不在这里，写在 captcha_keys.php（仓库里是空模板，真实值只放服务器）。
define('CAPTCHA_PROVIDER', 'CF');

// ===== 紧急安全加固（2026-09-29 入侵事件响应，实现见 security.php）=====
// 第一层：TOTP 二次验证 —— 1Password / Microsoft Authenticator 等标准 6 位动态码（仅校验，无推送）。
// 第二层：管理面 IP 白名单 —— 未配置白名单时，仅服务器本机 127.0.0.1 与持有 boot 引导令牌的
//         人可访问后台，其余任何人一律 403。
// 第三层：admin.php 动态访问令牌 —— 只有 https://域名/plugin/admin.php?token=<SEC_ACCESS_TOKEN>
//         才能打开后台；不带令牌（或令牌错）的请求一律返回 nginx 原生 404 页，
//         与"服务器上根本没有这个文件"表现完全一致，探测者无从判断。
//
// 下面这个区段故意不写任何 define —— 这些是每个部署者自己的安全隐私，严禁入库：
//   SEC_BOOT_TOKEN      引导令牌（被锁在外面时的逃生舱）
//   SEC_ACCESS_TOKEN    后台访问令牌（第三层门禁）
//   SEC_EPOCH           安全纪元（部署加固时作废此前的全部活会话）
//   ADMIN_IP_WHITELIST  你自己的 IP 白名单
//   SEC_2FA_ENABLED / SEC_2FA_SECRET  你自己的二次验证开关与密钥
// 首次访问 https://域名/plugin/admin_2fa_setup.php 并保存任意一项时，缺失的键会由该页
//         在你自己的服务器上随机生成并就地补全（写前自动备份到 db/config_bak/）。
// 被锁在外面时的恢复方法：宝塔/SSH 查看本文件补全后的 SEC_BOOT_TOKEN，访问
//         https://你的域名/plugin/admin_2fa_setup.php?boot=<令牌>
//         进入配置页把自己当前 IP 加入白名单（boot 令牌本身也是配置页入口）。
// >>>SEC-UPDATE-BEGIN 本区段由 admin_2fa_setup.php 自动维护，请勿手工改动标记行
// >>>SEC-UPDATE-END
