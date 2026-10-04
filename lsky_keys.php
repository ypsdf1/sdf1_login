<?php
/**
 * 兰空图床（Lsky Pro）API 配置 —— 工单图片上传用
 *
 * ★ 本文件仓库版是【空模板】：真实邮箱/密码只在服务器上手工放置，
 *   deploy_php_only.py 的 EXCLUDE_PATTERNS 永不上传本文件（覆盖线上即丢真值）。
 *   与 captcha_keys.php / config.php / pay_secrets.php 同一套规矩。
 *
 * ★ 图床地址（接口文档确认）：https://img.ypshidifu.cn/api/v1/
 *     取 token : POST /tokens   body {email,password}  -> data.token
 *     上传     : POST /upload   multipart: file(+strategy_id), Header: Authorization: Bearer <token>
 *
 * 服务器版真值示例（勿写进仓库）：
 *   define('LSKY_EMAIL',    '真实邮箱');
 *   define('LSKY_PASSWORD', '真实密码');
 */
define('LSKY_API_URL', 'https://img.ypshidifu.cn/api/v1');
define('LSKY_EMAIL', 'REPLACE_ME_LSKY_EMAIL');
define('LSKY_PASSWORD', 'REPLACE_ME_LSKY_PASSWORD');

// 上传走的存储策略ID：1 = 默认本地策略（接口文档 GET /strategies 返回的 id；0 = 不传该参数）
define('LSKY_STRATEGY_ID', 1);

// token 缓存文件（web.db 同目录，Web 可写；含有效期内的 token，仅服务器本地可读）
define('LSKY_TOKEN_CACHE', __DIR__ . '/db/lsky_token.json');
