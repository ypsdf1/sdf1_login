<?php
/**
 * 第一层二次验证 —— 6 位动态码验证页
 * 兼容 1Password / Microsoft Authenticator / Google Authenticator 等标准 RFC6238 认证器。
 * 登录成功且 SEC_2FA_ENABLED=true 时，前端跳转到本页；验证通过后进入 admin.php。
 */
require_once __DIR__ . '/security.php';
secEnsureSession();
// IP 白名单闸门；boot 引导会话（配置流程中转）也放行
secGatePage(true);

$enabled = defined('SEC_2FA_ENABLED') && SEC_2FA_ENABLED;
$err = '';

// 未启用 → 引导去配置页
if (!$enabled) {
    header('Location: admin_2fa_setup.php');
    exit;
}
// 未登录 / 旧纪元会话 → 回登录页
// （本页只依赖 security.php，不加载 core.php，故用等价内联判定）
if (empty($_SESSION['admin_auth']) || !secSessionEpochOk()) {
    header('Location: admin_login.php');
    exit;
}
// 已通过 → 直接进后台（★ 第三层：必须带上入口令牌，否则 admin.php 会返回 404）
if (!empty($_SESSION['admin_2fa_ok'])) {
    header('Location: ' . secTokenUrl('admin.php'));
    exit;
}

// ===== POST：校验动态码 =====
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $sid = session_id();
    $lock = secThrottleLocked('twofa_fail', $sid, 5, 900);
    if ($lock > 0) {
        $err = '验证失败次数过多，已锁定，请 ' . $lock . ' 秒后再试';
        secLog('2fa_throttled', 'lock=' . $lock . 's');
    } else {
        $code = isset($_POST['code']) ? (string)$_POST['code'] : '';
        if (secTotpVerify(SEC_2FA_SECRET, $code)) {
            $_SESSION['admin_2fa_ok'] = time();
            $_SESSION['admin_2fa_ip'] = secClientIp();
            secThrottleReset('twofa_fail', $sid);
            secLog('2fa_pass', 'session granted');
            header('Location: ' . secTokenUrl('admin.php'));
            exit;
        }
        $left = secThrottleFail('twofa_fail', $sid, 5, 900);
        // ★ 诊断：把"密钥录错/绑了旧密钥"和"设备时钟漂移"两种失败原因区分开，
        //   否则用户只能看到一句"无效"，无从下手。
        $off = secTotpDiagnose(SEC_2FA_SECRET, $code);
        secLog('2fa_fail', $left > 0 ? 'LOCKED_' . $left . 's'
            : ($off === null ? 'wrong_secret' : 'clock_skew ' . $off . 's'));
        $err = $left > 0
            ? '验证失败次数过多，已锁定，请 ' . $left . ' 秒后再试'
            : secTotpDiagnoseMsg($off);
    }
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SDF1 - 二次验证</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            background: #0d1117; color: #e6edf3;
            font-family: 'Segoe UI', system-ui, sans-serif;
            display: flex; justify-content: center; align-items: center; min-height: 100vh;
        }
        .box {
            background: #161b22; border: 1px solid #30363d; border-radius: 12px;
            padding: 40px; width: 400px; max-width: 92%;
        }
        .box h1 { text-align: center; color: #58a6ff; margin-bottom: 8px; font-size: 22px; }
        .box .sub { text-align: center; color: #8b949e; font-size: 13px; margin-bottom: 24px; line-height: 1.7; }
        .code-input {
            width: 100%; padding: 14px; background: #0d1117; border: 1px solid #30363d;
            border-radius: 8px; color: #e6edf3; font-size: 30px; letter-spacing: 10px;
            text-align: center; outline: none; font-family: monospace;
        }
        .code-input:focus { border-color: #58a6ff; }
        .btn {
            width: 100%; padding: 12px; background: #58a6ff; color: #fff; border: none;
            border-radius: 8px; font-size: 15px; font-weight: 600; cursor: pointer; margin-top: 16px;
        }
        .btn:hover { background: #79c0ff; }
        .timer-bar { height: 4px; background: #30363d; border-radius: 2px; margin-top: 14px; overflow: hidden; }
        .timer-bar .fill { height: 100%; background: #3fb950; width: 100%; transition: width .3s linear; }
        .timer-text { font-size: 11px; color: #8b949e; text-align: center; margin-top: 6px; }
        .error {
            color: #f85149; font-size: 13px; text-align: center; margin-top: 14px;
            background: rgba(248,81,73,0.1); border: 1px solid rgba(248,81,73,0.3);
            border-radius: 6px; padding: 8px; display: block;
        }
        .links { text-align: center; margin-top: 18px; }
        .links a { color: #58a6ff; text-decoration: none; font-size: 13px; }
        .ip { text-align: center; font-size: 11px; color: #8b949e; margin-top: 16px; }
        .ip b { color: #d29922; font-family: monospace; }
    </style>
</head>
<body>
    <div class="box">
        <h1>🛡️ 二次验证</h1>
        <div class="sub">请输入认证器 App 中的 <b style="color:#e6edf3">6 位动态码</b><br>
            （1Password / Microsoft Authenticator / Google Authenticator 均可）</div>
        <form method="post" autocomplete="off">
            <input class="code-input" id="code" name="code" inputmode="numeric" pattern="[0-9]*"
                   maxlength="6" placeholder="000000" autofocus>
            <button class="btn" type="submit" id="submitBtn">验 证</button>
            <div class="timer-bar"><div class="fill" id="tfill"></div></div>
            <div class="timer-text" id="ttext">动态码每 30 秒刷新</div>
            <div class="timer-text" id="srvtime" style="color:#58a6ff"></div>
            <?php if ($err !== ''): ?>
                <div class="error" style="white-space:pre-wrap;text-align:left"><?php echo htmlspecialchars($err, ENT_QUOTES, 'UTF-8'); ?></div>
            <?php endif; ?>
        </form>
        <div class="links">
            <a href="admin_login.php">← 返回登录</a> &nbsp;|&nbsp;
            <a href="admin_2fa_setup.php">安全配置</a>
        </div>
        <div class="ip">当前访问 IP：<b><?php echo htmlspecialchars(secClientIp(), ENT_QUOTES, 'UTF-8'); ?></b></div>
        <div class="ip" style="margin-top:4px">提示：认证器与上方<b>服务器时间</b>偏差超过 90 秒就会被拒</div>
    </div>

    <script>
    (function() {
        var input = document.getElementById('code');
        input.addEventListener('input', function() {
            this.value = this.value.replace(/\D/g, '').slice(0, 6);
        });
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && this.value.length === 6) {
                document.querySelector('form').submit();
            }
        });
        // ★ 进度条/倒计时改用【服务器时钟】：
        //   手机时间不准时，用浏览器自身时钟画进度条会误导用户（看着还剩 20 秒，
        //   服务器那边其实已经翻到下一窗格了），所以以 PHP 渲染时刻为基准推进。
        var srvBase = <?php echo time(); ?>;              // 页面渲染时的服务器 Unix 时间
        var srvOff  = <?php echo (new DateTime('now'))->getOffset(); ?>; // 服务器时区偏移（秒）
        var t0 = Date.now();
        function srvNow() { return srvBase + Math.floor((Date.now() - t0) / 1000); }
        function pad(n) { return (n < 10 ? '0' : '') + n; }
        var fill = document.getElementById('tfill');
        var text = document.getElementById('ttext');
        var srvBox = document.getElementById('srvtime');
        function tick() {
            var s = srvNow();
            var left = 30 - (s % 30);
            fill.style.width = (left / 30 * 100) + '%';
            var d = new Date((s + srvOff) * 1000);
            text.textContent = left + ' 秒后动态码刷新';
            srvBox.textContent = '服务器时间 ' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds())
                               + '（与你手机差多少秒请自行比对）';
        }
        tick();
        setInterval(tick, 300);
    })();
    </script>
</body>
</html>
