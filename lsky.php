<?php
/**
 * 兰空图床（Lsky Pro）API 客户端 —— 工单图片上传专用
 *
 * 接口规格（图床官方 API 文档，站点 https://img.ypshidifu.cn/api/v1/）：
 *   POST /tokens  body {email,password}          -> {status, data:{token, expires_at}}
 *   POST /upload  multipart: file(+strategy_id)
 *                 Header: Authorization: Bearer <token>
 *                 -> {status, data:{key,links:{url,html,markdown,...},thumbnail_url,...}}
 *
 * 设计要点：
 *   - 凭据在 lsky_keys.php（仓库只有空模板，部署脚本永不上传）；
 *   - token 有有效期 -> 文件缓存 + 提前 60 秒自动续签 + 上传遇 401 自动强刷重签重试 1 次；
 *   - 本文件不含任何敏感值，可入库、可批量部署。
 *
 * ★ 依赖 core.php 的 error()/success()（本文件只被 api/ticket.php 引入，core 必然已加载）。
 */

// 图床前缀白名单：写库前校验图片 URL 只能来自自家图床（防存任意 URL）。
if (!defined('LSKY_ALLOWED_HOST_PREFIX')) {
    define('LSKY_ALLOWED_HOST_PREFIX', 'https://img.ypshidifu.cn/');
}

/** 加载 lsky_keys.php（只加载一次），并检查必填项 */
function lskyLoadKeys() {
    static $done = false;
    if ($done) return;
    $file = __DIR__ . '/lsky_keys.php';
    if (!file_exists($file)) {
        error('图床配置缺失：服务器上没有 lsky_keys.php（请参照仓库模板放置）', 500);
    }
    require_once $file;
    $done = true;
    if (!defined('LSKY_API_URL') || !defined('LSKY_EMAIL') || !defined('LSKY_PASSWORD')) {
        error('图床配置不完整：LSKY_API_URL / LSKY_EMAIL / LSKY_PASSWORD 必须定义', 500);
    }
}

/**
 * 底层 HTTP（curl）。$isMultipart=true 时 $payload 直接作为 CURLOPT_POSTFIELDS 数组
 * （boundary 交给 curl 自动处理，切勿手写 Content-Type）。
 * 返回 ['ok','status','msg','data']。
 */
function lskyCurlJson($method, $url, $payload = null, $token = null, $isMultipart = false) {
    if (!function_exists('curl_init')) {
        return ['ok' => false, 'status' => 0, 'msg' => '服务器缺少 PHP curl 扩展', 'data' => null];
    }
    $ch = curl_init();
    curl_setopt($ch, CURLOPT_URL, $url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 10);
    curl_setopt($ch, CURLOPT_TIMEOUT, 60);
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, true);
    curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 2);

    $headers = ['Accept: application/json'];
    if ($token !== null && $token !== '') $headers[] = 'Authorization: Bearer ' . $token;

    if ($method === 'POST') {
        curl_setopt($ch, CURLOPT_POST, true);
        if ($isMultipart) {
            curl_setopt($ch, CURLOPT_POSTFIELDS, $payload); // 数组 -> multipart
        } else {
            $json = json_encode($payload === null ? new stdClass() : $payload);
            curl_setopt($ch, CURLOPT_POSTFIELDS, $json);
            $headers[] = 'Content-Type: application/json';
        }
    } else {
        curl_setopt($ch, CURLOPT_CUSTOMREQUEST, $method);
    }
    curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);

    $body = curl_exec($ch);
    $errno = curl_errno($ch);
    $err = curl_error($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);

    if ($errno) {
        return ['ok' => false, 'status' => 0, 'msg' => 'cURL错误: ' . $err, 'data' => null];
    }
    $j = json_decode((string)$body, true);
    return [
        'ok'   => ($status >= 200 && $status < 300),
        'status' => $status,
        'msg'  => (is_array($j) && isset($j['message']) && $j['message'] !== '')
                ? (string)$j['message']
                : ('HTTP ' . $status . ' ' . substr((string)$body, 0, 160)),
        'data' => (is_array($j) && isset($j['data']) && is_array($j['data'])) ? $j['data'] : null,
    ];
}

/**
 * 获取 API token：缓存优先（提前 60 秒过期），失效则 POST /tokens 续签并落盘。
 * 续签失败但缓存里还有旧 token 时返回旧 token（图床临时抖动不至于让上传全挂）。
 */
function lskyGetToken($force = false) {
    lskyLoadKeys();
    $cache = defined('LSKY_TOKEN_CACHE') ? LSKY_TOKEN_CACHE : (__DIR__ . '/db/lsky_token.json');
    $cached = null;
    if (!$force && is_readable($cache)) {
        $cached = json_decode((string)@file_get_contents($cache), true);
        if (is_array($cached) && !empty($cached['token'])
                && isset($cached['expire_at']) && time() < (int)$cached['expire_at'] - 60) {
            return $cached['token'];
        }
    }
    $r = lskyCurlJson('POST', rtrim(LSKY_API_URL, '/') . '/tokens',
            ['email' => LSKY_EMAIL, 'password' => LSKY_PASSWORD]);
    if ($r['ok'] && !empty($r['data']['token'])) {
        $token = (string)$r['data']['token'];
        $expire = time() + 3600; // 响应没有 expires_at 时按 1 小时兜底
        if (!empty($r['data']['expires_at'])) {
            $ts = strtotime((string)$r['data']['expires_at']);
            if ($ts !== false && $ts > time()) $expire = $ts;
        }
        $dir = dirname($cache);
        if (!is_dir($dir)) @mkdir($dir, 0755, true);
        @file_put_contents($cache,
            json_encode(['token' => $token, 'expire_at' => $expire, 'cached_at' => time()]),
            LOCK_EX);
        return $token;
    }
    // 续签失败 -> 旧缓存顶着用
    if (is_array($cached) && !empty($cached['token'])) return $cached['token'];
    return null;
}

/**
 * 上传一张已通过校验的图片到图床。
 * 401（token 失效）自动强刷 token 重试 1 次。
 * 成功返回 ['ok'=>true,'url'=>..,'thumb'=>..,'key'=>..]，失败 ['ok'=>false,'msg'=>..]
 */
function lskyUploadFile($tmpPath, $origName) {
    lskyLoadKeys();
    if (!is_uploaded_file($tmpPath)) {
        return ['ok' => false, 'msg' => '非法上传（is_uploaded_file 校验失败）'];
    }
    $mime = 'application/octet-stream';
    $info = @getimagesize($tmpPath);
    if (!empty($info['mime'])) $mime = (string)$info['mime'];

    for ($try = 0; $try < 2; $try++) {
        $token = lskyGetToken($try > 0);
        if (!$token) {
            return ['ok' => false, 'msg' => '获取图床 token 失败（请检查 lsky_keys.php 的邮箱密码）'];
        }
        $fields = ['file' => new CURLFile($tmpPath, $mime, $origName)];
        if (defined('LSKY_STRATEGY_ID') && (int)LSKY_STRATEGY_ID > 0) {
            $fields['strategy_id'] = (int)LSKY_STRATEGY_ID;
        }
        $r = lskyCurlJson('POST', rtrim(LSKY_API_URL, '/') . '/upload', $fields, $token, true);
        if ($r['ok'] && !empty($r['data']['links']['url'])) {
            $url = (string)$r['data']['links']['url'];
            $thumb = !empty($r['data']['thumbnail_url']) ? (string)$r['data']['thumbnail_url'] : $url;
            return ['ok' => true, 'url' => $url, 'thumb' => $thumb,
                    'key' => isset($r['data']['key']) ? (string)$r['data']['key'] : ''];
        }
        if ($r['status'] !== 401) {
            return ['ok' => false, 'msg' => $r['msg']];
        }
        // 401 -> 第 2 轮由 lskyGetToken(true) 强制续签后重试
    }
    return ['ok' => false, 'msg' => '上传失败（token 无效或已过期）'];
}
