<?php
/**
 * 人机验证码接口打通测试页（暂不接入任何登录流程）
 *
 * 上下布局同时接入：
 *   1. Cloudflare Turnstile
 *   2. VAPTCHA (v3)
 * 各自渲染 -> 出 token -> 回调本页 action=verify 做服务端二次校验 -> 展示官方接口原文。
 *
 * 密钥来源（软依赖，按优先级）：
 *   captcha_keys.php（不入库）> config.php 常量 > URL参数（临时）> robots.json（兜底）
 * robots.json / captcha_keys.php 均严禁入库（前者已在 .gitignore）。
 */

// ==================== 服务端二次校验代理 ====================
if (isset($_GET['action']) && $_GET['action'] === 'verify') {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
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
        // 候选端点：1=官方文档新接口，2=经典 verify 接口（实测择优）
        $endpoints = [
            1 => 'https://0.vaptcha.com/verify',
            2 => 'https://api.vaptcha.com/v2/validate',
        ];
        $endpoint = $endpoints[$ep] ?? $endpoints[1];
        $payload = [
            'id'        => $k['v_vid'],
            'secretkey' => $k['v_key'],
            'scene'     => $k['v_scene'],
            'token'     => $token,
            'ip'        => $ip,
        ];
        $r = captchaHttpPostJson($endpoint, $payload);
        echo json_encode([
            'ok' => $r['errno'] === 0, 'provider' => 'VAPTCHA',
            'endpoint' => $endpoint, 'http' => $r['status'],
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
        curl_close($ch);
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
        curl_close($ch);
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
        <h2>2 · VAPTCHA (v3)</h2>
        <div class="hint">SDK：v-cn.vaptcha.com/v3.js（失败自动尝试 cdn.vaptcha.com）· 点击式 mode=click</div>
        <div id="vState" class="badge load">SDK 加载中…</div>
        <div id="vBox"></div>
        <div id="vTok" class="tok"></div>
        <div id="vVerify" class="verify"></div>
        <div class="tip">出 token 后自动调 <code>action=verify&amp;provider=vaptcha</code>（端点1失败可
            <a href="#" id="vRetry" style="color:#7ee2a0">点此切换端点2重试</a>）。</div>
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
    var lastToken = '';

    window.__vRetryEp = function () { if (lastToken) doVerify(lastToken, ep === 1 ? 2 : 1); };
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

    // 主源失败自动切备用源
    loadScript('https://v-cn.vaptcha.com/v3.js', init, function () {
        st.textContent = '主源失败，尝试备用 CDN…';
        loadScript('https://cdn.vaptcha.com/v3.js', init, function () {
            st.className = 'badge err';
            st.textContent = 'SDK 加载失败（两个源都不可达）';
        });
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
        vaptcha({
            vid: vid,
            mode: 'click',
            scene: scene,
            container: '#vBox',
            area: 'auto'
        }).then(function (obj) {
            obj.render();
            st.className = 'badge load';
            st.textContent = '等待点击验证…';
            var onPass = function () {
                lastToken = obj.getToken ? obj.getToken() : '';
                st.className = 'badge ok';
                st.textContent = '前端验证通过，token 已获取';
                tokBox.style.display = 'block';
                tokBox.textContent = 'token: ' + lastToken;
                if (lastToken) doVerify(lastToken, 1);
            };
            obj.listen('pass', onPass);
            obj.listen('success', onPass);   // 兼容不同版本事件名
        }).catch(function (e) {
            st.className = 'badge err';
            st.textContent = '初始化失败: ' + (e && e.message ? e.message : e);
        });
    }

    function doVerify(token, useEp) {
        ep = useEp;
        vBox.style.display = 'block';
        vBox.textContent = '服务端校验中（端点' + ep + '）…';
        fetch('captcha_test.php?action=verify&provider=vaptcha&ep=' + ep
              + '&token=' + encodeURIComponent(token))
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
