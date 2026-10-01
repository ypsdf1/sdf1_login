<?php
/**
 * 人机验证码接口打通测试页（暂不接入任何登录流程）
 *
 * 上下布局同时接入：
 *   1. Cloudflare Turnstile
 *   2. VAPTCHA V4（V3 的 v-cn/cdn/0/api.vaptcha.com 已全部 NXDOMAIN，2026-10-01 切 V4）
 * 各自渲染 -> 出 token -> 回调本页 action=verify 做服务端二次校验 -> 展示官方接口原文。
 * VAPTCHA 服务端两种方式：ep=1 官方 v41.vaptcha.com/api/verify；ep=2 本地 HMAC-SHA256 验签。
 *
 * 密钥来源（软依赖，按优先级）：
 *   captcha_keys.php（不入库）> config.php 常量 > URL参数（临时）> robots.json（兜底）
 * robots.json / captcha_keys.php 均严禁入库（前者已在 .gitignore）。
 */

// ==================== 服务端二次校验代理 ====================
if (isset($_GET['action']) && $_GET['action'] === 'verify') {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    ini_set('display_errors', '0');   // PHP 8.5 的 Deprecated 告警不得污染 JSON
    $k = captchaTestKeys();
    $provider = isset($_GET['provider']) ? $_GET['provider'] : '';
    $token    = isset($_GET['token']) ? (string) $_GET['token'] : '';
    $ep       = isset($_GET['ep']) ? (int) $_GET['ep'] : 1;
    if ($token === '' || strlen($token) > 2048) {
        echo json_encode(['ok' => false, 'err' => 'token 为空'], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $ip = $_SERVER['REMOTE_ADDR'] ?? '';

    if ($provider === 'cf') {
        if ($k['cf_secret'] === '') {
            echo json_encode(['ok' => false, 'err' => 'CF 密钥(secret)未配置'], JSON_UNESCAPED_UNICODE);
            exit;
        }
        $endpoint = 'https://challenges.cloudflare.com/turnstile/v0/siteverify';
        $body = ['secret' => $k['cf_secret'], 'response' => $token];
        if ($ip !== '') $body['remoteip'] = $ip;
        $r = captchaHttpPostForm($endpoint, $body);
        echo json_encode([
            'ok' => $r['errno'] === 0, 'provider' => 'CF Turnstile',
            'endpoint' => $endpoint, 'http' => $r['status'],
            'body' => $r['body'], 'err' => $r['err'],
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    if ($provider === 'vaptcha') {
        if ($k['v_key'] === '' || $k['v_vid'] === '') {
            echo json_encode(['ok' => false, 'err' => 'vaptcha VID/Key 未配置'], JSON_UNESCAPED_UNICODE);
            exit;
        }
        // VAPTCHA V4 二次验证两种方式（2026-10-01 官方文档：document/install）
        //   ep=1（默认）官方 verify 接口；ep=2 本地 HMAC-SHA256 验签（token = ts.id.sig）
        $knock = isset($_GET['knock']) ? (string) $_GET['knock'] : '';
        $dfu   = isset($_GET['dfu'])   ? (string) $_GET['dfu']   : '';
        $sip   = isset($_GET['ip'])    ? (string) $_GET['ip']    : $ip;   // 优先 SDK 签名 IP
        if (strlen($knock) > 256 || strlen($dfu) > 512 || strlen($sip) > 64) {
            echo json_encode(['ok' => false, 'err' => 'knock/dfu/ip 参数过长'], JSON_UNESCAPED_UNICODE);
            exit;
        }

        if ($ep === 2) {
            // 方式二回退：本地验签（文档给的官方签名规则）
            $parts = explode('.', $token);
            $pass = false; $why = '';
            if (count($parts) !== 3) { $why = 'token 非三段式'; }
            elseif (abs(time() - (int) $parts[0]) > 180) { $why = 'token 超过 180s 有效期'; }
            else {
                $expected = hash_hmac('sha256', $parts[0] . '.' . $sip . '.' . $dfu . '.' . $knock, $k['v_key']);
                $pass = hash_equals($expected, $parts[2]);
                if (!$pass) $why = '签名校验不通过';
            }
            echo json_encode([
                'ok' => true, 'provider' => 'VAPTCHA V4 本地验签', 'mode' => 'local-hmac',
                'pass' => $pass, 'why' => $why,
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }

        $endpoint = 'https://v41.vaptcha.com/api/verify';
        $payload = [
            'vid'   => $k['v_vid'],
            'vkey'  => $k['v_key'],
            'token' => $token,
            'knock' => $knock,
            'dfu'   => $dfu,
            'ip'    => $sip,
        ];
        $r = captchaHttpPostJson($endpoint, $payload);
        $decoded = json_decode($r['body'], true);
        echo json_encode([
            'ok' => $r['errno'] === 0, 'provider' => 'VAPTCHA V4', 'mode' => 'official-verify',
            'endpoint' => $endpoint, 'http' => $r['status'],
            'result' => is_array($decoded) ? ($decoded['data']['result'] ?? null) : null,
            'body' => $r['body'], 'err' => $r['err'],
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    echo json_encode(['ok' => false, 'err' => '未知 provider'], JSON_UNESCAPED_UNICODE);
    exit;
}

// ==================== 密钥解析（软依赖四级回退） ====================
function captchaTestKeys() {
    static $cache = null;
    if ($cache !== null) return $cache;
    $k = ['source' => '未配置', 'cf_sitekey' => '', 'cf_secret' => '',
          'v_vid' => '', 'v_key' => '', 'v_scene' => 0];

    // 1) captcha_keys.php（独立密钥文件，不入库）
    $f = __DIR__ . '/captcha_keys.php';
    if (is_file($f)) {
        $CF_SITEKEY = ''; $CF_SECRET = ''; $VAPTCHA_VID = ''; $VAPTCHA_VKEY = ''; $VAPTCHA_SCENE = 0;
        @include $f;
        if ($CF_SITEKEY !== '' || $VAPTCHA_VID !== '') {
            $k = ['source' => 'captcha_keys.php', 'cf_sitekey' => (string) $CF_SITEKEY,
                  'cf_secret' => (string) $CF_SECRET, 'v_vid' => (string) $VAPTCHA_VID,
                  'v_key' => (string) $VAPTCHA_VKEY, 'v_scene' => (int) $VAPTCHA_SCENE];
            return $cache = $k;
        }
    }

    // 2) config.php 常量（config.php 本身不入库）
    if (defined('CF_TURNSTILE_SITEKEY') || defined('VAPTCHA_VID')) {
        $k = ['source' => 'config.php 常量',
              'cf_sitekey' => defined('CF_TURNSTILE_SITEKEY') ? CF_TURNSTILE_SITEKEY : '',
              'cf_secret'   => defined('CF_TURNSTILE_SECRET')   ? CF_TURNSTILE_SECRET   : '',
              'v_vid'       => defined('VAPTCHA_VID')            ? VAPTCHA_VID           : '',
              'v_key'       => defined('VAPTCHA_VKEY')           ? VAPTCHA_VKEY          : '',
              'v_scene'     => defined('VAPTCHA_SCENE')          ? VAPTCHA_SCENE         : 0];
        if ($k['cf_sitekey'] !== '' || $k['v_vid'] !== '') return $cache = $k;
    }

    // 3) URL 参数（临时联调）
    $gcf  = isset($_GET['cf_sitekey']) ? trim((string) $_GET['cf_sitekey']) : '';
    $gvid = isset($_GET['v_vid'])      ? trim((string) $_GET['v_vid'])      : '';
    if ($gcf !== '' || $gvid !== '') {
        $k = ['source' => 'URL参数(临时)', 'cf_sitekey' => $gcf,
              'cf_secret' => isset($_GET['cf_secret']) ? (string) $_GET['cf_secret'] : '',
              'v_vid' => $gvid,
              'v_key' => isset($_GET['v_key']) ? (string) $_GET['v_key'] : '',
              'v_scene' => isset($_GET['v_scene']) ? (int) $_GET['v_scene'] : 0];
        return $cache = $k;
    }

    // 4) robots.json 兜底（严禁入库的密钥清单）
    $rj = __DIR__ . '/robots.json';
    if (is_file($rj)) {
        $arr = json_decode((string) @file_get_contents($rj), true);
        if (is_array($arr)) {
            foreach ($arr as $item) {
                if (!is_array($item) || !isset($item['名字'])) continue;
                if ($item['名字'] === 'CF') {
                    $k['cf_sitekey'] = (string) ($item['站点密钥'] ?? '');
                    $k['cf_secret']   = (string) ($item['密钥'] ?? '');
                } elseif ($item['名字'] === 'vaptcha') {
                    $k['v_vid']  = (string) ($item['VID'] ?? '');
                    $k['v_key']  = (string) ($item['VKey'] ?? '');
                    $k['v_scene'] = 0;
                }
            }
            if ($k['cf_sitekey'] !== '' || $k['v_vid'] !== '') {
                $k['source'] = 'robots.json';
            }
        }
    }
    return $cache = $k;
}

function captchaHttpPostForm($url, array $fields) {
    $r = ['errno' => -1, 'status' => 0, 'body' => '', 'err' => ''];
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => http_build_query($fields),
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_CONNECTTIMEOUT => 5,
        ]);
        $body = curl_exec($ch);
        $r['status'] = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $r['err'] = curl_error($ch);
        // PHP 8.0+ curl_close 为 no-op，8.5 起抛 Deprecated —— 不再调用，句柄随作用域释放
        if ($body !== false) { $r['body'] = (string) $body; $r['errno'] = 0; }
        return $r;
    }
    $ctx = stream_context_create(['http' => [
        'method' => 'POST', 'timeout' => 10,
        'header' => "Content-Type: application/x-www-form-urlencoded\r\n",
        'content' => http_build_query($fields)]]);
    $body = @file_get_contents($url, false, $ctx);
    if ($body !== false) { $r['body'] = (string) $body; $r['errno'] = 0; $r['err'] = 'file_get_contents'; }
    else $r['err'] = '无 curl 且请求失败';
    return $r;
}

function captchaHttpPostJson($url, array $payload) {
    $r = ['errno' => -1, 'status' => 0, 'body' => '', 'err' => ''];
    $json = json_encode($payload, JSON_UNESCAPED_UNICODE);
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $json,
            CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_CONNECTTIMEOUT => 5,
        ]);
        $body = curl_exec($ch);
        $r['status'] = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $r['err'] = curl_error($ch);
        // PHP 8.0+ curl_close 为 no-op，8.5 起抛 Deprecated —— 不再调用，句柄随作用域释放
        if ($body !== false) { $r['body'] = (string) $body; $r['errno'] = 0; }
        return $r;
    }
    $ctx = stream_context_create(['http' => [
        'method' => 'POST', 'timeout' => 10,
        'header' => "Content-Type: application/json\r\n",
        'content' => $json]]);
    $body = @file_get_contents($url, false, $ctx);
    if ($body !== false) { $r['body'] = (string) $body; $r['errno'] = 0; $r['err'] = 'file_get_contents'; }
    else $r['err'] = '无 curl 且请求失败';
    return $r;
}

// ==================== 页面 ====================
$K = captchaTestKeys();
function maskKey($s) {
    if ($s === '') return '（未配置）';
    $len = strlen($s);
    if ($len <= 10) return $s;
    return substr($s, 0, 8) . str_repeat('*', max(4, $len - 12)) . substr($s, -4);
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>验证码接口测试页（Turnstile + VAPTCHA）</title>
<style>
    * { box-sizing: border-box; }
    body { margin: 0; padding: 24px 14px 48px; background: #0f1420; color: #e8ecf4;
           font-family: "Microsoft YaHei", "PingFang SC", system-ui, sans-serif; }
    .wrap { max-width: 720px; margin: 0 auto; }
    h1 { font-size: 20px; margin: 0 0 4px; color: #fff; }
    .sub { color: #8b96b3; font-size: 13px; margin-bottom: 18px; }
    .srcbar { background: #171d2c; border: 1px solid #2a3350; border-radius: 10px;
              padding: 12px 16px; font-size: 13px; color: #aab4d0; line-height: 1.9;
              margin-bottom: 20px; }
    .srcbar b { color: #ffd479; }
    .card { background: #171d2c; border: 1px solid #2a3350; border-radius: 14px;
            padding: 22px 22px 20px; margin-bottom: 24px; }
    .card h2 { margin: 0 0 4px; font-size: 17px; color: #fff; }
    .hint { color: #8b96b3; font-size: 12.5px; margin-bottom: 14px; }
    .badge { display: inline-block; padding: 3px 10px; border-radius: 999px;
             font-size: 12px; margin-bottom: 12px; background: #2a3350; color: #aab4d0; }
    .badge.ok { background: #1d4d2b; color: #7ee2a0; }
    .badge.err { background: #4d1d1d; color: #ff9b8f; }
    .badge.load { background: #4d3f1d; color: #ffd479; }
    #cfBox, #vBox { min-height: 60px; display: flex; align-items: center; }
    .tok { background: #10151f; border: 1px solid #2a3350; border-radius: 8px;
           padding: 10px 12px; font-family: Consolas, monospace; font-size: 12px;
           color: #7ee2a0; word-break: break-all; margin-top: 12px; display: none; }
    .verify { background: #10151f; border: 1px solid #2a3350; border-radius: 8px;
              padding: 10px 12px; font-family: Consolas, monospace; font-size: 12px;
              color: #9fd489; white-space: pre-wrap; word-break: break-all;
              margin-top: 10px; display: none; }
    .fail { color: #ff9b8f; font-weight: bold; }
    .tip { margin-top: 14px; font-size: 12px; color: #6b7695; line-height: 1.8; }
    code { background: #2a3350; color: #ffd479; padding: 1px 6px; border-radius: 5px; }
</style>
</head>
<body>
<div class="wrap">
    <h1>人机验证码接口测试页</h1>
    <div class="sub">CF Turnstile（上）+ VAPTCHA（下）· 仅测试接口连通性，暂不接入任何登录流程</div>

    <div class="srcbar">
        密钥来源：<b><?php echo htmlspecialchars($K['source'], ENT_QUOTES); ?></b><br>
        CF sitekey：<b><?php echo htmlspecialchars(maskKey($K['cf_sitekey']), ENT_QUOTES); ?></b>
        &nbsp;|&nbsp; CF secret：<b><?php echo htmlspecialchars(maskKey($K['cf_secret']), ENT_QUOTES); ?></b><br>
        VAPTCHA VID：<b><?php echo htmlspecialchars(maskKey($K['v_vid']), ENT_QUOTES); ?></b>
        &nbsp;|&nbsp; Key：<b><?php echo htmlspecialchars(maskKey($K['v_key']), ENT_QUOTES); ?></b>
        &nbsp;|&nbsp; scene：<b><?php echo (int) $K['v_scene']; ?></b><br>
        <span style="color:#6b7695">优先级：captcha_keys.php &gt; config.php 常量 &gt; URL参数 &gt; robots.json</span>
    </div>

    <!-- ============ 1. Cloudflare Turnstile ============ -->
    <div class="card">
        <h2>1 · Cloudflare Turnstile</h2>
        <div class="hint">官方文档：challenges.cloudflare.com/turnstile · 服务端校验 siteverify</div>
        <div id="cfState" class="badge load">SDK 加载中…</div>
        <div id="cfBox"></div>
        <div id="cfTok" class="tok"></div>
        <div id="cfVerify" class="verify"></div>
        <div class="tip">出 token 后自动调本页 <code>action=verify&amp;provider=cf</code> 走服务端 siteverify，
            展示官方接口原文。</div>
    </div>

    <!-- ============ 2. VAPTCHA ============ -->
    <div class="card">
        <h2>2 · VAPTCHA (V4)</h2>
        <div class="hint">SDK：c4.vaptcha.com/src/v4.js · 服务端校验 v41.vaptcha.com/api/verify（或本地 HMAC 验签）</div>
        <div id="vState" class="badge load">SDK 加载中…</div>
        <div id="vBox"></div>
        <div style="margin-top:10px">
            <button id="vStart" disabled
                style="background:#2a3350;color:#e8ecf4;border:1px solid #3b4872;border-radius:8px;padding:8px 18px;font-size:13px;cursor:not-allowed">
                发起验证
            </button>
        </div>
        <div id="vTok" class="tok"></div>
        <div id="vVerify" class="verify"></div>
        <div class="tip">点击「发起验证」出 token 后自动调 <code>action=verify&amp;provider=vaptcha</code>（默认官方接口，
            失败可 <a href="#" id="vRetry" style="color:#7ee2a0">改用本地 HMAC 验签重试</a>）。</div>
    </div>

    <div class="tip" style="text-align:center">
        提示：两个接口都必须在已绑定的域名下渲染（*.ypshidifu.cn / CF 站点域名），
        本地 localhost 打开可能被域名校验拒绝——属正常现象，以线上域名访问为准。
    </div>
</div>

<!-- ===== CF Turnstile SDK ===== -->
<?php if ($K['cf_sitekey'] !== '') : ?>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer
        onerror="document.getElementById('cfState').className='badge err';document.getElementById('cfState').textContent='SDK 加载失败（网络/被墙）'"></script>
<script>
(function () {
    var st = document.getElementById('cfState');
    var tokBox = document.getElementById('cfTok');
    var vBox = document.getElementById('cfVerify');
    var sitekey = <?php echo json_encode($K['cf_sitekey']); ?>;
    var tries = 0;

    function ready() {
        if (typeof turnstile === 'undefined') {
            if (++tries > 40) {   // 10 秒兜底
                st.className = 'badge err';
                st.textContent = 'SDK 未加载（检查网络/域名白名单）';
                return;
            }
            return setTimeout(ready, 250);
        }
        st.className = 'badge load';
        st.textContent = '渲染中…';
        try {
            turnstile.render('#cfBox', {
                sitekey: sitekey,
                callback: function (token) {
                    st.className = 'badge ok';
                    st.textContent = '前端验证通过，token 已获取';
                    tokBox.style.display = 'block';
                    tokBox.textContent = 'token: ' + token;
                    doVerify(token);
                },
                'error-callback': function (msg) {
                    st.className = 'badge err';
                    st.textContent = '渲染错误: ' + msg;
                },
                'expired-callback': function () {
                    st.className = 'badge load';
                    st.textContent = 'token 已过期，请重新勾选';
                    tokBox.style.display = 'none';
                    vBox.style.display = 'none';
                }
            });
        } catch (e) {
            st.className = 'badge err';
            st.textContent = 'render 异常: ' + e.message;
        }
    }

    function doVerify(token) {
        vBox.style.display = 'block';
        vBox.textContent = '服务端校验中…';
        fetch('captcha_test.php?action=verify&provider=cf&token=' + encodeURIComponent(token))
            .then(function (r) { return r.json(); })
            .then(function (j) {
                vBox.textContent = JSON.stringify(j, null, 2);
            })
            .catch(function (e) {
                vBox.textContent = '校验请求失败: ' + e;
            });
    }

    ready();
})();
</script>
<?php else : ?>
<script>document.getElementById('cfState').className='badge err';document.getElementById('cfState').textContent='CF sitekey 未配置';</script>
<?php endif; ?>

<!-- ===== VAPTCHA SDK ===== -->
<?php if ($K['v_vid'] !== '') : ?>
<script>
(function () {
    var st = document.getElementById('vState');
    var tokBox = document.getElementById('vTok');
    var vBox = document.getElementById('vVerify');
    var vid = <?php echo json_encode($K['v_vid']); ?>;
    var scene = <?php echo (int) $K['v_scene']; ?>;
    var ep = 1;
    var lastResult = null;
    var vaptchaObj = null;
    var btn = document.getElementById('vStart');

    window.__vRetryEp = function () { if (lastResult) doVerify(lastResult, ep === 1 ? 2 : 1); };
    document.getElementById('vRetry').addEventListener('click', function (e) {
        e.preventDefault();
        window.__vRetryEp();
    });

    function loadScript(src, ok, fail) {
        var s = document.createElement('script');
        s.src = src; s.async = true;
        s.onload = ok;
        s.onerror = fail;
        document.head.appendChild(s);
    }

    // VAPTCHA V4 官方 SDK（V3 的 v-cn/cdn.vaptcha.com 已 NXDOMAIN）
    loadScript('https://c4.vaptcha.com/src/v4.js', init, function () {
        st.className = 'badge err';
        st.textContent = 'SDK 加载失败（c4.vaptcha.com 不可达）';
    });

    var inited = false;
    function init() {
        if (inited) return;
        inited = true;
        if (typeof vaptcha !== 'function') {
            st.className = 'badge err';
            st.textContent = 'SDK 已加载但 vaptcha 不是函数（版本不符）';
            return;
        }
        st.className = 'badge load';
        st.textContent = '初始化中…';
        // V4：SDK 不注入按钮/事件，业务页提供入口并显式调用 validate()
        vaptcha({
            vid: vid,
            container: '#vBox',
            lang: 'zh-CN'
        }).then(function (obj) {
            vaptchaObj = obj;
            st.className = 'badge load';
            st.textContent = 'SDK 就绪，点击「发起验证」';
            btn.disabled = false;
            btn.style.cursor = 'pointer';
            btn.addEventListener('click', function () {
                st.className = 'badge load';
                st.textContent = '验证中…';
                Promise.resolve(obj.validate()).then(function (result) {
                    if (!result || !result.token) {
                        st.className = 'badge err';
                        st.textContent = '未取得 token（用户取消或验证失败）';
                        return;
                    }
                    lastResult = result;
                    ep = 1;
                    st.className = 'badge ok';
                    st.textContent = '前端验证通过，token 已获取';
                    tokBox.style.display = 'block';
                    tokBox.textContent = 'token: ' + result.token
                        + '\nknock: ' + (result.knock || '')
                        + '\ndfu: ' + (result.dfu || '')
                        + '\nip: ' + (result.ip || '');
                    doVerify(result, 1);
                }).catch(function (e) {
                    st.className = 'badge err';
                    st.textContent = 'validate() 异常: ' + (e && e.message ? e.message : e);
                });
            });
        }).catch(function (e) {
            st.className = 'badge err';
            st.textContent = '初始化失败: ' + (e && e.message ? e.message : e);
        });
    }

    function doVerify(result, useEp) {
        ep = useEp;
        vBox.style.display = 'block';
        vBox.textContent = '服务端校验中（' + (ep === 1 ? '官方接口' : '本地 HMAC') + '）…';
        var q = 'captcha_test.php?action=verify&provider=vaptcha&ep=' + ep
              + '&token=' + encodeURIComponent(result.token || '')
              + '&knock=' + encodeURIComponent(result.knock || '')
              + '&dfu=' + encodeURIComponent(result.dfu || '')
              + '&ip=' + encodeURIComponent(result.ip || '');
        fetch(q)
            .then(function (r) { return r.json(); })
            .then(function (j) {
                vBox.textContent = JSON.stringify(j, null, 2);
            })
            .catch(function (e) {
                vBox.textContent = '校验请求失败: ' + e;
            });
    }
})();
</script>
<?php else : ?>
<script>document.getElementById('vState').className='badge err';document.getElementById('vState').textContent='VAPTCHA VID 未配置';</script>
<?php endif; ?>
</body>
</html>
