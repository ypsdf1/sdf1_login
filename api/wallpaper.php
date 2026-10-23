<?php
/**
 * 自定义背景壁纸源代理 —— 第46批创建，第47批改多源 fallback
 *
 * 用户端(player.php)与管理员端(admin.php)的自定义背景系统共用：
 *   action=bing   每日壁纸元数据（图片URL/标题/版权/日期），6小时文件缓存
 *                 第47批：单一必应源 → 四级 fallback 链（用户侧必应取不到图的对策）：
 *                   1) www.bing.com HPImageArchive   2) cn.bing.com 镜像
 *                   3) NASA APOD (api.nasa.gov)      4) picsum.photos 兜底（永不失败）
 *   action=image  按 u 参数转发图片二进制（白名单域，防SSRF/防盗链），浏览器缓存6小时
 *                 第47批：白名单随多源扩充 NASA / picsum / unsplash 域
 *
 * 自定义图片URL模式为浏览器直连（前端 background-image），不经过本代理。
 * 自包含轻量实现，不依赖 core.php/DB。
 */

while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');

/** 壁纸元数据缓存时长（秒）—— 每日换图，6小时足够 */
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

/* ===== 每日壁纸元数据（第47批：多源 fallback 链） ===== */
if ($act === 'bing') {
    // ★第48批#247：key 版本化 bing -> bing_v2（旧缓存存着无 api/ 前缀的坏 URL，须失效）
    $cached = wpjCacheGet('bing_v2', WPJ_BING_TTL);
    if ($cached !== null) { wpjOut(true, 'ok', $cached); }

    $data = null;

    // 源1/2：必应官方 HPImageArchive（主域失败换中国镜像）
    foreach (array('https://www.bing.com', 'https://cn.bing.com') as $base) {
        $body = wpjFetch($base . '/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN', 6, $base . '/');
        $j = json_decode($body, true);
        if (!is_array($j) || empty($j['images'][0]['urlbase'])) { continue; }
        $img = $j['images'][0];
        $direct = $base . $img['urlbase'] . '_1920x1080.jpg';
        $data = array(
            // 前端优先用代理地址（防防盗链），direct 作兜底直连
            // ★第48批#247：补 api/ 前缀（原相对路径经前端路由转发后 404，图片取不到）
            'url'       => 'api/wallpaper.php?action=image&u=' . rawurlencode($direct),
            'direct'    => $direct,
            'source'    => 'bing',
            'title'     => isset($img['title'])     ? $img['title']     : '',
            'copyright' => isset($img['copyright']) ? $img['copyright'] : '',
            'date'      => isset($img['startdate']) ? $img['startdate'] : '',
        );
        break;
    }

    // 源3：NASA 每日天文图 APOD（DEMO_KEY 限速，但有6小时缓存，服务器低频调用足够）
    if ($data === null) {
        $body = wpjFetch('https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&hd=true', 8, 'https://api.nasa.gov/');
        $j = json_decode($body, true);
        if (is_array($j) && !empty($j['hdurl']) && preg_match('#^https?://#i', $j['hdurl'])) {
            $direct = $j['hdurl'];
            $data = array(
                // ★第48批#247：补 api/ 前缀（同 bing）
                'url'       => 'api/wallpaper.php?action=image&u=' . rawurlencode($direct),
                'direct'    => $direct,
                'source'    => 'nasa',
                'title'     => isset($j['title'])     ? $j['title']     : 'NASA 每日天文图',
                'copyright' => isset($j['copyright']) ? $j['copyright'] : 'NASA APOD',
                'date'      => isset($j['date'])      ? $j['date']      : date('Y-m-d'),
            );
        }
    }

    // 源4：picsum 固定尺寸摄影图（无 key、永不应答失败的最后兜底；302 跳转浏览器直接可用）
    if ($data === null) {
        $direct = 'https://picsum.photos/1920/1080';
        $data = array(
            'url'       => $direct,
            'direct'    => $direct,
            'source'    => 'picsum',
            'title'     => '随机摄影图',
            'copyright' => 'Photos from Unsplash (picsum.photos)',
            'date'      => date('Y-m-d'),
        );
    }

    wpjCacheSet('bing_v2', $data);
    wpjOut(true, 'ok', $data);
}

/* ===== 图片转发（白名单域，防 SSRF；第47批随多源扩充） ===== */
if ($act === 'image') {
    $u = isset($_GET['u']) ? (string)$_GET['u'] : '';
    $host = @parse_url($u, PHP_URL_HOST);
    if ($u === '' || !is_string($host) || $host === '') { wpjOut(false, '参数无效'); }
    $host = strtolower($host);
    $allow = array(
        // 必应（源1/2）
        'www.bing.com', 'bing.com', 'th.bing.com', 'cn.bing.com',
        // NASA APOD（源3，hdurl 指向 apod.nasa.gov / www.nasa.gov 等）
        'apod.nasa.gov', 'www.nasa.gov', 'images-api.nasa.gov',
        // picsum 兜底链（源4，302 目标域）
        'picsum.photos', 'fastly.picsum.photos', 'images.unsplash.com',
    );
    if (!in_array($host, $allow, true)) { wpjOut(false, '不支持的图片源'); }

    $referer = 'https://www.bing.com/';
    if ($host === 'apod.nasa.gov' || $host === 'www.nasa.gov' || $host === 'images-api.nasa.gov') {
        $referer = 'https://apod.nasa.gov/';
    } elseif ($host === 'picsum.photos' || $host === 'fastly.picsum.photos' || $host === 'images.unsplash.com') {
        $referer = 'https://picsum.photos/';
    }
    $bin = wpjFetch($u, 10, $referer);
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
