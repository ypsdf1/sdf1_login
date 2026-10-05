<?php
/**
 * 人机验证码统一接入（CF Turnstile + VAPTCHA V4）
 *
 * 覆盖三大场景：
 *   1. 管理员登录        admin_login.php        → api/admin.php       adminDoLogin()
 *   2. 用户后台账密登录  login.php(密码页签)    → api/sync.php        webLoginRequest()
 *   3. 正版验证提交前    api/minecraft_auth.php paste_code_page → verify_code
 *
 * 用哪一家由 config.php 的 CAPTCHA_PROVIDER 决定（未定义时默认 CF）：
 *   'CF' / 'cloudflare' / 'turnstile'（不区分大小写） → Cloudflare Turnstile
 *   'VA' / 'vaptcha'    （不区分大小写）               → VAPTCHA V4
 *   '' / 'off' / 'none'                                → 关闭人机验证
 *
 * 密钥来源（与 captcha_test.php 同一套优先级，URL 参数除外）：
 *   captcha_keys.php > config.php 常量 > robots.json
 *
 * 安全策略：
 *   - 密钥完全没配 → 放行（本地开发/未启用环境不受影响），记日志
 *   - 密钥已配但没提交 token → 拒绝
 *   - 校验请求网络不通（传输层失败）→ 放行 + 告警日志（避免第三方故障把所有人锁在门外）
 *   - 校验接口正常返回但判定不通过 → 拒绝
 *
 * 本文件自包含，不依赖 core.php（api/minecraft_auth.php 不引 core.php）。
 */

// ====================================================================
//  配置解析
// ====================================================================

require_once __DIR__ . '/config.php';   // 取 CAPTCHA_PROVIDER（require_once，不会重复定义）

/** 取当前启用的验证码厂商：'cf' | 'va' | ''（关闭） */
function cgProvider() {
    $raw = defined('CAPTCHA_PROVIDER') ? (string) CAPTCHA_PROVIDER : 'CF';
    $v = strtolower(trim($raw));
    if ($v === '' || $v === 'off' || $v === 'none' || $v === 'false' || $v === '0') return '';
    if (in_array($v, ['va', 'v', 'vaptcha', 'vaptcha-v4', 'vaptcha_v4'], true)) return 'va';
    // cf / cloudflare / turnstile / 未识别值 → 一律按 CF 处理（默认厂商）
    return 'cf';
}

/** 读取密钥（四级回退，静态缓存）。 */
function cgKeys() {
    static $k = null;
    if ($k !== null) return $k;
    $k = ['source' => '未配置', 'cf_sitekey' => '', 'cf_secret' => '',
          'v_vid' => '', 'v_key' => '', 'v_scene' => 0];

    // 1) captcha_keys.php（独立密钥文件，真实值只在服务器上，仓库里是空模板）
    $f = __DIR__ . '/captcha_keys.php';
    if (is_file($f)) {
        $CF_SITEKEY = ''; $CF_SECRET = ''; $VAPTCHA_VID = ''; $VAPTCHA_VKEY = ''; $VAPTCHA_SCENE = 0;
        @include $f;
        if ($CF_SITEKEY !== '' || $VAPTCHA_VID !== '') {
            $k = ['source' => 'captcha_keys.php', 'cf_sitekey' => (string) $CF_SITEKEY,
                  'cf_secret' => (string) $CF_SECRET, 'v_vid' => (string) $VAPTCHA_VID,
                  'v_key' => (string) $VAPTCHA_VKEY, 'v_scene' => (int) $VAPTCHA_SCENE];
            return $k;
        }
    }

    // 2) config.php 常量
    if (defined('CF_TURNSTILE_SITEKEY') || defined('VAPTCHA_VID')) {
        $k = ['source' => 'config.php 常量',
              'cf_sitekey' => defined('CF_TURNSTILE_SITEKEY') ? CF_TURNSTILE_SITEKEY : '',
              'cf_secret'   => defined('CF_TURNSTILE_SECRET')   ? CF_TURNSTILE_SECRET   : '',
              'v_vid'       => defined('VAPTCHA_VID')            ? VAPTCHA_VID           : '',
              'v_key'       => defined('VAPTCHA_VKEY')           ? VAPTCHA_VKEY          : '',
              'v_scene'     => defined('VAPTCHA_SCENE')          ? VAPTCHA_SCENE         : 0];
        if ($k['cf_sitekey'] !== '' || $k['v_vid'] !== '') return $k;
    }

    // 3) robots.json 兜底（严禁入库的密钥清单）
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
                    $k['v_vid']   = (string) ($item['VID'] ?? '');
                    $k['v_key']   = (string) ($item['VKey'] ?? '');
                    $k['v_scene'] = 0;
                }
            }
            if ($k['cf_sitekey'] !== '' || $k['v_vid'] !== '') $k['source'] = 'robots.json';
        }
    }
    return $k;
}

/** 当前厂商的密钥是否齐全。 */
function cgConfigured($p = null) {
    $p = ($p === null) ? cgProvider() : $p;
    if ($p === '') return false;
    $k = cgKeys();
    return ($p === 'cf') ? ($k['cf_sitekey'] !== '' && $k['cf_secret'] !== '')
                         : ($k['v_vid'] !== '' && $k['v_key'] !== '');
}

/** 是否需要做整套人机验证（厂商开启 + 密钥齐全）。 */
function cgActive() {
    return cgConfigured(cgProvider());
}

// ====================================================================
//  日志
// ====================================================================
function cgLog($msg) {
    $line = '[' . date('Y-m-d H:i:s') . '] [captcha] ' . $msg . "\n";
    @error_log('[captcha] ' . $msg);
    if (function_exists('debugLog')) {
        debugLog('captcha', ['msg' => $msg]);
        return;
    }
    $dir = __DIR__ . '/db';
    if (!is_dir($dir)) { @mkdir($dir, 0755, true); }
    $f = $dir . '/debug.log';
    @file_put_contents($f, $line, FILE_APPEND | LOCK_EX);
    // ★ 保留策略（2026-10-05）：只留最近3天 + ≤5MB，任一超标即清理到两条件同时满足
    if (is_file(__DIR__ . '/log_maintain.php')) {
        @require_once __DIR__ . '/log_maintain.php';
        if (function_exists('logMaintain')) { @logMaintain($f); }
    }
}

// ====================================================================
//  HTTP（PHP 8.5 起 curl_close 是 Deprecated，会污染 JSON —— 一律不调用）
// ====================================================================
function cgHttpPostForm($url, array $fields) {
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

function cgHttpPostJson($url, array $payload) {
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

// ====================================================================
//  取客户端 IP
// ====================================================================
function cgClientIp() {
    if (function_exists('secClientIp')) {
        $ip = secClientIp();
        if (is_string($ip) && $ip !== '') return $ip;
    }
    return (string) ($_SERVER['REMOTE_ADDR'] ?? '');
}

// ====================================================================
//  从请求里收集验证码字段（POST 表单 / GET / JSON body 三路通吃）
// ====================================================================
function cgInput() {
    $keys = ['captcha_provider', 'captcha_token', 'captcha_knock', 'captcha_dfu', 'captcha_ip'];
    $raw = null;   // JSON body 缓存
    $rawRead = false;
    $out = [];
    foreach ($keys as $k) {
        $v = '';
        if (isset($_POST[$k])) $v = $_POST[$k];
        elseif (isset($_GET[$k])) $v = $_GET[$k];
        elseif (function_exists('getParam')) {
            // core.php 的 getParam 会解析 JSON body；minecraft_auth.php 的只读 GET（上面已兜过）
            $v = getParam($k, '');
        }
        $out[$k] = is_string($v) ? trim($v) : '';
    }
    return $out;
}

// ====================================================================
//  服务端校验
//  返回 ['ok'=>bool, 'msg'=>string, 'skipped'=>bool]
// ====================================================================
function cgVerify(array $in) {
    $p = cgProvider();
    if ($p === '') return ['ok' => true, 'skipped' => true, 'msg' => '验证码已关闭'];

    $k = cgKeys();
    if ($p === 'cf' && ($k['cf_sitekey'] === '' || $k['cf_secret'] === '')) {
        cgLog('skip: CF 密钥未配置 (source=' . $k['source'] . ')');
        return ['ok' => true, 'skipped' => true, 'msg' => 'CF 密钥未配置，已跳过'];
    }
    if ($p === 'va' && ($k['v_vid'] === '' || $k['v_key'] === '')) {
        cgLog('skip: VAPTCHA 密钥未配置 (source=' . $k['source'] . ')');
        return ['ok' => true, 'skipped' => true, 'msg' => 'VAPTCHA 密钥未配置，已跳过'];
    }

    // 前端渲染的厂商必须和服务端配置一致（不一致=篡改或配置漂移）
    $claimed = strtolower($in['captcha_provider'] ?? '');
    if ($claimed !== '' && $claimed !== $p) {
        cgLog('reject: provider mismatch claimed=' . $claimed . ' configured=' . $p);
        return ['ok' => false, 'skipped' => false, 'msg' => '人机验证配置不匹配，请刷新页面重试'];
    }

    $token = (string) ($in['captcha_token'] ?? '');
    if ($token === '' || strlen($token) > 4096) {
        cgLog('reject: 空 token (provider=' . $p . ')');
        return ['ok' => false, 'skipped' => false, 'msg' => '请先完成人机验证'];
    }

    if ($p === 'cf') return cgVerifyCf($k, $token);
    return cgVerifyVa($k, $in, $token);
}

function cgVerifyCf(array $k, $token) {
    $endpoint = 'https://challenges.cloudflare.com/turnstile/v0/siteverify';
    $body = ['secret' => $k['cf_secret'], 'response' => $token];
    $ip = cgClientIp();
    if ($ip !== '') $body['remoteip'] = $ip;

    $r = cgHttpPostForm($endpoint, $body);
    if ($r['errno'] !== 0 || $r['status'] === 0) {
        cgLog('fail-open: CF siteverify 传输失败 status=' . $r['status'] . ' err=' . $r['err']);
        return ['ok' => true, 'skipped' => true, 'msg' => '验证码服务不可用，已放行'];
    }
    $j = json_decode($r['body'], true);
    if (is_array($j) && !empty($j['success'])) {
        cgLog('pass: CF (hostname=' . (isset($j['hostname']) ? $j['hostname'] : '-') . ')');
        return ['ok' => true, 'skipped' => false, 'msg' => 'ok'];
    }
    $errc = is_array($j) && isset($j['error-codes']) ? implode(',', (array) $j['error-codes']) : 'no-json';
    cgLog('reject: CF http=' . $r['status'] . ' codes=' . $errc . ' body=' . substr($r['body'], 0, 200));
    return ['ok' => false, 'skipped' => false, 'msg' => '人机验证未通过，请重试'];
}

function cgVerifyVa(array $k, array $in, $token) {
    $knock = substr((string) ($in['captcha_knock'] ?? ''), 0, 256);
    $dfu   = substr((string) ($in['captcha_dfu'] ?? ''), 0, 512);
    $sip   = substr((string) ($in['captcha_ip'] ?? ''), 0, 64);
    if ($sip === '') $sip = cgClientIp();

    $endpoint = 'https://v41.vaptcha.com/api/verify';
    $r = cgHttpPostJson($endpoint, [
        'vid'   => $k['v_vid'],
        'vkey'  => $k['v_key'],
        'token' => $token,
        'knock' => $knock,
        'dfu'   => $dfu,
        'ip'    => $sip,
    ]);
    if ($r['errno'] !== 0 || $r['status'] === 0) {
        cgLog('fail-open: VAPTCHA verify 传输失败 status=' . $r['status'] . ' err=' . $r['err']);
        return ['ok' => true, 'skipped' => true, 'msg' => '验证码服务不可用，已放行'];
    }
    $j = json_decode($r['body'], true);
    $code = is_array($j) && isset($j['code']) ? (int) $j['code'] : -1;
    $res  = is_array($j) && isset($j['data']['result']) ? (bool) $j['data']['result'] : false;
    if ($code === 0 && $res) {
        cgLog('pass: VAPTCHA V4');
        return ['ok' => true, 'skipped' => false, 'msg' => 'ok'];
    }
    cgLog('reject: VAPTCHA http=' . $r['status'] . ' code=' . $code . ' result=' . var_export($res, true)
        . ' body=' . substr($r['body'], 0, 200));
    return ['ok' => false, 'skipped' => false, 'msg' => '人机验证未通过，请重试'];
}

/**
 * 强制校验：不通过就按当前环境的 error() 约定输出 JSON 并终止。
 * 三大 API 入口各调一次（Java 带 secret 的调用不经过这里）。
 */
function cgEnforce($context = '') {
    $r = cgVerify(cgInput());
    if ($r['ok']) return;
    cgLog('enforce-block' . ($context !== '' ? ' ctx=' . $context : '') . ' msg=' . $r['msg']);
    if (function_exists('error')) error($r['msg'], 403);
    http_response_code(403);
    header('Content-Type: application/json; charset=utf-8');
    $payload = ['success' => false, 'message' => $r['msg'], 'error' => $r['msg']];
    echo json_encode($payload, JSON_UNESCAPED_UNICODE);
    exit;
}

// ====================================================================
//  前端挂件（HTML + 脚本），未启用时返回空串
// ====================================================================
function cgWidgetHtml() {
    if (!cgActive()) return '';
    $p = cgProvider();
    $k = cgKeys();
    $sitekey = $p === 'cf' ? $k['cf_sitekey'] : '';
    $vid     = $p === 'va' ? $k['v_vid'] : '';

    $tpl = <<<'CGTPL'
<style>
.cg-box{margin:0 0 16px;padding:14px 14px 12px;background:#0d1117;border:1px solid #30363d;
  border-radius:8px;text-align:left}
.cg-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:10px}
.cg-title{font-size:13px;color:#8b949e}
.cg-badge{font-size:12px;padding:2px 9px;border-radius:999px;background:#21262d;color:#8b949e}
.cg-badge.ok{background:#1a7f37;color:#fff}
.cg-badge.bad{background:#da3633;color:#fff}
.cg-badge.warn{background:#9e6a03;color:#fff}
.cg-err{display:none;margin-top:8px;font-size:12.5px;color:#f85149}
.cg-btn{width:100%;padding:11px 14px;font-size:14px;font-weight:600;border:none;border-radius:6px;
  background:#21262d;color:#e6edf3;cursor:not-allowed}
.cg-btn:enabled{background:#238636;color:#fff;cursor:pointer}
.cg-btn:enabled:hover{background:#2ea043}
</style>
<div class="cg-box" id="cgBox" data-provider="__PROVIDER__">
  <div class="cg-head"><span class="cg-title">🛡 人机验证</span><span class="cg-badge" id="cgBadge">加载中…</span></div>
  <div id="cgCfBox" style="display:none"></div>
  <div id="cgVaBox" style="display:none"></div>
  <button type="button" class="cg-btn" id="cgVaBtn" style="display:none" disabled>点击完成人机验证</button>
  <div class="cg-err" id="cgErr"></div>
  <input type="hidden" name="captcha_provider" id="captcha_provider" value="__PROVIDER__">
  <input type="hidden" name="captcha_token" id="captcha_token" value="">
  <input type="hidden" name="captcha_knock" id="captcha_knock" value="">
  <input type="hidden" name="captcha_dfu" id="captcha_dfu" value="">
  <input type="hidden" name="captcha_ip" id="captcha_ip" value="">
</div>
<script>
(function(){
  var P = '__PROVIDER__';
  var st = {tok:'',knock:'',dfu:'',ip:'',err:'',vaObj:null,vaBusy:false,vaDone:false};
  function el(id){return document.getElementById(id);}
  function put(){el('captcha_token').value=st.tok;el('captcha_knock').value=st.knock;
    el('captcha_dfu').value=st.dfu;el('captcha_ip').value=st.ip;}
  function badge(t,c){var b=el('cgBadge');if(!b)return;b.textContent=t;b.className='cg-badge '+(c||'');}
  function fail(m){st.err=m;badge('验证未通过','bad');var e=el('cgErr');
    if(e){e.textContent=m;e.style.display='block';}}
  function collect(){return {captcha_provider:P,captcha_token:st.tok,captcha_knock:st.knock,
    captcha_dfu:st.dfu,captcha_ip:st.ip};}
  function loadScript(src,ok,failFn){var s=document.createElement('script');s.src=src;s.async=true;
    s.onload=ok;s.onerror=failFn;document.head.appendChild(s);}

  function initCf(){
    el('cgCfBox').style.display='block';
    // ★ 关键：本页必须自己拉起 Turnstile SDK（测试页是 <script src> 引的，本挂件漏了）
    if (typeof turnstile === 'undefined' && !window.__cgSdkLoading){
      window.__cgSdkLoading = true;
      loadScript('https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit',
        function(){ window.__cgSdkLoading = false; window.__cgSdkOk = true; },
        function(){ window.__cgSdkLoading = false; window.__cgSdkErr = true; });
    }
    var tries=0;
    (function wait(){
      if (typeof turnstile === 'undefined'){
        if (window.__cgSdkErr){ fail('Turnstile SDK 加载失败（网络不通或域名未加入白名单），请刷新重试'); return; }
        if (++tries > 96){ fail('验证组件加载失败（网络或域名未加入白名单），请刷新重试'); return; }
        return setTimeout(wait,250);
      }
      badge('请完成验证','');
      try{
        turnstile.render('#cgCfBox', {
          sitekey: '__SITEKEY__',
          theme: 'auto',
          callback: function(t){ st.tok=t; st.err=''; put(); badge('已通过','ok');
            var e=el('cgErr'); if(e) e.style.display='none'; },
          'error-callback': function(m){ fail('验证错误: '+m); },
          'expired-callback': function(){ st.tok=''; put(); badge('已过期，请重新勾选','warn'); }
        });
      }catch(ex){ fail('render 异常: '+ex.message); }
    })();
  }

  function initVa(){
    el('cgVaBox').style.display='block';
    var btn=el('cgVaBtn'); btn.style.display='block';
    loadScript('https://c4.vaptcha.com/src/v4.js', function(){
      if (typeof vaptcha !== 'function'){ fail('VAPTCHA SDK 异常'); return; }
      vaptcha({vid:'__VID__', container:'#cgVaBox', lang:'zh-CN'}).then(function(obj){
        st.vaObj=obj; badge('准备就绪','');
        btn.disabled=false;
        btn.addEventListener('click', function(){ validateVa(function(){}); });
      }).catch(function(e){ fail('VAPTCHA 初始化失败: '+(e&&e.message?e.message:e)); });
    }, function(){ fail('VAPTCHA SDK 加载失败（c4.vaptcha.com 不可达）'); });
  }

  function validateVa(done){
    if (st.tok){ done(true); return; }
    if (!st.vaObj){ fail(st.err || '人机验证组件未就绪，请稍候重试'); done(false); return; }
    if (st.vaBusy) return;
    st.vaBusy=true;
    badge('验证中…','');
    Promise.resolve(st.vaObj.validate()).then(function(r){
      st.vaBusy=false;
      if (!r || !r.token){ fail('未完成人机验证'); done(false); return; }
      st.tok=r.token; st.knock=r.knock||''; st.dfu=r.dfu||''; st.ip=r.ip||'';
      st.err=''; st.vaDone=true; put(); badge('已通过','ok');
      var e=el('cgErr'); if(e) e.style.display='none';
      var b=el('cgVaBtn'); if(b){ b.textContent='人机验证已完成'; b.disabled=true; }
      done(true);
    }).catch(function(e){
      st.vaBusy=false;
      fail('人机验证失败: '+(e&&e.message?e.message:e));
      done(false);
    });
  }

  window.CaptchaGuard = {
    provider: P,
    payload: function(){
      return new Promise(function(resolve,reject){
        if (st.tok) { resolve(collect()); return; }
        if (P === 'cf'){
          if (st.err){ reject(new Error(st.err)); return; }
          var t0=Date.now();
          var iv=setInterval(function(){
            if (st.tok){ clearInterval(iv); resolve(collect()); }
            else if (st.err){ clearInterval(iv); reject(new Error(st.err)); }
            else if (Date.now()-t0 > 60000){ clearInterval(iv);
              reject(new Error('人机验证超时，请刷新页面重试')); }
          },200);
          return;
        }
        if (st.err){ reject(new Error(st.err)); return; }
        validateVa(function(ok){
          if (ok) resolve(collect());
          else reject(new Error(st.err || '请先完成人机验证'));
        });
      });
    }
  };

  if (P === 'cf') initCf(); else initVa();
})();
</script>
CGTPL;

    $html = str_replace(['__PROVIDER__', '__SITEKEY__', '__VID__'],
                        [$p, $sitekey, $vid], $tpl);
    return $html;
}
