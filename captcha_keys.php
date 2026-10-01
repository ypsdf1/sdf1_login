<?php
/**
 * 人机验证码密钥（模板版 —— 里面没有任何真实密钥，可安全入库/上传）
 *
 * ⚠️ 本文件已在 .gitignore 中排除，线上真实密钥请直接在服务器上编辑本文件填写。
 *    填好后不要再把真实值提交进 git。
 *
 * ── 怎么填 ──────────────────────────────────────────────
 *
 * 1) Cloudflare Turnstile（默认使用的验证码，体验最好、无感）
 *    申请入口：Cloudflare 控制台 → Workers & Pages / Turnstile → Add site
 *      https://dash.cloudflare.com/?to=/:account/turnstile
 *    绑定域名：*.ypshidifu.cn （添加 hostname 白名单）
 *    拿到两个值：
 *      $CF_SITEKEY  → 页面上显示的 Sitekey（0x4AAAA 开头）
 *      $CF_SECRET   → 后台的 Secret key（0x4AAAA 开头，不要和 sitekey 搞混）
 *
 * 2) VAPTCHA（备用验证码，配置 CAPTCHA_PROVIDER = 'VA' 时启用）
 *    申请入口：VAPTCHA 官网控制台 → 创建应用 → 拿 VID / VKey
 *      https://www.vaptcha.com/
 *    注意：V3 的 v-cn/cdn/api.vaptcha.com 已全部失效，本项目走 V4
 *      SDK：c4.vaptcha.com/src/v4.js
 *      校验：v41.vaptcha.com/api/verify
 *    拿到两个值：
 *      $VAPTCHA_VID   → 形如 id_xxxxxxxxxxxxxxxx
 *      $VAPTCHA_VKEY  → 形如 key_xxxxxxxxxxxxxxxx
 *      $VAPTCHA_SCENE → 场景号，不确定就填 0
 *
 * 3) 用哪个验证码由 config.php 里的 CAPTCHA_PROVIDER 决定：
 *      define('CAPTCHA_PROVIDER', 'CF');   // 默认 CF（Cloudflare）
 *      define('CAPTCHA_PROVIDER', 'VA');   // 或 VAPTCHA
 *    兼容写法：cloudflare / turnstile / cf（不区分大小写）都算 CF；
 *             vaptcha / va（不区分大小写）都算 VA。
 *
 * 4) 填完本文件后，可用 captcha_test.php 自测连通性。
 * ───────────────────────────────────────────────────────
 */

// ===== Cloudflare Turnstile =====
$CF_SITEKEY = '';          // 例：'0x4AAAA...站点密钥'
$CF_SECRET   = '';          // 例：'0x4AAAA...密钥'

// ===== VAPTCHA (V4) =====
$VAPTCHA_VID   = '';        // 例：'id_xxxxxxxxxxxxxxxx'
$VAPTCHA_VKEY  = '';        // 例：'key_xxxxxxxxxxxxxxxx'
$VAPTCHA_SCENE = 0;         // 场景号，默认 0
