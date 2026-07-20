<?php
/**
 * ★ 支付密钥文件（服务器部署用）
 *
 * 安全说明：
 *  - 本文件被 .gitignore 拉黑（绝不进入版本库），且不会通过任何自动部署脚本上传，
 *    必须手工 FTP 单独上传到 /plugin/api/ 目录。
 *  - 本文件是一个 .php：仅 define 常量、无任何 echo/输出。即便被直接 HTTP 访问，
 *    服务器执行后也只返回空白页，私钥不会泄露。
 *  - 本地 txt 文件仅作为人工本地记录，pay.php 不再读取它；服务器端只认本文件。
 *
 * 密钥配对说明（已通过密码学验证）：
 *  - PAY_MCH_PRIVATE_KEY（商户私钥）与 pay_user.publickey（商户公钥）是一对 → 用于【提交签名】。
 *  - PAY_PLATFORM_PUBLIC_KEY（平台公钥）与 pay_config.private_key（平台私钥）是一对 → 用于【验签平台回调】。
 *  - PAY_MD5_KEY 为商户 MD5 密钥（pay_user.key），注意【末尾无点】。
 */
if (!defined('PAY_PID'))                  define('PAY_PID', '1001');
if (!defined('PAY_MD5_KEY'))              define('PAY_MD5_KEY', '');
if (!defined('PAY_MCH_PRIVATE_KEY'))      define('PAY_MCH_PRIVATE_KEY', '');
if (!defined('PAY_PLATFORM_PUBLIC_KEY')) define('PAY_PLATFORM_PUBLIC_KEY', '');

// ★ 平台MySQL数据库连接凭据（彩虹易支付后台数据库）
if (!defined('PAY_MYSQL_HOST'))    define('PAY_MYSQL_HOST', '127.0.0.1');
if (!defined('PAY_MYSQL_DBNAME'))  define('PAY_MYSQL_DBNAME', '');
if (!defined('PAY_MYSQL_USER'))    define('PAY_MYSQL_USER', '');
if (!defined('PAY_MYSQL_PASS'))    define('PAY_MYSQL_PASS', '');

// ★ Microsoft Azure OAuth 配置（正版验证用）
// 客户端ID: caoyuan Azure App
if (!defined('MS_CLIENT_ID'))      define('MS_CLIENT_ID', '');
// 客户端密钥: 从Azure Portal → 证书和密码 页面获取
if (!defined('MS_CLIENT_SECRET'))  define('MS_CLIENT_SECRET', '');
// 回调地址: 必须与Azure Portal中注册的重定向URI一致
if (!defined('MS_REDIRECT_URI'))   define('MS_REDIRECT_URI', '');
// Java通信密钥（与WebManager的secretKey一致）
if (!defined('MC_AUTH_SECRET'))    define('MC_AUTH_SECRET', 'sdf1_web_comm_2026_ypshidifu');
