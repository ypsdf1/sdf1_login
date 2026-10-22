<?php
/**
 * 自定义背景壁纸源代理 —— 第46批
 *
 * 用户端(player.php)与管理员端(admin.php)的自定义背景系统共用：
 *   action=bing   返回必应每日壁纸元数据（图片URL/标题/版权/日期），6小时文件缓存
 *   action=image  按 u 参数转发图片二进制（仅白名单 bing.com 域，防SSRF/防盗链），浏览器缓存6小时
 *
 * 自定义图片URL模式为浏览器直连（前端 background-image），不经过本代理。
 * 自包含轻量实现，不依赖 core.php/DB。
 */

while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');

/** 必应结果缓存时长（秒）—— 每日换图，6小时足够 */
define('WPJ_BING_TTL', 21600);

function wpjOut($ok, $msg, $data = null) {
    header('Content-Type: application/json; charset=utf-8');
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: no-store');
    echo json_encode(array('success' => $ok, 'message' => $msg, 'data' => $data),
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/** 缓存文件放系统临时目录 —— Web 根可能只读，临时目录必可写 */
function wpjCacheFile($key) {
    return rtrim(sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR . 'sdf1_wp_' . $key . '.json';
}

function wpjCacheGet($key, $ttl) {
    $f = wpjCacheFile($key);
    if (is_file($f) && (time() - filemtime($f)) < $ttl) {
        $j = json_decode((string)@file_get_contents($f), true);
        if (is_array($j)) { return $j; }
    }
    return null;
}

function wpjCacheSet($key, $arr) {
    @file_put_contents(wpjCacheFile($key),
        json_encode($arr, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
}

function wpjFetch($url, $timeout, $referer) {
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, array(
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => $timeout,
            CURLOPT_CONNECTTIMEOUT => 4,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_USERAGENT      => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) sdf1-wallpaper',
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_REFERER        => $referer,
        ));
        $body = curl_exec($ch);
        curl_close($ch);
        return is_string($body) ? $body : '';
    }
    $ctx = stream_context_create(array('http' => array(
        'timeout' => $timeout,
        'header'  => "User-Agent: Mozilla/5.0 sdf1-wallpaper\r\nReferer: " . $referer . "\r\n",
    )));
    $body = @file_get_contents($url, false, $ctx);
    return is_string($body) ? $body : '';
}

$act = isset($_GET['action']) ? $_GET['action'] : '';

/* ===== 必应每日壁纸元数据 ===== */
if ($act === 'bing') {
    $cached = wpjCacheGet('bing', WPJ_BING_TTL);
    if ($cached !== null) { wpjOut(true, 'ok', $cached); }

    $api = 'https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN';
    $body = wpjFetch($api, 6, 'https://www.bing.com/');
    $j = json_decode($body, true);
    if (!is_array($j) || empty($j['images'][0]['urlbase'])) {
        wpjOut(false, '壁纸源暂不可用，请稍后重试或改用图片URL');
    }
    $img  = $j['images'][0];
    $direct = 'https://www.bing.com' . $img['urlbase'] . '_1920x1080.jpg';
    $data = array(
        // 前端优先用代理地址（防防盗链），direct 作兜底直连
        'url'       => 'wallpaper.php?action=image&u=' . rawurlencode($direct),
        'direct'    => $direct,
        'title'     => isset($img['title'])     ? $img['title']     : '',
        'copyright' => isset($img['copyright']) ? $img['copyright'] : '',
        'date'      => isset($img['startdate']) ? $img['startdate'] : '',
    );
    wpjCacheSet('bing', $data);
    wpjOut(true, 'ok', $data);
}

/* ===== 图片转发（仅 bing 白名单，防 SSRF） ===== */
if ($act === 'image') {
    $u = isset($_GET['u']) ? (string)$_GET['u'] : '';
    $host = @parse_url($u, PHP_URL_HOST);
    if ($u === '' || !is_string($host) || $host === '') { wpjOut(false, '参数无效'); }
    $host = strtolower($host);
    $allow = array('www.bing.com', 'bing.com', 'th.bing.com', 'cn.bing.com');
    if (!in_array($host, $allow, true)) { wpjOut(false, '不支持的图片源'); }

    $bin = wpjFetch($u, 10, 'https://www.bing.com/');
    if (!is_string($bin) || strlen($bin) < 1000) { wpjOut(false, '图片下载失败'); }
    // 粗校验图片魔数，防返回HTML错误页被当图片缓存
    $head = substr($bin, 0, 3);
    if ($head !== "\xFF\xD8\xFF" && substr($bin, 0, 4) !== "\x89PNG" && stripos(substr($bin, 0, 32), 'RIFF') !== 0) {
        wpjOut(false, '图片源返回内容异常');
    }
    header('Content-Type: image/jpeg');
    header('Cache-Control: public, max-age=21600');
    echo $bin;
    exit;
}

wpjOut(false, '未知 action');
