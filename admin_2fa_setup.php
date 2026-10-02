<?php
/**
 * 后台安全配置页（独立页面）—— 第一层 TOTP 二次验证 + 第二层 IP 白名单 的统一配置入口。
 *
 * 访问方式（三选一）：
 *   A. 已登录后台，且（2FA 未启用 或 已通过 2FA 验证）→ 直接访问
 *   B. 被白名单挡在外面：宝塔/SSH 查 config.php 的 SEC_BOOT_TOKEN，
 *      访问 admin_2fa_setup.php?boot=<令牌>（引导模式，免密码）
 *   C. 服务器本机 127.0.0.1 永远可访问（仍需登录，或用 boot）
 *
 * 所有配置写入 config.php 的 SEC-UPDATE 标记区（写前自动备份到 db/config_bak/）。
 */
require_once __DIR__ . '/security.php';
secEnsureSession();

// IP 白名单闸门 + boot 引导令牌解锁
secGatePage(true);
$boot = secBootSessionActive();

// ===== 访问模式判定 =====
// 说明：本页只依赖 security.php（不加载 core.php），
//       isAdminLoggedIn() 是 core 的函数，这里用等价的内联判定（admin_auth + 安全纪元）。
$loggedIn = !empty($_SESSION['admin_auth']) && secSessionEpochOk();
if ($boot) {
    $mode = 'boot';
} elseif ($loggedIn) {
    // 已登录：若 2FA 已启用但本会话未验证 → 先去验证页
    if (defined('SEC_2FA_ENABLED') && SEC_2FA_ENABLED && empty($_SESSION['admin_2fa_ok'])) {
        header('Location: admin_2fa.php');
        exit;
    }
    $mode = 'session';
} else {
    header('Location: admin_login.php');
    exit;
}

// ===== 当前状态（操作后会就地更新，保证同请求内显示正确）=====
$whitelist = (defined('ADMIN_IP_WHITELIST') && is_array(ADMIN_IP_WHITELIST)) ? ADMIN_IP_WHITELIST : array();
$twoFaOn = defined('SEC_2FA_ENABLED') && SEC_2FA_ENABLED;
$twoFaSecret = defined('SEC_2FA_SECRET') ? (string)SEC_2FA_SECRET : '';
// ★ 第三层：后台入口令牌（config.php 的 SEC_ACCESS_TOKEN）。
//   若旧版 config 尚未有该键，首次任何写操作会顺手生成一个（自愈），本页会把地址显示出来。
$secToken = (defined('SEC_ACCESS_TOKEN') && SEC_ACCESS_TOKEN !== '') ? (string)SEC_ACCESS_TOKEN : secNewToken();
// ★ boot 引导令牌同理：config.php 分发版里 SEC-UPDATE 区是空的（隐私信息不入库），
//   首次保存时自动生成，之后一直沿用 —— 否则逃生舱永远是空令牌、谁都进不来也等于没锁。
$secBootTok = (defined('SEC_BOOT_TOKEN') && SEC_BOOT_TOKEN !== '') ? (string)SEC_BOOT_TOKEN : secNewToken();
$pending = isset($_SESSION['sec_pending_secret']) ? (string)$_SESSION['sec_pending_secret'] : '';
$myIp = secClientIp();
$err = '';
$ok = '';

// ===== POST：所有写操作 =====
if ($_SERVER['REQUEST_METHOD'] === 'POST' && $mode !== 'none') {
    $act = isset($_POST['act']) ? (string)$_POST['act'] : '';
    $allowed = false;

    if ($mode === 'boot') {
        $allowed = true;                       // 引导模式（已持有服务器文件级凭据）免密码
    } else {
        // 第二层附加保护：改配置必须重输管理密码（防活会话被盗用者直接改安全配置）
        $lock = secThrottleLocked('setup_pw', $myIp, 5, 600);
        if ($lock > 0) {
            $err = '密码尝试过多，请 ' . $lock . ' 秒后再试';
            secLog('setup_throttled', 'lock=' . $lock . 's');
        } else {
            $pw = isset($_POST['admin_pass']) ? (string)$_POST['admin_pass'] : '';
            if ($pw !== '' && $pw === ADMIN_PASS) {
                $allowed = true;
                secThrottleReset('setup_pw', $myIp);
            } else {
                $left = secThrottleFail('setup_pw', $myIp, 5, 600);
                secLog('setup_pw_fail', $left > 0 ? 'LOCKED_' . $left . 's' : 'wrong_password');
                $err = $left > 0
                    ? '管理密码错误且尝试过多，已锁定 ' . $left . ' 秒'
                    : '管理密码错误，操作被拒绝';
            }
        }
    }

    if ($allowed) {
        try {
            // 全量写回（未提及的键保留旧值由 secWriteConfig 内部保证）
            $full = function ($over) {
                return array_merge(array(
                    // 用变量而不是常量：分发版 config.php 的 SEC-UPDATE 区是空的，
                    // 未定义时常量会触发 PHP8 undefined constant Error；这里取"当前值或本次生成的值"，
                    // 首次保存时把随机 boot 令牌 / 访问令牌 / 纪元就地补进 config.php。
                    'SEC_BOOT_TOKEN' => $GLOBALS['secBootTok'],
                    'SEC_ACCESS_TOKEN' => $GLOBALS['secToken'],
                    'SEC_EPOCH' => defined('SEC_EPOCH') ? (int)SEC_EPOCH : time(),
                    'ADMIN_IP_WHITELIST' => $GLOBALS['whitelist'],
                    'SEC_2FA_ENABLED' => $GLOBALS['twoFaOn'],
                    'SEC_2FA_SECRET' => $GLOBALS['twoFaSecret'],
                ), $over);
            };

            if ($act === 'save_whitelist') {
                $lines = preg_split('/\r\n|\r|\n/', isset($_POST['whitelist']) ? (string)$_POST['whitelist'] : '');
                $items = array();
                foreach ($lines as $L) {
                    $L = trim($L);
                    if ($L === '') continue;
                    if (filter_var($L, FILTER_VALIDATE_IP)) {
                        $items[] = $L;
                    } elseif (preg_match('#^(\d{1,3}\.){3}\d{1,3}/(\d{1,2})$#', $L, $mm)
                              && (int)$mm[2] <= 32
                              && filter_var(explode('/', $L)[0], FILTER_VALIDATE_IP)) {
                        $items[] = $L;
                    } else {
                        throw new Exception('无效的 IP / CIDR：' . htmlspecialchars($L, ENT_QUOTES, 'UTF-8'));
                    }
                }
                $items = array_values(array_unique($items));
                // 改白名单同样不许碰 2FA：写前快照 + 写后护栏（同 rotate_token）
                $twoFaSnap = secReadUpdateBlock();
                secWriteConfig($full(array('ADMIN_IP_WHITELIST' => $items)));
                secGuardTwoFaUnchanged($twoFaSnap, 'save_whitelist');
                $whitelist = $items;
                secLog('whitelist_save', 'count=' . count($items) . ' ' . implode(',', $items));
                $ok = 'IP 白名单已保存（' . count($items) . ' 条）' . (count($items) === 0 ? '。注意：白名单为空 = 仅服务器本机可访问后台！' : '');
            } elseif ($act === 'twofa_begin') {
                // ★ 幂等：已有待绑定密钥就继续用它。
                //   以前每次点都换新密钥 —— 用户手里的认证器可能刚扫/刚录完旧的，
                //   再点一次就把那条作废了，之后所有动态码"永远无效"，极难排查。
                if ($pending === '') {
                    $_SESSION['sec_pending_secret'] = secNewSecret();
                    $pending = $_SESSION['sec_pending_secret'];
                    secLog('twofa_begin', 'pending secret generated');
                    $ok = '密钥已生成：请扫码或手动录入后，提交下方验证码完成绑定';
                } else {
                    secLog('twofa_begin', 'reuse pending secret');
                    $ok = '沿用已生成的密钥（避免认证器里出现多条作废记录）。要换新请点「重新生成密钥」。';
                }
            } elseif ($act === 'twofa_regen') {
                $_SESSION['sec_pending_secret'] = secNewSecret();
                $pending = $_SESSION['sec_pending_secret'];
                secLog('twofa_regen', 'pending secret regenerated, old one discarded');
                $ok = '密钥已重新生成 —— 之前录入认证器的那条已作废，请用新二维码/新密钥重新添加';
            } elseif ($act === 'twofa_finish') {
                if ($pending === '') throw new Exception('没有待验证的密钥，请先点击"生成密钥"');
                $code = isset($_POST['code']) ? (string)$_POST['code'] : '';
                $lock2 = secThrottleLocked('twofa_bind', $myIp, 5, 600);
                if ($lock2 > 0) throw new Exception('尝试过多，请 ' . $lock2 . ' 秒后再试');
                if (!secTotpVerify($pending, $code)) {
                    $left2 = secThrottleFail('twofa_bind', $myIp, 5, 600);
                    // ★ 诊断：区分"认证器里根本不是这把密钥"与"设备时钟偏了"
                    $off = secTotpDiagnose($pending, $code);
                    secLog('twofa_bind_fail', $left2 > 0 ? 'LOCKED'
                        : ($off === null ? 'wrong_secret' : 'clock_skew ' . $off . 's'));
                    if ($left2 > 0) throw new Exception('尝试过多，请 ' . $left2 . ' 秒后再试');
                    throw new Exception('绑定未完成：' . secTotpDiagnoseMsg($off));
                }
                secWriteConfig($full(array('SEC_2FA_ENABLED' => true, 'SEC_2FA_SECRET' => $pending)));
                $twoFaOn = true;
                $twoFaSecret = $pending;
                unset($_SESSION['sec_pending_secret']);
                $pending = '';
                secThrottleReset('twofa_bind', $myIp);
                secLog('twofa_enabled', '');
                $ok = '✅ 二次验证已启用！之后每次登录后台都必须输入 6 位动态码（当前会话也需重新验证）';
            } elseif ($act === 'rotate_token') {
                // ★ 防误触兜底（2026-09-30 事故）：本操作曾是表单第一个 submit 按钮，
                //   任何文本框按回车都会隐式提交命中它。现在只有点了「重新生成令牌」
                //   按钮并确认对话框（rotate_confirm=1）才执行；回车等路径一律拒绝。
                if (!isset($_POST['rotate_confirm']) || (string)$_POST['rotate_confirm'] !== '1') {
                    secLog('token_rotate_denied', '缺少显式确认标记（疑似回车隐式提交），已拒绝');
                    throw new Exception('已拦截一次非按钮发起的令牌重置（防误触）。请点「重新生成令牌」按钮并在确认框中点确定。');
                }
                // ★ 需求：重置访问密钥不得关闭二次验证。
                //   1) 写前先拍一张 SEC-UPDATE 区快照；
                //   2) 最小写入 —— 本次只提交"两个令牌键"，绝不再走 $full() 把内存里的
                //      2FA / 白名单 / 纪元一起带进去（secWriteConfig 对未提及的键一律按
                //      磁盘旧值原样保留，所以那些键连一个字节都不会动）。
                //      顺带写 SEC_BOOT_TOKEN 是为了：分发版 config.php 的区段是空的，
                //      页面上算出来的引导令牌必须落盘一次，否则刷新就变、逃生舱永远用不了。
                //   3) 写后由 secGuardTwoFaUnchanged 复核磁盘上的 2FA 状态，
                //      只要被打开/关闭或密钥被清空就立刻回滚并报错。
                $twoFaSnap = secReadUpdateBlock();
                $newTok = secNewToken();
                secWriteConfig(array(
                    'SEC_ACCESS_TOKEN' => $newTok,
                    'SEC_BOOT_TOKEN' => $GLOBALS['secBootTok'],
                ));
                secGuardTwoFaUnchanged($twoFaSnap, 'rotate_token');
                $secToken = $newTok;
                secLog('token_rotate', 'admin.php 入口令牌已更换，2FA 保持' . ($twoFaOn ? '启用' : '原样'));
                $ok = '后台入口令牌已重新生成，旧地址立即失效（返回 404）。二次验证状态不受影响（仍为'
                    . ($twoFaOn ? '已启用 ✅' : '未启用') . '）。请复制下方新地址并更新书签。';
            } elseif ($act === 'twofa_disable') {
                if ($mode !== 'boot') {
                    // 非引导模式：必须出示当前动态码（证明认证器在手）
                    $code = isset($_POST['code']) ? (string)$_POST['code'] : '';
                    if (!secTotpVerify($twoFaSecret, $code)) {
                        $offD = secTotpDiagnose($twoFaSecret, $code);
                        secLog('twofa_disable_fail', $offD === null ? 'wrong_secret' : 'clock_skew ' . $offD . 's');
                        throw new Exception('未执行解绑：' . secTotpDiagnoseMsg($offD));
                    }
                }
                secWriteConfig($full(array('SEC_2FA_ENABLED' => false, 'SEC_2FA_SECRET' => '')));
                $twoFaOn = false;
                $twoFaSecret = '';
                unset($_SESSION['sec_pending_secret']);
                $pending = '';
                secLog('twofa_disabled', 'mode=' . $mode);
                $ok = '二次验证已关闭' . ($mode === 'boot' ? '（引导模式强制关闭）' : '');
            } else {
                throw new Exception('未知操作');
            }
        } catch (Exception $e) {
            $err = $e->getMessage();
        }
    }
}

// ===== 恢复访问地址（引导令牌入口，供状态卡片展示）=====
$reqPath = parse_url(isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '/', PHP_URL_PATH);
$baseDir = rtrim(dirname($reqPath), '/');
$scheme = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on') ? 'https' : 'http';
$host = isset($_SERVER['HTTP_HOST']) ? $_SERVER['HTTP_HOST'] : '';
$recoverUrl = $scheme . '://' . $host . $baseDir . '/admin_2fa_setup.php?boot=' . $secBootTok;
// ★ 第三层：后台唯一正确入口（不带令牌访问 admin.php 一律 404）
$adminUrl = $scheme . '://' . $host . $baseDir . '/admin.php?token=' . rawurlencode($secToken);

// ===== 安全审计日志（最近 8 条）=====
$logLines = array();
$secLogPath = __DIR__ . '/db/security.log';
if (file_exists($secLogPath)) {
    $all = @file($secLogPath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if (is_array($all)) $logLines = array_slice($all, -8);
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SDF1 - 后台安全配置</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { background: #0d1117; color: #e6edf3; font-family: 'Segoe UI', system-ui, sans-serif; padding: 24px 16px; }
        .wrap { max-width: 760px; margin: 0 auto; }
        h1 { color: #58a6ff; font-size: 22px; margin-bottom: 6px; }
        .sub { color: #8b949e; font-size: 13px; margin-bottom: 18px; }
        .card { background: #161b22; border: 1px solid #30363d; border-radius: 10px; padding: 20px; margin-bottom: 16px; }
        .card h2 { font-size: 15px; color: #58a6ff; margin-bottom: 12px; }
        .badge { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 11px; font-weight: 600; margin-left: 8px; vertical-align: middle; }
        .b-on { background: rgba(63,185,80,0.15); color: #3fb950; }
        .b-off { background: rgba(248,81,73,0.15); color: #f85149; }
        .b-boot { background: rgba(210,153,34,0.2); color: #d29922; }
        .row { display: flex; gap: 8px; margin-bottom: 10px; align-items: center; flex-wrap: wrap; }
        label { font-size: 12px; color: #8b949e; min-width: 92px; }
        textarea, input[type=password], input[type=text] {
            width: 100%; padding: 10px 12px; background: #0d1117; border: 1px solid #30363d;
            border-radius: 6px; color: #e6edf3; font-size: 13px; outline: none; font-family: monospace;
        }
        textarea:focus, input:focus { border-color: #58a6ff; }
        textarea { min-height: 92px; resize: vertical; }
        .btn { padding: 9px 18px; border: none; border-radius: 6px; cursor: pointer; font-size: 13px; font-weight: 600; }
        .btn-blue { background: #58a6ff; color: #fff; }
        .btn-green { background: #3fb950; color: #fff; }
        .btn-red { background: #f85149; color: #fff; }
        .btn-gray { background: #30363d; color: #e6edf3; }
        .btn:hover { opacity: 0.85; }
        .hint { font-size: 12px; color: #8b949e; line-height: 1.8; margin-top: 8px; }
        .hint code { color: #d29922; background: #0d1117; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
        .msg { padding: 10px 14px; border-radius: 6px; font-size: 13px; margin-bottom: 14px; display: block; word-break: break-all; }
        .msg.err { background: rgba(248,81,73,0.1); border: 1px solid rgba(248,81,73,0.35); color: #f85149; }
        .msg.ok { background: rgba(63,185,80,0.1); border: 1px solid rgba(63,185,80,0.35); color: #3fb950; }
        .secret-box { background: #0d1117; border: 1px dashed #d29922; border-radius: 8px; padding: 14px; margin: 10px 0; }
        .secret-box .lbl { font-size: 11px; color: #8b949e; margin-bottom: 4px; }
        .secret-box .val { font-family: monospace; font-size: 15px; color: #d29922; word-break: break-all; }
        .secret-box .uri { font-family: monospace; font-size: 11px; color: #8b949e; word-break: break-all; margin-top: 8px; }
        .qrbox { background: #ffffff; border-radius: 8px; padding: 12px; display: inline-block; line-height: 0; }
        .qrbox svg { width: 190px; height: 190px; display: block; shape-rendering: crispEdges; }
        .qrbox .fail { color: #333; font-size: 12px; line-height: 1.7; padding: 6px; }
        .copy { background: none; border: 1px solid #30363d; color: #58a6ff; border-radius: 4px; padding: 2px 8px; font-size: 11px; cursor: pointer; margin-left: 6px; }
        .status-list { font-size: 13px; line-height: 2; }
        .status-list b { color: #e6edf3; }
        .wl-item { display: inline-block; background: #0d1117; border: 1px solid #30363d; border-radius: 999px; padding: 3px 12px; margin: 3px 6px 3px 0; font-size: 12px; font-family: monospace; color: #3fb950; }
        .logbox { background: #0d1117; border: 1px solid #30363d; border-radius: 6px; padding: 10px 12px; font-family: monospace; font-size: 11px; color: #8b949e; line-height: 1.9; max-height: 180px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; }
        a { color: #58a6ff; text-decoration: none; font-size: 13px; }
        .top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; flex-wrap: wrap; gap: 8px; }
        .pwd-row { background: #0d1117; border: 1px solid #30363d; border-radius: 8px; padding: 12px 14px; margin-bottom: 16px; }
    </style>
</head>
<body>
<div class="wrap">
    <div class="top">
        <h1>🔐 后台安全配置</h1>
        <div>
            <?php if ($mode === 'boot'): ?><span class="badge b-boot">引导模式（boot 令牌）</span><?php endif; ?>
            <a href="<?php echo $twoFaOn ? 'admin_2fa.php' : htmlspecialchars(secTokenUrl('admin.php'), ENT_QUOTES, 'UTF-8'); ?>">← 返回</a>
        </div>
    </div>
    <div class="sub">第一层：TOTP 二次验证（6 位动态码）　|　第二层：管理面 IP 白名单（未配置时仅本机可访问）</div>

    <?php if ($err !== ''): ?><span class="msg err">❌ <?php echo $err; ?></span><?php endif; ?>
    <?php if ($ok !== ''): ?><span class="msg ok">✅ <?php echo $ok; ?></span><?php endif; ?>

    <form method="post" autocomplete="off" id="secForm">
        <input type="hidden" name="act" id="act" value="">
        <input type="hidden" name="rotate_confirm" id="rotateConfirmBox" value="">

        <?php if ($mode === 'session'): ?>
        <div class="pwd-row">
            <div class="row" style="margin-bottom:0">
                <label>管理密码</label>
                <input type="password" name="admin_pass" placeholder="所有写操作都需重输管理密码（boot 引导模式免）">
            </div>
        </div>
        <?php endif; ?>

        <!-- ===== 当前状态 ===== -->
        <div class="card">
            <h2>📋 当前状态</h2>
            <div class="status-list">
                门禁状态：<b><?php echo secIsBootstrapped() ? '已引导 · 强制模式（不在白名单的请求一律 nginx 404）✅' : '引导模式 · 尚未保存过配置（首次访问先放行，保存任意一项即转强制）⚠️'; ?></b><br>
                二次验证：<b><?php echo $twoFaOn ? '已启用 ✅' : '未启用 ⚠️'; ?></b>
                <span class="badge <?php echo $twoFaOn ? 'b-on' : 'b-off'; ?>"><?php echo $twoFaOn ? 'ON' : 'OFF'; ?></span><br>
                IP 白名单：<b><?php echo count($whitelist) === 0 ? '空（仅服务器本机可访问）⚠️' : count($whitelist) . ' 条'; ?></b><br>
                你的 IP：<b style="font-family:monospace;color:#58a6ff"><?php echo htmlspecialchars($myIp, ENT_QUOTES, 'UTF-8'); ?></b><br>
                安全纪元：<b><?php echo secEpoch() > 0 ? date('Y-m-d H:i:s', secEpoch()) : '未设置（首次保存时自动定格）'; ?></b>（此前的登录会话已全部作废）<br>
                后台入口令牌：<b style="font-family:monospace;color:#3fb950"><?php echo htmlspecialchars($secToken, ENT_QUOTES, 'UTF-8'); ?></b>
                <button type="button" class="copy" onclick="copyText('<?php echo htmlspecialchars($secToken, ENT_QUOTES, 'UTF-8'); ?>', this)">复制</button><br>
                引导令牌：<b style="font-family:monospace;color:#d29922"><?php echo htmlspecialchars($secBootTok, ENT_QUOTES, 'UTF-8'); ?></b>
                <button type="button" class="copy" onclick="copyText('<?php echo htmlspecialchars($secBootTok, ENT_QUOTES, 'UTF-8'); ?>', this)">复制</button>
                <div class="hint">🔖 后台唯一入口（请收藏这一条，不带 token 访问 admin.php 一律返回 404）：<br><code><?php echo htmlspecialchars($adminUrl, ENT_QUOTES, 'UTF-8'); ?></code>
                <button type="button" class="copy" onclick="copyText('<?php echo htmlspecialchars($adminUrl, ENT_QUOTES, 'UTF-8'); ?>', this)">复制地址</button>
                <button type="submit" class="btn btn-gray" style="margin-left:6px" onclick="return rotateConfirm()">重新生成令牌</button></div>
                <div class="hint">恢复访问地址：<code><?php echo htmlspecialchars($recoverUrl, ENT_QUOTES, 'UTF-8'); ?></code>
                <button type="button" class="copy" onclick="copyText('<?php echo htmlspecialchars($recoverUrl, ENT_QUOTES, 'UTF-8'); ?>', this)">复制</button></div>
            </div>
        </div>

        <!-- ===== 第二层：IP 白名单 ===== -->
        <div class="card">
            <h2>🚪 第二层 · IP 白名单</h2>
            <?php if (count($whitelist) > 0): ?>
                <div style="margin-bottom:10px"><?php foreach ($whitelist as $w): ?><span class="wl-item"><?php echo htmlspecialchars($w, ENT_QUOTES, 'UTF-8'); ?></span><?php endforeach; ?></div>
            <?php endif; ?>
            <textarea name="whitelist" id="wlBox" placeholder="每行一个 IP 或 CIDR 网段，例如：&#10;203.0.113.8&#10;192.168.1.0/24"><?php echo htmlspecialchars(implode("\n", $whitelist), ENT_QUOTES, 'UTF-8'); ?></textarea>
            <div class="row" style="margin-top:10px">
                <button type="button" class="btn btn-gray" onclick="addMyIp()">＋ 把我的 IP 加入</button>
                <button type="submit" class="btn btn-blue" onclick="setAct('save_whitelist')">保存白名单</button>
                <button type="submit" class="btn btn-red" onclick="setAct('save_whitelist');">清空白名单（锁死到仅本机）</button>
            </div>
            <div class="hint">
                · 白名单<b style="color:#f85149">为空 = 除服务器本机外任何人都无法访问后台</b>（包括登录页与本页）。<br>
                · 支持 CIDR 网段（家用动态 IP 可填运营商段，如 <code>x.x.x.0/24</code>，慎用大段）。<br>
                · 动态 IP 变更后进不来 → 用上方引导令牌恢复访问。
            </div>
        </div>

        <!-- ===== 第一层：二次验证 ===== -->
        <div class="card">
            <h2>🛡️ 第一层 · 二次验证（TOTP 6 位动态码）<span class="badge <?php echo $twoFaOn ? 'b-on' : 'b-off'; ?>"><?php echo $twoFaOn ? '已启用' : '未启用'; ?></span></h2>

            <?php if (!$twoFaOn && $pending === ''): ?>
                <div class="hint" style="margin-top:0">未启用。启用后，每次登录后台都需在认证器 App 中输入 6 位动态码（无推送，纯本地校验）。</div>
                <div class="row" style="margin-top:10px">
                    <button type="submit" class="btn btn-green" onclick="setAct('twofa_begin')">生成密钥并绑定</button>
                </div>
            <?php endif; ?>

            <?php if (!$twoFaOn && $pending !== ''): ?>
                <div class="secret-box">
                    <div class="lbl" style="font-size:13px;color:#e6edf3;margin-bottom:8px">📱 第 1 步：用认证器扫码添加（打开 App 的「扫描二维码 / Setup another account」）</div>
                    <div id="qrBox" class="qrbox"></div>
                    <div class="lbl" id="qrHint">扫码会自动填好密钥与参数，避免手工输入 32 位密钥时敲错字符</div>
                    <div class="lbl" style="margin-top:14px">Base32 密钥（认证器选择"无法扫描/手动输入密钥"时填这个）
                        <button type="button" class="copy" onclick="copyText('<?php echo htmlspecialchars($pending, ENT_QUOTES, 'UTF-8'); ?>', this)">复制</button></div>
                    <div class="val"><?php echo htmlspecialchars($pending, ENT_QUOTES, 'UTF-8'); ?></div>
                    <div class="lbl" style="margin-top:10px">otpauth 链接（部分 App 支持粘贴）
                        <button type="button" class="copy" onclick="copyText('<?php echo htmlspecialchars(secOtpauthUri($pending), ENT_QUOTES, 'UTF-8'); ?>', this)">复制</button></div>
                    <div class="uri"><?php echo htmlspecialchars(secOtpauthUri($pending), ENT_QUOTES, 'UTF-8'); ?></div>
                </div>
                <div class="row">
                    <label>第 2 步 · 验证码</label>
                    <input type="text" name="code" id="codeBox" inputmode="numeric" maxlength="6" placeholder="认证器上的 6 位数字" style="max-width:200px">
                    <button type="submit" class="btn btn-green" onclick="setAct('twofa_finish')">确认绑定</button>
                    <button type="submit" class="btn btn-gray" onclick="return regenConfirm()" formnovalidate>重新生成密钥</button>
                </div>
                <div class="hint">扫码（或手动输入密钥）→ 认证器上出现 6 位数字 → 回填并点「确认绑定」。
                    提交被拒时会明确告诉你原因：是<b>密钥没对上</b>还是<b>手机时钟偏了</b>多少秒。<br>
                    <b style="color:#f85149">⚠ 绑定完成前不要点「重新生成密钥」</b> —— 一旦换新，认证器里已录入的那条立即作废，
                    之后提交的动态码会全部被拒（这正是"验证码一直无效"最常见的原因）。</div>
            <?php endif; ?>

            <?php if ($twoFaOn): ?>
                <div class="row">
                    <label>解绑验证码</label>
                    <input type="text" name="code" id="codeBox" inputmode="numeric" maxlength="6" placeholder="当前 6 位动态码<?php echo $mode === 'boot' ? '（引导模式可留空）' : ''; ?>" style="max-width:220px">
                    <button type="submit" class="btn btn-red" onclick="setAct('twofa_disable')">关闭二次验证</button>
                </div>
                <div class="hint">关闭需出示当前动态码（证明认证器在手）；boot 引导模式可强制关闭（灾难恢复）。关闭后请尽快重新绑定。<br>
                    <b style="color:#d29922">动态码一直被拒？</b>先看报错提示是"密钥没对上"还是"时钟偏了多少秒"：
                    前者用状态卡里的引导令牌地址进入本页强制关闭后重新扫码绑定；后者把手机时间改成自动同步即可。</div>
            <?php endif; ?>
        </div>

        <!-- ===== 审计日志 ===== -->
        <div class="card">
            <h2>📜 安全审计（db/security.log 最近 8 条）</h2>
            <div class="logbox"><?php echo $logLines ? htmlspecialchars(implode("\n", $logLines), ENT_QUOTES, 'UTF-8') : '（暂无记录）'; ?></div>
        </div>
    </form>
</div>

<!-- QR Code Generator for JavaScript v1.4.4 | MIT | Copyright (c) 2009 Kazuhiko Arase
     离线内联，不外联任何 CDN：绑定 2FA 时就地生成 otpauth 二维码，扫码即可把密钥
     写入 1Password / Microsoft Authenticator / Google Authenticator，免手工输入。 -->
<script>
//---------------------------------------------------------------------
//
// QR Code Generator for JavaScript
//
// Copyright (c) 2009 Kazuhiko Arase
//
// URL: http://www.d-project.com/
//
// Licensed under the MIT license:
//  http://www.opensource.org/licenses/mit-license.php
//
// The word 'QR Code' is registered trademark of
// DENSO WAVE INCORPORATED
//  http://www.denso-wave.com/qrcode/faqpatent-e.html
//
//---------------------------------------------------------------------

var qrcode = function() {

  //---------------------------------------------------------------------
  // qrcode
  //---------------------------------------------------------------------

  /**
   * qrcode
   * @param typeNumber 1 to 40
   * @param errorCorrectionLevel 'L','M','Q','H'
   */
  var qrcode = function(typeNumber, errorCorrectionLevel) {

    var PAD0 = 0xEC;
    var PAD1 = 0x11;

    var _typeNumber = typeNumber;
    var _errorCorrectionLevel = QRErrorCorrectionLevel[errorCorrectionLevel];
    var _modules = null;
    var _moduleCount = 0;
    var _dataCache = null;
    var _dataList = [];

    var _this = {};

    var makeImpl = function(test, maskPattern) {

      _moduleCount = _typeNumber * 4 + 17;
      _modules = function(moduleCount) {
        var modules = new Array(moduleCount);
        for (var row = 0; row < moduleCount; row += 1) {
          modules[row] = new Array(moduleCount);
          for (var col = 0; col < moduleCount; col += 1) {
            modules[row][col] = null;
          }
        }
        return modules;
      }(_moduleCount);

      setupPositionProbePattern(0, 0);
      setupPositionProbePattern(_moduleCount - 7, 0);
      setupPositionProbePattern(0, _moduleCount - 7);
      setupPositionAdjustPattern();
      setupTimingPattern();
      setupTypeInfo(test, maskPattern);

      if (_typeNumber >= 7) {
        setupTypeNumber(test);
      }

      if (_dataCache == null) {
        _dataCache = createData(_typeNumber, _errorCorrectionLevel, _dataList);
      }

      mapData(_dataCache, maskPattern);
    };

    var setupPositionProbePattern = function(row, col) {

      for (var r = -1; r <= 7; r += 1) {

        if (row + r <= -1 || _moduleCount <= row + r) continue;

        for (var c = -1; c <= 7; c += 1) {

          if (col + c <= -1 || _moduleCount <= col + c) continue;

          if ( (0 <= r && r <= 6 && (c == 0 || c == 6) )
              || (0 <= c && c <= 6 && (r == 0 || r == 6) )
              || (2 <= r && r <= 4 && 2 <= c && c <= 4) ) {
            _modules[row + r][col + c] = true;
          } else {
            _modules[row + r][col + c] = false;
          }
        }
      }
    };

    var getBestMaskPattern = function() {

      var minLostPoint = 0;
      var pattern = 0;

      for (var i = 0; i < 8; i += 1) {

        makeImpl(true, i);

        var lostPoint = QRUtil.getLostPoint(_this);

        if (i == 0 || minLostPoint > lostPoint) {
          minLostPoint = lostPoint;
          pattern = i;
        }
      }

      return pattern;
    };

    var setupTimingPattern = function() {

      for (var r = 8; r < _moduleCount - 8; r += 1) {
        if (_modules[r][6] != null) {
          continue;
        }
        _modules[r][6] = (r % 2 == 0);
      }

      for (var c = 8; c < _moduleCount - 8; c += 1) {
        if (_modules[6][c] != null) {
          continue;
        }
        _modules[6][c] = (c % 2 == 0);
      }
    };

    var setupPositionAdjustPattern = function() {

      var pos = QRUtil.getPatternPosition(_typeNumber);

      for (var i = 0; i < pos.length; i += 1) {

        for (var j = 0; j < pos.length; j += 1) {

          var row = pos[i];
          var col = pos[j];

          if (_modules[row][col] != null) {
            continue;
          }

          for (var r = -2; r <= 2; r += 1) {

            for (var c = -2; c <= 2; c += 1) {

              if (r == -2 || r == 2 || c == -2 || c == 2
                  || (r == 0 && c == 0) ) {
                _modules[row + r][col + c] = true;
              } else {
                _modules[row + r][col + c] = false;
              }
            }
          }
        }
      }
    };

    var setupTypeNumber = function(test) {

      var bits = QRUtil.getBCHTypeNumber(_typeNumber);

      for (var i = 0; i < 18; i += 1) {
        var mod = (!test && ( (bits >> i) & 1) == 1);
        _modules[Math.floor(i / 3)][i % 3 + _moduleCount - 8 - 3] = mod;
      }

      for (var i = 0; i < 18; i += 1) {
        var mod = (!test && ( (bits >> i) & 1) == 1);
        _modules[i % 3 + _moduleCount - 8 - 3][Math.floor(i / 3)] = mod;
      }
    };

    var setupTypeInfo = function(test, maskPattern) {

      var data = (_errorCorrectionLevel << 3) | maskPattern;
      var bits = QRUtil.getBCHTypeInfo(data);

      // vertical
      for (var i = 0; i < 15; i += 1) {

        var mod = (!test && ( (bits >> i) & 1) == 1);

        if (i < 6) {
          _modules[i][8] = mod;
        } else if (i < 8) {
          _modules[i + 1][8] = mod;
        } else {
          _modules[_moduleCount - 15 + i][8] = mod;
        }
      }

      // horizontal
      for (var i = 0; i < 15; i += 1) {

        var mod = (!test && ( (bits >> i) & 1) == 1);

        if (i < 8) {
          _modules[8][_moduleCount - i - 1] = mod;
        } else if (i < 9) {
          _modules[8][15 - i - 1 + 1] = mod;
        } else {
          _modules[8][15 - i - 1] = mod;
        }
      }

      // fixed module
      _modules[_moduleCount - 8][8] = (!test);
    };

    var mapData = function(data, maskPattern) {

      var inc = -1;
      var row = _moduleCount - 1;
      var bitIndex = 7;
      var byteIndex = 0;
      var maskFunc = QRUtil.getMaskFunction(maskPattern);

      for (var col = _moduleCount - 1; col > 0; col -= 2) {

        if (col == 6) col -= 1;

        while (true) {

          for (var c = 0; c < 2; c += 1) {

            if (_modules[row][col - c] == null) {

              var dark = false;

              if (byteIndex < data.length) {
                dark = ( ( (data[byteIndex] >>> bitIndex) & 1) == 1);
              }

              var mask = maskFunc(row, col - c);

              if (mask) {
                dark = !dark;
              }

              _modules[row][col - c] = dark;
              bitIndex -= 1;

              if (bitIndex == -1) {
                byteIndex += 1;
                bitIndex = 7;
              }
            }
          }

          row += inc;

          if (row < 0 || _moduleCount <= row) {
            row -= inc;
            inc = -inc;
            break;
          }
        }
      }
    };

    var createBytes = function(buffer, rsBlocks) {

      var offset = 0;

      var maxDcCount = 0;
      var maxEcCount = 0;

      var dcdata = new Array(rsBlocks.length);
      var ecdata = new Array(rsBlocks.length);

      for (var r = 0; r < rsBlocks.length; r += 1) {

        var dcCount = rsBlocks[r].dataCount;
        var ecCount = rsBlocks[r].totalCount - dcCount;

        maxDcCount = Math.max(maxDcCount, dcCount);
        maxEcCount = Math.max(maxEcCount, ecCount);

        dcdata[r] = new Array(dcCount);

        for (var i = 0; i < dcdata[r].length; i += 1) {
          dcdata[r][i] = 0xff & buffer.getBuffer()[i + offset];
        }
        offset += dcCount;

        var rsPoly = QRUtil.getErrorCorrectPolynomial(ecCount);
        var rawPoly = qrPolynomial(dcdata[r], rsPoly.getLength() - 1);

        var modPoly = rawPoly.mod(rsPoly);
        ecdata[r] = new Array(rsPoly.getLength() - 1);
        for (var i = 0; i < ecdata[r].length; i += 1) {
          var modIndex = i + modPoly.getLength() - ecdata[r].length;
          ecdata[r][i] = (modIndex >= 0)? modPoly.getAt(modIndex) : 0;
        }
      }

      var totalCodeCount = 0;
      for (var i = 0; i < rsBlocks.length; i += 1) {
        totalCodeCount += rsBlocks[i].totalCount;
      }

      var data = new Array(totalCodeCount);
      var index = 0;

      for (var i = 0; i < maxDcCount; i += 1) {
        for (var r = 0; r < rsBlocks.length; r += 1) {
          if (i < dcdata[r].length) {
            data[index] = dcdata[r][i];
            index += 1;
          }
        }
      }

      for (var i = 0; i < maxEcCount; i += 1) {
        for (var r = 0; r < rsBlocks.length; r += 1) {
          if (i < ecdata[r].length) {
            data[index] = ecdata[r][i];
            index += 1;
          }
        }
      }

      return data;
    };

    var createData = function(typeNumber, errorCorrectionLevel, dataList) {

      var rsBlocks = QRRSBlock.getRSBlocks(typeNumber, errorCorrectionLevel);

      var buffer = qrBitBuffer();

      for (var i = 0; i < dataList.length; i += 1) {
        var data = dataList[i];
        buffer.put(data.getMode(), 4);
        buffer.put(data.getLength(), QRUtil.getLengthInBits(data.getMode(), typeNumber) );
        data.write(buffer);
      }

      // calc num max data.
      var totalDataCount = 0;
      for (var i = 0; i < rsBlocks.length; i += 1) {
        totalDataCount += rsBlocks[i].dataCount;
      }

      if (buffer.getLengthInBits() > totalDataCount * 8) {
        throw 'code length overflow. ('
          + buffer.getLengthInBits()
          + '>'
          + totalDataCount * 8
          + ')';
      }

      // end code
      if (buffer.getLengthInBits() + 4 <= totalDataCount * 8) {
        buffer.put(0, 4);
      }

      // padding
      while (buffer.getLengthInBits() % 8 != 0) {
        buffer.putBit(false);
      }

      // padding
      while (true) {

        if (buffer.getLengthInBits() >= totalDataCount * 8) {
          break;
        }
        buffer.put(PAD0, 8);

        if (buffer.getLengthInBits() >= totalDataCount * 8) {
          break;
        }
        buffer.put(PAD1, 8);
      }

      return createBytes(buffer, rsBlocks);
    };

    _this.addData = function(data, mode) {

      mode = mode || 'Byte';

      var newData = null;

      switch(mode) {
      case 'Numeric' :
        newData = qrNumber(data);
        break;
      case 'Alphanumeric' :
        newData = qrAlphaNum(data);
        break;
      case 'Byte' :
        newData = qr8BitByte(data);
        break;
      case 'Kanji' :
        newData = qrKanji(data);
        break;
      default :
        throw 'mode:' + mode;
      }

      _dataList.push(newData);
      _dataCache = null;
    };

    _this.isDark = function(row, col) {
      if (row < 0 || _moduleCount <= row || col < 0 || _moduleCount <= col) {
        throw row + ',' + col;
      }
      return _modules[row][col];
    };

    _this.getModuleCount = function() {
      return _moduleCount;
    };

    _this.make = function() {
      if (_typeNumber < 1) {
        var typeNumber = 1;

        for (; typeNumber < 40; typeNumber++) {
          var rsBlocks = QRRSBlock.getRSBlocks(typeNumber, _errorCorrectionLevel);
          var buffer = qrBitBuffer();

          for (var i = 0; i < _dataList.length; i++) {
            var data = _dataList[i];
            buffer.put(data.getMode(), 4);
            buffer.put(data.getLength(), QRUtil.getLengthInBits(data.getMode(), typeNumber) );
            data.write(buffer);
          }

          var totalDataCount = 0;
          for (var i = 0; i < rsBlocks.length; i++) {
            totalDataCount += rsBlocks[i].dataCount;
          }

          if (buffer.getLengthInBits() <= totalDataCount * 8) {
            break;
          }
        }

        _typeNumber = typeNumber;
      }

      makeImpl(false, getBestMaskPattern() );
    };

    _this.createTableTag = function(cellSize, margin) {

      cellSize = cellSize || 2;
      margin = (typeof margin == 'undefined')? cellSize * 4 : margin;

      var qrHtml = '';

      qrHtml += '<table style="';
      qrHtml += ' border-width: 0px; border-style: none;';
      qrHtml += ' border-collapse: collapse;';
      qrHtml += ' padding: 0px; margin: ' + margin + 'px;';
      qrHtml += '">';
      qrHtml += '<tbody>';

      for (var r = 0; r < _this.getModuleCount(); r += 1) {

        qrHtml += '<tr>';

        for (var c = 0; c < _this.getModuleCount(); c += 1) {
          qrHtml += '<td style="';
          qrHtml += ' border-width: 0px; border-style: none;';
          qrHtml += ' border-collapse: collapse;';
          qrHtml += ' padding: 0px; margin: 0px;';
          qrHtml += ' width: ' + cellSize + 'px;';
          qrHtml += ' height: ' + cellSize + 'px;';
          qrHtml += ' background-color: ';
          qrHtml += _this.isDark(r, c)? '#000000' : '#ffffff';
          qrHtml += ';';
          qrHtml += '"/>';
        }

        qrHtml += '</tr>';
      }

      qrHtml += '</tbody>';
      qrHtml += '</table>';

      return qrHtml;
    };

    _this.createSvgTag = function(cellSize, margin, alt, title) {

      var opts = {};
      if (typeof arguments[0] == 'object') {
        // Called by options.
        opts = arguments[0];
        // overwrite cellSize and margin.
        cellSize = opts.cellSize;
        margin = opts.margin;
        alt = opts.alt;
        title = opts.title;
      }

      cellSize = cellSize || 2;
      margin = (typeof margin == 'undefined')? cellSize * 4 : margin;

      // Compose alt property surrogate
      alt = (typeof alt === 'string') ? {text: alt} : alt || {};
      alt.text = alt.text || null;
      alt.id = (alt.text) ? alt.id || 'qrcode-description' : null;

      // Compose title property surrogate
      title = (typeof title === 'string') ? {text: title} : title || {};
      title.text = title.text || null;
      title.id = (title.text) ? title.id || 'qrcode-title' : null;

      var size = _this.getModuleCount() * cellSize + margin * 2;
      var c, mc, r, mr, qrSvg='', rect;

      rect = 'l' + cellSize + ',0 0,' + cellSize +
        ' -' + cellSize + ',0 0,-' + cellSize + 'z ';

      qrSvg += '<svg version="1.1" xmlns="http://www.w3.org/2000/svg"';
      qrSvg += !opts.scalable ? ' width="' + size + 'px" height="' + size + 'px"' : '';
      qrSvg += ' viewBox="0 0 ' + size + ' ' + size + '" ';
      qrSvg += ' preserveAspectRatio="xMinYMin meet"';
      qrSvg += (title.text || alt.text) ? ' role="img" aria-labelledby="' +
          escapeXml([title.id, alt.id].join(' ').trim() ) + '"' : '';
      qrSvg += '>';
      qrSvg += (title.text) ? '<title id="' + escapeXml(title.id) + '">' +
          escapeXml(title.text) + '</title>' : '';
      qrSvg += (alt.text) ? '<description id="' + escapeXml(alt.id) + '">' +
          escapeXml(alt.text) + '</description>' : '';
      qrSvg += '<rect width="100%" height="100%" fill="white" cx="0" cy="0"/>';
      qrSvg += '<path d="';

      for (r = 0; r < _this.getModuleCount(); r += 1) {
        mr = r * cellSize + margin;
        for (c = 0; c < _this.getModuleCount(); c += 1) {
          if (_this.isDark(r, c) ) {
            mc = c*cellSize+margin;
            qrSvg += 'M' + mc + ',' + mr + rect;
          }
        }
      }

      qrSvg += '" stroke="transparent" fill="black"/>';
      qrSvg += '</svg>';

      return qrSvg;
    };

    _this.createDataURL = function(cellSize, margin) {

      cellSize = cellSize || 2;
      margin = (typeof margin == 'undefined')? cellSize * 4 : margin;

      var size = _this.getModuleCount() * cellSize + margin * 2;
      var min = margin;
      var max = size - margin;

      return createDataURL(size, size, function(x, y) {
        if (min <= x && x < max && min <= y && y < max) {
          var c = Math.floor( (x - min) / cellSize);
          var r = Math.floor( (y - min) / cellSize);
          return _this.isDark(r, c)? 0 : 1;
        } else {
          return 1;
        }
      } );
    };

    _this.createImgTag = function(cellSize, margin, alt) {

      cellSize = cellSize || 2;
      margin = (typeof margin == 'undefined')? cellSize * 4 : margin;

      var size = _this.getModuleCount() * cellSize + margin * 2;

      var img = '';
      img += '<img';
      img += '\u0020src="';
      img += _this.createDataURL(cellSize, margin);
      img += '"';
      img += '\u0020width="';
      img += size;
      img += '"';
      img += '\u0020height="';
      img += size;
      img += '"';
      if (alt) {
        img += '\u0020alt="';
        img += escapeXml(alt);
        img += '"';
      }
      img += '/>';

      return img;
    };

    var escapeXml = function(s) {
      var escaped = '';
      for (var i = 0; i < s.length; i += 1) {
        var c = s.charAt(i);
        switch(c) {
        case '<': escaped += '&lt;'; break;
        case '>': escaped += '&gt;'; break;
        case '&': escaped += '&amp;'; break;
        case '"': escaped += '&quot;'; break;
        default : escaped += c; break;
        }
      }
      return escaped;
    };

    var _createHalfASCII = function(margin) {
      var cellSize = 1;
      margin = (typeof margin == 'undefined')? cellSize * 2 : margin;

      var size = _this.getModuleCount() * cellSize + margin * 2;
      var min = margin;
      var max = size - margin;

      var y, x, r1, r2, p;

      var blocks = {
        '██': '█',
        '█ ': '▀',
        ' █': '▄',
        '  ': ' '
      };

      var blocksLastLineNoMargin = {
        '██': '▀',
        '█ ': '▀',
        ' █': ' ',
        '  ': ' '
      };

      var ascii = '';
      for (y = 0; y < size; y += 2) {
        r1 = Math.floor((y - min) / cellSize);
        r2 = Math.floor((y + 1 - min) / cellSize);
        for (x = 0; x < size; x += 1) {
          p = '█';

          if (min <= x && x < max && min <= y && y < max && _this.isDark(r1, Math.floor((x - min) / cellSize))) {
            p = ' ';
          }

          if (min <= x && x < max && min <= y+1 && y+1 < max && _this.isDark(r2, Math.floor((x - min) / cellSize))) {
            p += ' ';
          }
          else {
            p += '█';
          }

          // Output 2 characters per pixel, to create full square. 1 character per pixels gives only half width of square.
          ascii += (margin < 1 && y+1 >= max) ? blocksLastLineNoMargin[p] : blocks[p];
        }

        ascii += '\n';
      }

      if (size % 2 && margin > 0) {
        return ascii.substring(0, ascii.length - size - 1) + Array(size+1).join('▀');
      }

      return ascii.substring(0, ascii.length-1);
    };

    _this.createASCII = function(cellSize, margin) {
      cellSize = cellSize || 1;

      if (cellSize < 2) {
        return _createHalfASCII(margin);
      }

      cellSize -= 1;
      margin = (typeof margin == 'undefined')? cellSize * 2 : margin;

      var size = _this.getModuleCount() * cellSize + margin * 2;
      var min = margin;
      var max = size - margin;

      var y, x, r, p;

      var white = Array(cellSize+1).join('██');
      var black = Array(cellSize+1).join('  ');

      var ascii = '';
      var line = '';
      for (y = 0; y < size; y += 1) {
        r = Math.floor( (y - min) / cellSize);
        line = '';
        for (x = 0; x < size; x += 1) {
          p = 1;

          if (min <= x && x < max && min <= y && y < max && _this.isDark(r, Math.floor((x - min) / cellSize))) {
            p = 0;
          }

          // Output 2 characters per pixel, to create full square. 1 character per pixels gives only half width of square.
          line += p ? white : black;
        }

        for (r = 0; r < cellSize; r += 1) {
          ascii += line + '\n';
        }
      }

      return ascii.substring(0, ascii.length-1);
    };

    _this.renderTo2dContext = function(context, cellSize) {
      cellSize = cellSize || 2;
      var length = _this.getModuleCount();
      for (var row = 0; row < length; row++) {
        for (var col = 0; col < length; col++) {
          context.fillStyle = _this.isDark(row, col) ? 'black' : 'white';
          context.fillRect(row * cellSize, col * cellSize, cellSize, cellSize);
        }
      }
    }

    return _this;
  };

  //---------------------------------------------------------------------
  // qrcode.stringToBytes
  //---------------------------------------------------------------------

  qrcode.stringToBytesFuncs = {
    'default' : function(s) {
      var bytes = [];
      for (var i = 0; i < s.length; i += 1) {
        var c = s.charCodeAt(i);
        bytes.push(c & 0xff);
      }
      return bytes;
    }
  };

  qrcode.stringToBytes = qrcode.stringToBytesFuncs['default'];

  //---------------------------------------------------------------------
  // qrcode.createStringToBytes
  //---------------------------------------------------------------------

  /**
   * @param unicodeData base64 string of byte array.
   * [16bit Unicode],[16bit Bytes], ...
   * @param numChars
   */
  qrcode.createStringToBytes = function(unicodeData, numChars) {

    // create conversion map.

    var unicodeMap = function() {

      var bin = base64DecodeInputStream(unicodeData);
      var read = function() {
        var b = bin.read();
        if (b == -1) throw 'eof';
        return b;
      };

      var count = 0;
      var unicodeMap = {};
      while (true) {
        var b0 = bin.read();
        if (b0 == -1) break;
        var b1 = read();
        var b2 = read();
        var b3 = read();
        var k = String.fromCharCode( (b0 << 8) | b1);
        var v = (b2 << 8) | b3;
        unicodeMap[k] = v;
        count += 1;
      }
      if (count != numChars) {
        throw count + ' != ' + numChars;
      }

      return unicodeMap;
    }();

    var unknownChar = '?'.charCodeAt(0);

    return function(s) {
      var bytes = [];
      for (var i = 0; i < s.length; i += 1) {
        var c = s.charCodeAt(i);
        if (c < 128) {
          bytes.push(c);
        } else {
          var b = unicodeMap[s.charAt(i)];
          if (typeof b == 'number') {
            if ( (b & 0xff) == b) {
              // 1byte
              bytes.push(b);
            } else {
              // 2bytes
              bytes.push(b >>> 8);
              bytes.push(b & 0xff);
            }
          } else {
            bytes.push(unknownChar);
          }
        }
      }
      return bytes;
    };
  };

  //---------------------------------------------------------------------
  // QRMode
  //---------------------------------------------------------------------

  var QRMode = {
    MODE_NUMBER :    1 << 0,
    MODE_ALPHA_NUM : 1 << 1,
    MODE_8BIT_BYTE : 1 << 2,
    MODE_KANJI :     1 << 3
  };

  //---------------------------------------------------------------------
  // QRErrorCorrectionLevel
  //---------------------------------------------------------------------

  var QRErrorCorrectionLevel = {
    L : 1,
    M : 0,
    Q : 3,
    H : 2
  };

  //---------------------------------------------------------------------
  // QRMaskPattern
  //---------------------------------------------------------------------

  var QRMaskPattern = {
    PATTERN000 : 0,
    PATTERN001 : 1,
    PATTERN010 : 2,
    PATTERN011 : 3,
    PATTERN100 : 4,
    PATTERN101 : 5,
    PATTERN110 : 6,
    PATTERN111 : 7
  };

  //---------------------------------------------------------------------
  // QRUtil
  //---------------------------------------------------------------------

  var QRUtil = function() {

    var PATTERN_POSITION_TABLE = [
      [],
      [6, 18],
      [6, 22],
      [6, 26],
      [6, 30],
      [6, 34],
      [6, 22, 38],
      [6, 24, 42],
      [6, 26, 46],
      [6, 28, 50],
      [6, 30, 54],
      [6, 32, 58],
      [6, 34, 62],
      [6, 26, 46, 66],
      [6, 26, 48, 70],
      [6, 26, 50, 74],
      [6, 30, 54, 78],
      [6, 30, 56, 82],
      [6, 30, 58, 86],
      [6, 34, 62, 90],
      [6, 28, 50, 72, 94],
      [6, 26, 50, 74, 98],
      [6, 30, 54, 78, 102],
      [6, 28, 54, 80, 106],
      [6, 32, 58, 84, 110],
      [6, 30, 58, 86, 114],
      [6, 34, 62, 90, 118],
      [6, 26, 50, 74, 98, 122],
      [6, 30, 54, 78, 102, 126],
      [6, 26, 52, 78, 104, 130],
      [6, 30, 56, 82, 108, 134],
      [6, 34, 60, 86, 112, 138],
      [6, 30, 58, 86, 114, 142],
      [6, 34, 62, 90, 118, 146],
      [6, 30, 54, 78, 102, 126, 150],
      [6, 24, 50, 76, 102, 128, 154],
      [6, 28, 54, 80, 106, 132, 158],
      [6, 32, 58, 84, 110, 136, 162],
      [6, 26, 54, 82, 110, 138, 166],
      [6, 30, 58, 86, 114, 142, 170]
    ];
    var G15 = (1 << 10) | (1 << 8) | (1 << 5) | (1 << 4) | (1 << 2) | (1 << 1) | (1 << 0);
    var G18 = (1 << 12) | (1 << 11) | (1 << 10) | (1 << 9) | (1 << 8) | (1 << 5) | (1 << 2) | (1 << 0);
    var G15_MASK = (1 << 14) | (1 << 12) | (1 << 10) | (1 << 4) | (1 << 1);

    var _this = {};

    var getBCHDigit = function(data) {
      var digit = 0;
      while (data != 0) {
        digit += 1;
        data >>>= 1;
      }
      return digit;
    };

    _this.getBCHTypeInfo = function(data) {
      var d = data << 10;
      while (getBCHDigit(d) - getBCHDigit(G15) >= 0) {
        d ^= (G15 << (getBCHDigit(d) - getBCHDigit(G15) ) );
      }
      return ( (data << 10) | d) ^ G15_MASK;
    };

    _this.getBCHTypeNumber = function(data) {
      var d = data << 12;
      while (getBCHDigit(d) - getBCHDigit(G18) >= 0) {
        d ^= (G18 << (getBCHDigit(d) - getBCHDigit(G18) ) );
      }
      return (data << 12) | d;
    };

    _this.getPatternPosition = function(typeNumber) {
      return PATTERN_POSITION_TABLE[typeNumber - 1];
    };

    _this.getMaskFunction = function(maskPattern) {

      switch (maskPattern) {

      case QRMaskPattern.PATTERN000 :
        return function(i, j) { return (i + j) % 2 == 0; };
      case QRMaskPattern.PATTERN001 :
        return function(i, j) { return i % 2 == 0; };
      case QRMaskPattern.PATTERN010 :
        return function(i, j) { return j % 3 == 0; };
      case QRMaskPattern.PATTERN011 :
        return function(i, j) { return (i + j) % 3 == 0; };
      case QRMaskPattern.PATTERN100 :
        return function(i, j) { return (Math.floor(i / 2) + Math.floor(j / 3) ) % 2 == 0; };
      case QRMaskPattern.PATTERN101 :
        return function(i, j) { return (i * j) % 2 + (i * j) % 3 == 0; };
      case QRMaskPattern.PATTERN110 :
        return function(i, j) { return ( (i * j) % 2 + (i * j) % 3) % 2 == 0; };
      case QRMaskPattern.PATTERN111 :
        return function(i, j) { return ( (i * j) % 3 + (i + j) % 2) % 2 == 0; };

      default :
        throw 'bad maskPattern:' + maskPattern;
      }
    };

    _this.getErrorCorrectPolynomial = function(errorCorrectLength) {
      var a = qrPolynomial([1], 0);
      for (var i = 0; i < errorCorrectLength; i += 1) {
        a = a.multiply(qrPolynomial([1, QRMath.gexp(i)], 0) );
      }
      return a;
    };

    _this.getLengthInBits = function(mode, type) {

      if (1 <= type && type < 10) {

        // 1 - 9

        switch(mode) {
        case QRMode.MODE_NUMBER    : return 10;
        case QRMode.MODE_ALPHA_NUM : return 9;
        case QRMode.MODE_8BIT_BYTE : return 8;
        case QRMode.MODE_KANJI     : return 8;
        default :
          throw 'mode:' + mode;
        }

      } else if (type < 27) {

        // 10 - 26

        switch(mode) {
        case QRMode.MODE_NUMBER    : return 12;
        case QRMode.MODE_ALPHA_NUM : return 11;
        case QRMode.MODE_8BIT_BYTE : return 16;
        case QRMode.MODE_KANJI     : return 10;
        default :
          throw 'mode:' + mode;
        }

      } else if (type < 41) {

        // 27 - 40

        switch(mode) {
        case QRMode.MODE_NUMBER    : return 14;
        case QRMode.MODE_ALPHA_NUM : return 13;
        case QRMode.MODE_8BIT_BYTE : return 16;
        case QRMode.MODE_KANJI     : return 12;
        default :
          throw 'mode:' + mode;
        }

      } else {
        throw 'type:' + type;
      }
    };

    _this.getLostPoint = function(qrcode) {

      var moduleCount = qrcode.getModuleCount();

      var lostPoint = 0;

      // LEVEL1

      for (var row = 0; row < moduleCount; row += 1) {
        for (var col = 0; col < moduleCount; col += 1) {

          var sameCount = 0;
          var dark = qrcode.isDark(row, col);

          for (var r = -1; r <= 1; r += 1) {

            if (row + r < 0 || moduleCount <= row + r) {
              continue;
            }

            for (var c = -1; c <= 1; c += 1) {

              if (col + c < 0 || moduleCount <= col + c) {
                continue;
              }

              if (r == 0 && c == 0) {
                continue;
              }

              if (dark == qrcode.isDark(row + r, col + c) ) {
                sameCount += 1;
              }
            }
          }

          if (sameCount > 5) {
            lostPoint += (3 + sameCount - 5);
          }
        }
      };

      // LEVEL2

      for (var row = 0; row < moduleCount - 1; row += 1) {
        for (var col = 0; col < moduleCount - 1; col += 1) {
          var count = 0;
          if (qrcode.isDark(row, col) ) count += 1;
          if (qrcode.isDark(row + 1, col) ) count += 1;
          if (qrcode.isDark(row, col + 1) ) count += 1;
          if (qrcode.isDark(row + 1, col + 1) ) count += 1;
          if (count == 0 || count == 4) {
            lostPoint += 3;
          }
        }
      }

      // LEVEL3

      for (var row = 0; row < moduleCount; row += 1) {
        for (var col = 0; col < moduleCount - 6; col += 1) {
          if (qrcode.isDark(row, col)
              && !qrcode.isDark(row, col + 1)
              &&  qrcode.isDark(row, col + 2)
              &&  qrcode.isDark(row, col + 3)
              &&  qrcode.isDark(row, col + 4)
              && !qrcode.isDark(row, col + 5)
              &&  qrcode.isDark(row, col + 6) ) {
            lostPoint += 40;
          }
        }
      }

      for (var col = 0; col < moduleCount; col += 1) {
        for (var row = 0; row < moduleCount - 6; row += 1) {
          if (qrcode.isDark(row, col)
              && !qrcode.isDark(row + 1, col)
              &&  qrcode.isDark(row + 2, col)
              &&  qrcode.isDark(row + 3, col)
              &&  qrcode.isDark(row + 4, col)
              && !qrcode.isDark(row + 5, col)
              &&  qrcode.isDark(row + 6, col) ) {
            lostPoint += 40;
          }
        }
      }

      // LEVEL4

      var darkCount = 0;

      for (var col = 0; col < moduleCount; col += 1) {
        for (var row = 0; row < moduleCount; row += 1) {
          if (qrcode.isDark(row, col) ) {
            darkCount += 1;
          }
        }
      }

      var ratio = Math.abs(100 * darkCount / moduleCount / moduleCount - 50) / 5;
      lostPoint += ratio * 10;

      return lostPoint;
    };

    return _this;
  }();

  //---------------------------------------------------------------------
  // QRMath
  //---------------------------------------------------------------------

  var QRMath = function() {

    var EXP_TABLE = new Array(256);
    var LOG_TABLE = new Array(256);

    // initialize tables
    for (var i = 0; i < 8; i += 1) {
      EXP_TABLE[i] = 1 << i;
    }
    for (var i = 8; i < 256; i += 1) {
      EXP_TABLE[i] = EXP_TABLE[i - 4]
        ^ EXP_TABLE[i - 5]
        ^ EXP_TABLE[i - 6]
        ^ EXP_TABLE[i - 8];
    }
    for (var i = 0; i < 255; i += 1) {
      LOG_TABLE[EXP_TABLE[i] ] = i;
    }

    var _this = {};

    _this.glog = function(n) {

      if (n < 1) {
        throw 'glog(' + n + ')';
      }

      return LOG_TABLE[n];
    };

    _this.gexp = function(n) {

      while (n < 0) {
        n += 255;
      }

      while (n >= 256) {
        n -= 255;
      }

      return EXP_TABLE[n];
    };

    return _this;
  }();

  //---------------------------------------------------------------------
  // qrPolynomial
  //---------------------------------------------------------------------

  function qrPolynomial(num, shift) {

    if (typeof num.length == 'undefined') {
      throw num.length + '/' + shift;
    }

    var _num = function() {
      var offset = 0;
      while (offset < num.length && num[offset] == 0) {
        offset += 1;
      }
      var _num = new Array(num.length - offset + shift);
      for (var i = 0; i < num.length - offset; i += 1) {
        _num[i] = num[i + offset];
      }
      return _num;
    }();

    var _this = {};

    _this.getAt = function(index) {
      return _num[index];
    };

    _this.getLength = function() {
      return _num.length;
    };

    _this.multiply = function(e) {

      var num = new Array(_this.getLength() + e.getLength() - 1);

      for (var i = 0; i < _this.getLength(); i += 1) {
        for (var j = 0; j < e.getLength(); j += 1) {
          num[i + j] ^= QRMath.gexp(QRMath.glog(_this.getAt(i) ) + QRMath.glog(e.getAt(j) ) );
        }
      }

      return qrPolynomial(num, 0);
    };

    _this.mod = function(e) {

      if (_this.getLength() - e.getLength() < 0) {
        return _this;
      }

      var ratio = QRMath.glog(_this.getAt(0) ) - QRMath.glog(e.getAt(0) );

      var num = new Array(_this.getLength() );
      for (var i = 0; i < _this.getLength(); i += 1) {
        num[i] = _this.getAt(i);
      }

      for (var i = 0; i < e.getLength(); i += 1) {
        num[i] ^= QRMath.gexp(QRMath.glog(e.getAt(i) ) + ratio);
      }

      // recursive call
      return qrPolynomial(num, 0).mod(e);
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // QRRSBlock
  //---------------------------------------------------------------------

  var QRRSBlock = function() {

    var RS_BLOCK_TABLE = [

      // L
      // M
      // Q
      // H

      // 1
      [1, 26, 19],
      [1, 26, 16],
      [1, 26, 13],
      [1, 26, 9],

      // 2
      [1, 44, 34],
      [1, 44, 28],
      [1, 44, 22],
      [1, 44, 16],

      // 3
      [1, 70, 55],
      [1, 70, 44],
      [2, 35, 17],
      [2, 35, 13],

      // 4
      [1, 100, 80],
      [2, 50, 32],
      [2, 50, 24],
      [4, 25, 9],

      // 5
      [1, 134, 108],
      [2, 67, 43],
      [2, 33, 15, 2, 34, 16],
      [2, 33, 11, 2, 34, 12],

      // 6
      [2, 86, 68],
      [4, 43, 27],
      [4, 43, 19],
      [4, 43, 15],

      // 7
      [2, 98, 78],
      [4, 49, 31],
      [2, 32, 14, 4, 33, 15],
      [4, 39, 13, 1, 40, 14],

      // 8
      [2, 121, 97],
      [2, 60, 38, 2, 61, 39],
      [4, 40, 18, 2, 41, 19],
      [4, 40, 14, 2, 41, 15],

      // 9
      [2, 146, 116],
      [3, 58, 36, 2, 59, 37],
      [4, 36, 16, 4, 37, 17],
      [4, 36, 12, 4, 37, 13],

      // 10
      [2, 86, 68, 2, 87, 69],
      [4, 69, 43, 1, 70, 44],
      [6, 43, 19, 2, 44, 20],
      [6, 43, 15, 2, 44, 16],

      // 11
      [4, 101, 81],
      [1, 80, 50, 4, 81, 51],
      [4, 50, 22, 4, 51, 23],
      [3, 36, 12, 8, 37, 13],

      // 12
      [2, 116, 92, 2, 117, 93],
      [6, 58, 36, 2, 59, 37],
      [4, 46, 20, 6, 47, 21],
      [7, 42, 14, 4, 43, 15],

      // 13
      [4, 133, 107],
      [8, 59, 37, 1, 60, 38],
      [8, 44, 20, 4, 45, 21],
      [12, 33, 11, 4, 34, 12],

      // 14
      [3, 145, 115, 1, 146, 116],
      [4, 64, 40, 5, 65, 41],
      [11, 36, 16, 5, 37, 17],
      [11, 36, 12, 5, 37, 13],

      // 15
      [5, 109, 87, 1, 110, 88],
      [5, 65, 41, 5, 66, 42],
      [5, 54, 24, 7, 55, 25],
      [11, 36, 12, 7, 37, 13],

      // 16
      [5, 122, 98, 1, 123, 99],
      [7, 73, 45, 3, 74, 46],
      [15, 43, 19, 2, 44, 20],
      [3, 45, 15, 13, 46, 16],

      // 17
      [1, 135, 107, 5, 136, 108],
      [10, 74, 46, 1, 75, 47],
      [1, 50, 22, 15, 51, 23],
      [2, 42, 14, 17, 43, 15],

      // 18
      [5, 150, 120, 1, 151, 121],
      [9, 69, 43, 4, 70, 44],
      [17, 50, 22, 1, 51, 23],
      [2, 42, 14, 19, 43, 15],

      // 19
      [3, 141, 113, 4, 142, 114],
      [3, 70, 44, 11, 71, 45],
      [17, 47, 21, 4, 48, 22],
      [9, 39, 13, 16, 40, 14],

      // 20
      [3, 135, 107, 5, 136, 108],
      [3, 67, 41, 13, 68, 42],
      [15, 54, 24, 5, 55, 25],
      [15, 43, 15, 10, 44, 16],

      // 21
      [4, 144, 116, 4, 145, 117],
      [17, 68, 42],
      [17, 50, 22, 6, 51, 23],
      [19, 46, 16, 6, 47, 17],

      // 22
      [2, 139, 111, 7, 140, 112],
      [17, 74, 46],
      [7, 54, 24, 16, 55, 25],
      [34, 37, 13],

      // 23
      [4, 151, 121, 5, 152, 122],
      [4, 75, 47, 14, 76, 48],
      [11, 54, 24, 14, 55, 25],
      [16, 45, 15, 14, 46, 16],

      // 24
      [6, 147, 117, 4, 148, 118],
      [6, 73, 45, 14, 74, 46],
      [11, 54, 24, 16, 55, 25],
      [30, 46, 16, 2, 47, 17],

      // 25
      [8, 132, 106, 4, 133, 107],
      [8, 75, 47, 13, 76, 48],
      [7, 54, 24, 22, 55, 25],
      [22, 45, 15, 13, 46, 16],

      // 26
      [10, 142, 114, 2, 143, 115],
      [19, 74, 46, 4, 75, 47],
      [28, 50, 22, 6, 51, 23],
      [33, 46, 16, 4, 47, 17],

      // 27
      [8, 152, 122, 4, 153, 123],
      [22, 73, 45, 3, 74, 46],
      [8, 53, 23, 26, 54, 24],
      [12, 45, 15, 28, 46, 16],

      // 28
      [3, 147, 117, 10, 148, 118],
      [3, 73, 45, 23, 74, 46],
      [4, 54, 24, 31, 55, 25],
      [11, 45, 15, 31, 46, 16],

      // 29
      [7, 146, 116, 7, 147, 117],
      [21, 73, 45, 7, 74, 46],
      [1, 53, 23, 37, 54, 24],
      [19, 45, 15, 26, 46, 16],

      // 30
      [5, 145, 115, 10, 146, 116],
      [19, 75, 47, 10, 76, 48],
      [15, 54, 24, 25, 55, 25],
      [23, 45, 15, 25, 46, 16],

      // 31
      [13, 145, 115, 3, 146, 116],
      [2, 74, 46, 29, 75, 47],
      [42, 54, 24, 1, 55, 25],
      [23, 45, 15, 28, 46, 16],

      // 32
      [17, 145, 115],
      [10, 74, 46, 23, 75, 47],
      [10, 54, 24, 35, 55, 25],
      [19, 45, 15, 35, 46, 16],

      // 33
      [17, 145, 115, 1, 146, 116],
      [14, 74, 46, 21, 75, 47],
      [29, 54, 24, 19, 55, 25],
      [11, 45, 15, 46, 46, 16],

      // 34
      [13, 145, 115, 6, 146, 116],
      [14, 74, 46, 23, 75, 47],
      [44, 54, 24, 7, 55, 25],
      [59, 46, 16, 1, 47, 17],

      // 35
      [12, 151, 121, 7, 152, 122],
      [12, 75, 47, 26, 76, 48],
      [39, 54, 24, 14, 55, 25],
      [22, 45, 15, 41, 46, 16],

      // 36
      [6, 151, 121, 14, 152, 122],
      [6, 75, 47, 34, 76, 48],
      [46, 54, 24, 10, 55, 25],
      [2, 45, 15, 64, 46, 16],

      // 37
      [17, 152, 122, 4, 153, 123],
      [29, 74, 46, 14, 75, 47],
      [49, 54, 24, 10, 55, 25],
      [24, 45, 15, 46, 46, 16],

      // 38
      [4, 152, 122, 18, 153, 123],
      [13, 74, 46, 32, 75, 47],
      [48, 54, 24, 14, 55, 25],
      [42, 45, 15, 32, 46, 16],

      // 39
      [20, 147, 117, 4, 148, 118],
      [40, 75, 47, 7, 76, 48],
      [43, 54, 24, 22, 55, 25],
      [10, 45, 15, 67, 46, 16],

      // 40
      [19, 148, 118, 6, 149, 119],
      [18, 75, 47, 31, 76, 48],
      [34, 54, 24, 34, 55, 25],
      [20, 45, 15, 61, 46, 16]
    ];

    var qrRSBlock = function(totalCount, dataCount) {
      var _this = {};
      _this.totalCount = totalCount;
      _this.dataCount = dataCount;
      return _this;
    };

    var _this = {};

    var getRsBlockTable = function(typeNumber, errorCorrectionLevel) {

      switch(errorCorrectionLevel) {
      case QRErrorCorrectionLevel.L :
        return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 0];
      case QRErrorCorrectionLevel.M :
        return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 1];
      case QRErrorCorrectionLevel.Q :
        return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 2];
      case QRErrorCorrectionLevel.H :
        return RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 3];
      default :
        return undefined;
      }
    };

    _this.getRSBlocks = function(typeNumber, errorCorrectionLevel) {

      var rsBlock = getRsBlockTable(typeNumber, errorCorrectionLevel);

      if (typeof rsBlock == 'undefined') {
        throw 'bad rs block @ typeNumber:' + typeNumber +
            '/errorCorrectionLevel:' + errorCorrectionLevel;
      }

      var length = rsBlock.length / 3;

      var list = [];

      for (var i = 0; i < length; i += 1) {

        var count = rsBlock[i * 3 + 0];
        var totalCount = rsBlock[i * 3 + 1];
        var dataCount = rsBlock[i * 3 + 2];

        for (var j = 0; j < count; j += 1) {
          list.push(qrRSBlock(totalCount, dataCount) );
        }
      }

      return list;
    };

    return _this;
  }();

  //---------------------------------------------------------------------
  // qrBitBuffer
  //---------------------------------------------------------------------

  var qrBitBuffer = function() {

    var _buffer = [];
    var _length = 0;

    var _this = {};

    _this.getBuffer = function() {
      return _buffer;
    };

    _this.getAt = function(index) {
      var bufIndex = Math.floor(index / 8);
      return ( (_buffer[bufIndex] >>> (7 - index % 8) ) & 1) == 1;
    };

    _this.put = function(num, length) {
      for (var i = 0; i < length; i += 1) {
        _this.putBit( ( (num >>> (length - i - 1) ) & 1) == 1);
      }
    };

    _this.getLengthInBits = function() {
      return _length;
    };

    _this.putBit = function(bit) {

      var bufIndex = Math.floor(_length / 8);
      if (_buffer.length <= bufIndex) {
        _buffer.push(0);
      }

      if (bit) {
        _buffer[bufIndex] |= (0x80 >>> (_length % 8) );
      }

      _length += 1;
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // qrNumber
  //---------------------------------------------------------------------

  var qrNumber = function(data) {

    var _mode = QRMode.MODE_NUMBER;
    var _data = data;

    var _this = {};

    _this.getMode = function() {
      return _mode;
    };

    _this.getLength = function(buffer) {
      return _data.length;
    };

    _this.write = function(buffer) {

      var data = _data;

      var i = 0;

      while (i + 2 < data.length) {
        buffer.put(strToNum(data.substring(i, i + 3) ), 10);
        i += 3;
      }

      if (i < data.length) {
        if (data.length - i == 1) {
          buffer.put(strToNum(data.substring(i, i + 1) ), 4);
        } else if (data.length - i == 2) {
          buffer.put(strToNum(data.substring(i, i + 2) ), 7);
        }
      }
    };

    var strToNum = function(s) {
      var num = 0;
      for (var i = 0; i < s.length; i += 1) {
        num = num * 10 + chatToNum(s.charAt(i) );
      }
      return num;
    };

    var chatToNum = function(c) {
      if ('0' <= c && c <= '9') {
        return c.charCodeAt(0) - '0'.charCodeAt(0);
      }
      throw 'illegal char :' + c;
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // qrAlphaNum
  //---------------------------------------------------------------------

  var qrAlphaNum = function(data) {

    var _mode = QRMode.MODE_ALPHA_NUM;
    var _data = data;

    var _this = {};

    _this.getMode = function() {
      return _mode;
    };

    _this.getLength = function(buffer) {
      return _data.length;
    };

    _this.write = function(buffer) {

      var s = _data;

      var i = 0;

      while (i + 1 < s.length) {
        buffer.put(
          getCode(s.charAt(i) ) * 45 +
          getCode(s.charAt(i + 1) ), 11);
        i += 2;
      }

      if (i < s.length) {
        buffer.put(getCode(s.charAt(i) ), 6);
      }
    };

    var getCode = function(c) {

      if ('0' <= c && c <= '9') {
        return c.charCodeAt(0) - '0'.charCodeAt(0);
      } else if ('A' <= c && c <= 'Z') {
        return c.charCodeAt(0) - 'A'.charCodeAt(0) + 10;
      } else {
        switch (c) {
        case ' ' : return 36;
        case '$' : return 37;
        case '%' : return 38;
        case '*' : return 39;
        case '+' : return 40;
        case '-' : return 41;
        case '.' : return 42;
        case '/' : return 43;
        case ':' : return 44;
        default :
          throw 'illegal char :' + c;
        }
      }
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // qr8BitByte
  //---------------------------------------------------------------------

  var qr8BitByte = function(data) {

    var _mode = QRMode.MODE_8BIT_BYTE;
    var _data = data;
    var _bytes = qrcode.stringToBytes(data);

    var _this = {};

    _this.getMode = function() {
      return _mode;
    };

    _this.getLength = function(buffer) {
      return _bytes.length;
    };

    _this.write = function(buffer) {
      for (var i = 0; i < _bytes.length; i += 1) {
        buffer.put(_bytes[i], 8);
      }
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // qrKanji
  //---------------------------------------------------------------------

  var qrKanji = function(data) {

    var _mode = QRMode.MODE_KANJI;
    var _data = data;

    var stringToBytes = qrcode.stringToBytesFuncs['SJIS'];
    if (!stringToBytes) {
      throw 'sjis not supported.';
    }
    !function(c, code) {
      // self test for sjis support.
      var test = stringToBytes(c);
      if (test.length != 2 || ( (test[0] << 8) | test[1]) != code) {
        throw 'sjis not supported.';
      }
    }('\u53cb', 0x9746);

    var _bytes = stringToBytes(data);

    var _this = {};

    _this.getMode = function() {
      return _mode;
    };

    _this.getLength = function(buffer) {
      return ~~(_bytes.length / 2);
    };

    _this.write = function(buffer) {

      var data = _bytes;

      var i = 0;

      while (i + 1 < data.length) {

        var c = ( (0xff & data[i]) << 8) | (0xff & data[i + 1]);

        if (0x8140 <= c && c <= 0x9FFC) {
          c -= 0x8140;
        } else if (0xE040 <= c && c <= 0xEBBF) {
          c -= 0xC140;
        } else {
          throw 'illegal char at ' + (i + 1) + '/' + c;
        }

        c = ( (c >>> 8) & 0xff) * 0xC0 + (c & 0xff);

        buffer.put(c, 13);

        i += 2;
      }

      if (i < data.length) {
        throw 'illegal char at ' + (i + 1);
      }
    };

    return _this;
  };

  //=====================================================================
  // GIF Support etc.
  //

  //---------------------------------------------------------------------
  // byteArrayOutputStream
  //---------------------------------------------------------------------

  var byteArrayOutputStream = function() {

    var _bytes = [];

    var _this = {};

    _this.writeByte = function(b) {
      _bytes.push(b & 0xff);
    };

    _this.writeShort = function(i) {
      _this.writeByte(i);
      _this.writeByte(i >>> 8);
    };

    _this.writeBytes = function(b, off, len) {
      off = off || 0;
      len = len || b.length;
      for (var i = 0; i < len; i += 1) {
        _this.writeByte(b[i + off]);
      }
    };

    _this.writeString = function(s) {
      for (var i = 0; i < s.length; i += 1) {
        _this.writeByte(s.charCodeAt(i) );
      }
    };

    _this.toByteArray = function() {
      return _bytes;
    };

    _this.toString = function() {
      var s = '';
      s += '[';
      for (var i = 0; i < _bytes.length; i += 1) {
        if (i > 0) {
          s += ',';
        }
        s += _bytes[i];
      }
      s += ']';
      return s;
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // base64EncodeOutputStream
  //---------------------------------------------------------------------

  var base64EncodeOutputStream = function() {

    var _buffer = 0;
    var _buflen = 0;
    var _length = 0;
    var _base64 = '';

    var _this = {};

    var writeEncoded = function(b) {
      _base64 += String.fromCharCode(encode(b & 0x3f) );
    };

    var encode = function(n) {
      if (n < 0) {
        // error.
      } else if (n < 26) {
        return 0x41 + n;
      } else if (n < 52) {
        return 0x61 + (n - 26);
      } else if (n < 62) {
        return 0x30 + (n - 52);
      } else if (n == 62) {
        return 0x2b;
      } else if (n == 63) {
        return 0x2f;
      }
      throw 'n:' + n;
    };

    _this.writeByte = function(n) {

      _buffer = (_buffer << 8) | (n & 0xff);
      _buflen += 8;
      _length += 1;

      while (_buflen >= 6) {
        writeEncoded(_buffer >>> (_buflen - 6) );
        _buflen -= 6;
      }
    };

    _this.flush = function() {

      if (_buflen > 0) {
        writeEncoded(_buffer << (6 - _buflen) );
        _buffer = 0;
        _buflen = 0;
      }

      if (_length % 3 != 0) {
        // padding
        var padlen = 3 - _length % 3;
        for (var i = 0; i < padlen; i += 1) {
          _base64 += '=';
        }
      }
    };

    _this.toString = function() {
      return _base64;
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // base64DecodeInputStream
  //---------------------------------------------------------------------

  var base64DecodeInputStream = function(str) {

    var _str = str;
    var _pos = 0;
    var _buffer = 0;
    var _buflen = 0;

    var _this = {};

    _this.read = function() {

      while (_buflen < 8) {

        if (_pos >= _str.length) {
          if (_buflen == 0) {
            return -1;
          }
          throw 'unexpected end of file./' + _buflen;
        }

        var c = _str.charAt(_pos);
        _pos += 1;

        if (c == '=') {
          _buflen = 0;
          return -1;
        } else if (c.match(/^\s$/) ) {
          // ignore if whitespace.
          continue;
        }

        _buffer = (_buffer << 6) | decode(c.charCodeAt(0) );
        _buflen += 6;
      }

      var n = (_buffer >>> (_buflen - 8) ) & 0xff;
      _buflen -= 8;
      return n;
    };

    var decode = function(c) {
      if (0x41 <= c && c <= 0x5a) {
        return c - 0x41;
      } else if (0x61 <= c && c <= 0x7a) {
        return c - 0x61 + 26;
      } else if (0x30 <= c && c <= 0x39) {
        return c - 0x30 + 52;
      } else if (c == 0x2b) {
        return 62;
      } else if (c == 0x2f) {
        return 63;
      } else {
        throw 'c:' + c;
      }
    };

    return _this;
  };

  //---------------------------------------------------------------------
  // gifImage (B/W)
  //---------------------------------------------------------------------

  var gifImage = function(width, height) {

    var _width = width;
    var _height = height;
    var _data = new Array(width * height);

    var _this = {};

    _this.setPixel = function(x, y, pixel) {
      _data[y * _width + x] = pixel;
    };

    _this.write = function(out) {

      //---------------------------------
      // GIF Signature

      out.writeString('GIF87a');

      //---------------------------------
      // Screen Descriptor

      out.writeShort(_width);
      out.writeShort(_height);

      out.writeByte(0x80); // 2bit
      out.writeByte(0);
      out.writeByte(0);

      //---------------------------------
      // Global Color Map

      // black
      out.writeByte(0x00);
      out.writeByte(0x00);
      out.writeByte(0x00);

      // white
      out.writeByte(0xff);
      out.writeByte(0xff);
      out.writeByte(0xff);

      //---------------------------------
      // Image Descriptor

      out.writeString(',');
      out.writeShort(0);
      out.writeShort(0);
      out.writeShort(_width);
      out.writeShort(_height);
      out.writeByte(0);

      //---------------------------------
      // Local Color Map

      //---------------------------------
      // Raster Data

      var lzwMinCodeSize = 2;
      var raster = getLZWRaster(lzwMinCodeSize);

      out.writeByte(lzwMinCodeSize);

      var offset = 0;

      while (raster.length - offset > 255) {
        out.writeByte(255);
        out.writeBytes(raster, offset, 255);
        offset += 255;
      }

      out.writeByte(raster.length - offset);
      out.writeBytes(raster, offset, raster.length - offset);
      out.writeByte(0x00);

      //---------------------------------
      // GIF Terminator
      out.writeString(';');
    };

    var bitOutputStream = function(out) {

      var _out = out;
      var _bitLength = 0;
      var _bitBuffer = 0;

      var _this = {};

      _this.write = function(data, length) {

        if ( (data >>> length) != 0) {
          throw 'length over';
        }

        while (_bitLength + length >= 8) {
          _out.writeByte(0xff & ( (data << _bitLength) | _bitBuffer) );
          length -= (8 - _bitLength);
          data >>>= (8 - _bitLength);
          _bitBuffer = 0;
          _bitLength = 0;
        }

        _bitBuffer = (data << _bitLength) | _bitBuffer;
        _bitLength = _bitLength + length;
      };

      _this.flush = function() {
        if (_bitLength > 0) {
          _out.writeByte(_bitBuffer);
        }
      };

      return _this;
    };

    var getLZWRaster = function(lzwMinCodeSize) {

      var clearCode = 1 << lzwMinCodeSize;
      var endCode = (1 << lzwMinCodeSize) + 1;
      var bitLength = lzwMinCodeSize + 1;

      // Setup LZWTable
      var table = lzwTable();

      for (var i = 0; i < clearCode; i += 1) {
        table.add(String.fromCharCode(i) );
      }
      table.add(String.fromCharCode(clearCode) );
      table.add(String.fromCharCode(endCode) );

      var byteOut = byteArrayOutputStream();
      var bitOut = bitOutputStream(byteOut);

      // clear code
      bitOut.write(clearCode, bitLength);

      var dataIndex = 0;

      var s = String.fromCharCode(_data[dataIndex]);
      dataIndex += 1;

      while (dataIndex < _data.length) {

        var c = String.fromCharCode(_data[dataIndex]);
        dataIndex += 1;

        if (table.contains(s + c) ) {

          s = s + c;

        } else {

          bitOut.write(table.indexOf(s), bitLength);

          if (table.size() < 0xfff) {

            if (table.size() == (1 << bitLength) ) {
              bitLength += 1;
            }

            table.add(s + c);
          }

          s = c;
        }
      }

      bitOut.write(table.indexOf(s), bitLength);

      // end code
      bitOut.write(endCode, bitLength);

      bitOut.flush();

      return byteOut.toByteArray();
    };

    var lzwTable = function() {

      var _map = {};
      var _size = 0;

      var _this = {};

      _this.add = function(key) {
        if (_this.contains(key) ) {
          throw 'dup key:' + key;
        }
        _map[key] = _size;
        _size += 1;
      };

      _this.size = function() {
        return _size;
      };

      _this.indexOf = function(key) {
        return _map[key];
      };

      _this.contains = function(key) {
        return typeof _map[key] != 'undefined';
      };

      return _this;
    };

    return _this;
  };

  var createDataURL = function(width, height, getPixel) {
    var gif = gifImage(width, height);
    for (var y = 0; y < height; y += 1) {
      for (var x = 0; x < width; x += 1) {
        gif.setPixel(x, y, getPixel(x, y) );
      }
    }

    var b = byteArrayOutputStream();
    gif.write(b);

    var base64 = base64EncodeOutputStream();
    var bytes = b.toByteArray();
    for (var i = 0; i < bytes.length; i += 1) {
      base64.writeByte(bytes[i]);
    }
    base64.flush();

    return 'data:image/gif;base64,' + base64;
  };

  //---------------------------------------------------------------------
  // returns qrcode function.

  return qrcode;
}();

// multibyte support
!function() {

  qrcode.stringToBytesFuncs['UTF-8'] = function(s) {
    // http://stackoverflow.com/questions/18729405/how-to-convert-utf8-string-to-byte-array
    function toUTF8Array(str) {
      var utf8 = [];
      for (var i=0; i < str.length; i++) {
        var charcode = str.charCodeAt(i);
        if (charcode < 0x80) utf8.push(charcode);
        else if (charcode < 0x800) {
          utf8.push(0xc0 | (charcode >> 6),
              0x80 | (charcode & 0x3f));
        }
        else if (charcode < 0xd800 || charcode >= 0xe000) {
          utf8.push(0xe0 | (charcode >> 12),
              0x80 | ((charcode>>6) & 0x3f),
              0x80 | (charcode & 0x3f));
        }
        // surrogate pair
        else {
          i++;
          // UTF-16 encodes 0x10000-0x10FFFF by
          // subtracting 0x10000 and splitting the
          // 20 bits of 0x0-0xFFFFF into two halves
          charcode = 0x10000 + (((charcode & 0x3ff)<<10)
            | (str.charCodeAt(i) & 0x3ff));
          utf8.push(0xf0 | (charcode >>18),
              0x80 | ((charcode>>12) & 0x3f),
              0x80 | ((charcode>>6) & 0x3f),
              0x80 | (charcode & 0x3f));
        }
      }
      return utf8;
    }
    return toUTF8Array(s);
  };

}();

(function (factory) {
  if (typeof define === 'function' && define.amd) {
      define([], factory);
  } else if (typeof exports === 'object') {
      module.exports = factory();
  }
}(function () {
    return qrcode;
}));
</script>
<script>
(function () {
    var uri = <?php echo json_encode($pending !== '' ? secOtpauthUri($pending) : ''); ?>;
    var box = document.getElementById('qrBox');
    if (!uri || !box || typeof qrcode === 'undefined') return;
    for (var t = 1; t <= 12; t++) {           // 二维码型号自适应：按数据长度逐级尝试
        try {
            var q = qrcode(t, 'M');
            q.addData(uri);
            q.make();
            box.innerHTML = q.createSvgTag({ cellSize: 4, margin: 4, scalable: true, alt: '2FA 绑定二维码' });
            return;
        } catch (e) { /* 型号容量不够，换下一个 */ }
    }
    box.innerHTML = '<div class="fail">二维码生成失败，请改用下方 Base32 密钥手动录入。</div>';
})();
</script>
<script>
function setAct(v) { document.getElementById('act').value = v; }
// ★ 重新生成令牌：必须显式点击 + 二次确认，并置 rotate_confirm=1 供服务端兜底校验
function rotateConfirm() {
    if (!confirm('确定重新生成后台入口令牌？\n旧地址将立即失效（404），请复制新地址并更新书签。\n（此操作不影响二次验证状态）')) { return false; }
    document.getElementById('rotateConfirmBox').value = '1';
    setAct('rotate_token');
    return true;
}
// ★ 回车隐式提交防护（2026-09-30 事故根因）：
//   表单里第一个 type=submit 是「重新生成令牌」，浏览器在任意文本框按回车都会
//   隐式提交并命中它，导致"只想绑定二次验证，结果重置了后台密钥"。这里统一拦截：
//   - 绑定流程中验证码框回车 = 点「确认绑定」（等价操作，体验不变）
//   - 其它输入框回车一律不提交（必须显式点按钮，防误触破坏性操作）
//   注：只拦 INPUT —— textarea 的 Enter 本就是换行、不会提交表单，拦了反而没法换行。
document.addEventListener('DOMContentLoaded', function () {
    var fm = document.getElementById('secForm');
    if (!fm) return;
    fm.addEventListener('keydown', function (ev) {
        if (ev.key !== 'Enter') return;
        var t = ev.target;
        if (!t || t.tagName !== 'INPUT') return;
        if (t.type === 'submit' || t.type === 'button' || t.type === 'image') return;
        ev.preventDefault();
        if (t.id === 'codeBox' && <?php echo (!$twoFaOn && $pending !== '') ? 'true' : 'false'; ?>) {
            document.getElementById('rotateConfirmBox').value = '';
            setAct('twofa_finish');
            fm.submit();
        }
    });
});
// 重新生成密钥前必须确认：旧密钥一作废，认证器里那条就永远对不上了
function regenConfirm() {
    if (!confirm('重新生成会作废当前密钥——你认证器里已经添加的那条将立即失效（动态码会一直被拒）。确定继续？')) return false;
    setAct('twofa_regen');
    return true;
}
function addMyIp() {
    var box = document.getElementById('wlBox');
    var ip = <?php echo json_encode($myIp); ?>;
    if (!ip) return;
    var lines = box.value.split(/\r?\n/).map(function(s){return s.trim();}).filter(Boolean);
    if (lines.indexOf(ip) === -1) lines.push(ip);
    box.value = lines.join('\n');
}
function copyText(t, btn) {
    var done = function() { var o = btn.textContent; btn.textContent = '已复制'; setTimeout(function(){ btn.textContent = o; }, 1200); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(t).then(done, function(){ fallback(t, done); });
    } else { fallback(t, done); }
}
function fallback(t, done) {
    var ta = document.createElement('textarea');
    ta.value = t; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); done(); } catch (e) {}
    document.body.removeChild(ta);
}
// 验证码框只允许数字
document.addEventListener('input', function(e) {
    if (e.target && e.target.id === 'codeBox') e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6);
});
// 清空白名单按钮二次确认
document.addEventListener('click', function(e) {
    if (e.target && e.target.textContent && e.target.textContent.indexOf('清空白名单') >= 0) {
        if (!confirm('确认清空 IP 白名单？清空后除服务器本机外任何人都无法访问后台（含你自己，只能用引导令牌恢复）。')) {
            e.preventDefault();
        }
    }
});
</script>
</body>
</html>
