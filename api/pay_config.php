<?php
/**
 * 收银台订单前缀 / 订单号生成 —— 统一读配置的公共小工具（2026-10-06 创建，2026-10-07 改读 pay.md）
 *
 * 背景：平台 MySQL 的 pay_order 是多商户共用表，创单与三个补单脚本此前都把前缀
 *      写死在各自代码里。生产服 / 测试服共用一个前缀就会互相捡单 —— 测试服
 *      补单器把生产服的已支付单补进测试库，反过来也一样（2026-10-06 串数据的根因）。
 *
 * ★ 2026-10-07 订单号规则改读站点根目录 pay.md（配置+文档二合一文件）：
 *      - 只认「### 以下是配置区域：」后第一个 ```json``` 块，其余说明区全部忽略；
 *      - 「前缀码」= 完整订单前缀（创单与补单永远一致），payOrderPrefix() 的第一来源；
 *      - 「是否固定订单号格式」= true → 用「订单号格式」模板：[]去掉、纯数字的 0 换随机；
 *        模板非纯数字（或为空）→ 回退保底 6 位递增序列 000001/000002…（计数器持久化在
 *        db/pay_order_seq.txt，flock 防并发撞单）；
 *      - = false → 按「当前配置」组合规则生成（+/- 连接符不进订单号，验证码位数读
 *        「订单验证码长度」字段而非文本里的数字）；
 *      - pay.md 缺失 / 解析失败 → 一律回退旧版逻辑（前缀+时间+4码+玩家名），行为与改造前一致。
 *
 * 前缀兼容（防串单，2026-10-06 事故修复的延续）：
 *      - payOrderPrefix()   第一来源 pay.md「前缀码」→ 回退 config.php 的 PAY_ORDER_PREFIX
 *        → 回退 'RE'，只保留字母数字（要拼进 SQL LIKE，与平台侧约定一致）；
 *      - payOrderIsOurs()   认「当前前缀 + 历史前缀(PAY_ORDER_PREFIX)」任一，改前缀后
 *        历史在途单仍被补单器/查询接口认领；
 *      - payOrderPrefixLikeAny()  给补单器用的双前缀 SQL 条件（poller_online /
 *        recharge_orders_api / pay_poller），扫描范围与 payOrderIsOurs 一致。
 *
 * ★ 本文件不依赖 core.php，任何脚本（含自包含的 poller）都能直接 require。
 * ★ 可用 define('PAY_MD_PATH', '/path/pay.md') 覆盖 pay.md 路径（仅测试用）。
 * ★ 债券换算（estimateBonds）不在本文件：金额→债券由后台「充值商店配置」
 *   (shop_configs.bond_reward) 决定，代码不干预比例。
 */

if (!function_exists('payMdOrderConfig')) {
    /**
     * 读 pay.md「### 以下是配置区域：」之后的第一个 ```json``` 块。
     * 二合一文件里说明区的表格 / 文本一律忽略。
     *
     * @return array|null 解析成功返回关联数组；缺失/损坏返回 null（调用方回退旧逻辑）
     */
    function payMdOrderConfig() {
        $path = defined('PAY_MD_PATH') ? PAY_MD_PATH : dirname(__DIR__) . '/pay.md';
        if (!is_file($path)) return null;
        $md = @file_get_contents($path);
        if (!is_string($md) || $md === '') return null;
        $pos = strpos($md, '以下是配置区域');
        if ($pos === false) return null;
        if (!preg_match('/```(?:json|JSON)?\s*(.*?)```/s', substr($md, $pos), $m)) return null;
        $cfg = json_decode(trim($m[1]), true);
        return is_array($cfg) ? $cfg : null;
    }
}

if (!function_exists('payOrderPrefix')) {
    /**
     * 订单号前缀（永不返回空串）—— 第一来源 pay.md「前缀码」。
     * pay.md 缺失/未配/解析失败时回退 config.php 的 PAY_ORDER_PREFIX，再回退 'RE'
     * （与 2026-10-06 改造前的行为完全一致，线上老配置零变化）。
     * 只保留字母数字：它要拼进 SQL 的 LIKE '前缀%'，也与平台侧约定一致。
     */
    function payOrderPrefix() {
        $p = '';
        $cfg = payMdOrderConfig();
        if (is_array($cfg) && isset($cfg['前缀码'])) {
            $p = trim((string)$cfg['前缀码']);
        }
        if ($p === '') {
            $p = defined('PAY_ORDER_PREFIX') ? trim((string)PAY_ORDER_PREFIX) : '';
        }
        if ($p === '') return 'RE';
        $p = preg_replace('/[^A-Za-z0-9]/', '', $p);
        return $p === '' ? 'RE' : $p;
    }
}

if (!function_exists('payOrderPrefixes')) {
    /**
     * 本端全部有效前缀 = [当前前缀(pay.md), 历史前缀(PAY_ORDER_PREFIX，不同时才带上)]。
     * 用于「认旧单」：改前缀后，改动前已生成、尚未补单的订单仍能被本端认领。
     * @return string[]
     */
    function payOrderPrefixes() {
        $list = array(payOrderPrefix());
        $legacy = defined('PAY_ORDER_PREFIX') ? preg_replace('/[^A-Za-z0-9]/', '', trim((string)PAY_ORDER_PREFIX)) : '';
        if ($legacy !== '' && !in_array($legacy, $list, true)) {
            $list[] = $legacy;
        }
        return $list;
    }
}

if (!function_exists('payOrderPrefixLen')) {
    /** 前缀长度（strncmp 用） */
    function payOrderPrefixLen() {
        return strlen(payOrderPrefix());
    }
}

if (!function_exists('payOrderIsOurs')) {
    /**
     * 判断一个 out_trade_no 是否本端订单（补单器/查询接口的兜底校验）
     * 当前前缀与历史前缀命中其一即算本端单。
     * @param string $outNo
     * @return bool
     */
    function payOrderIsOurs($outNo) {
        foreach (payOrderPrefixes() as $p) {
            if (strncmp((string)$outNo, $p, strlen($p)) === 0) return true;
        }
        return false;
    }
}

if (!function_exists('payOrderPrefixLike')) {
    /** SQL 片段：out_trade_no LIKE '当前前缀%'（单前缀，向后兼容保留） */
    function payOrderPrefixLike() {
        return payOrderPrefix() . '%';
    }
}

if (!function_exists('payOrderPrefixLikeAny')) {
    /**
     * SQL 条件片段：out_trade_no LIKE '当前前缀%' OR out_trade_no LIKE '历史前缀%'。
     * 补单器扫描用，与 payOrderIsOurs 的认定范围一致；前缀均已净化为字母数字，无需转义。
     * 用法：WHERE (".payOrderPrefixLikeAny().") AND status IN (1,2)
     * @return string
     */
    function payOrderPrefixLikeAny() {
        $conds = array();
        foreach (payOrderPrefixes() as $p) {
            $conds[] = "out_trade_no LIKE '" . $p . "%'";
        }
        return implode(' OR ', $conds);
    }
}

if (!function_exists('payRandomCode')) {
    /**
     * 随机校验码：长度 1~32，去掉易混的 I/O/0/1
     * @param int $len
     * @return string
     */
    function payRandomCode($len) {
        $len = (int)$len;
        if ($len < 1) $len = 4;
        if ($len > 32) $len = 32;
        $cs = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
        $out = '';
        for ($i = 0; $i < $len; $i++) {
            $out .= $cs[mt_rand(0, strlen($cs) - 1)];
        }
        return $out;
    }
}

if (!function_exists('payComposeOrderNo')) {
    /**
     * 按「当前配置」组合规则拼订单号。连接符（+/-/＋/－）只作分隔，不进订单号。
     * 变量映射（中文变量名与说明区英文别名都认）：
     *   剦缀码/front → 当前前缀；当前时间/下单时间/timeis → YmdHis；
     *   订单验证码/校验码/ordcode → 随机码（长度读「订单验证码长度」，不读文本里的"N位"）；
     *   玩家名字/用户名/username → 净化后的玩家名；未知变量忽略。
     *
     * @param string $tpl   「当前配置」原文，如 剦缀码+当前时间+4位订单验证码+玩家名字
     * @param int $codeLen  验证码长度
     * @param string $name  已净化的玩家名
     * @return array|null   [前段, 后段, 是否含玩家名]（订单号=前段.玩家名.后段）；无有效段返回 null
     */
    function payComposeOrderNo($tpl, $codeLen, $name) {
        $tokens = preg_split('/[+\-\x{FF0B}\x{FF0D}]/u', (string)$tpl);
        if (!is_array($tokens) || count($tokens) === 0) return null;
        $pre = '';         // 玩家名之前的段落
        $post = '';        // 玩家名之后的段落
        $withName = false; // 组合里含玩家名变量（payGuardLen 据此裁剪）
        $afterName = false; // 已越过玩家名位置
        $valid = false;
        foreach ($tokens as $t) {
            $t = trim($t);
            if ($t === '') continue;
            $tl = strtolower($t);
            if (strpos($t, '前缀') !== false || $tl === 'front') {
                $seg = payOrderPrefix();
            } elseif (strpos($t, '时间') !== false || strpos($t, '日期') !== false || $tl === 'timeis') {
                $seg = date('YmdHis');
            } elseif (strpos($t, '验证码') !== false || strpos($t, '校验码') !== false || $tl === 'ordcode') {
                $seg = payRandomCode($codeLen);
            } elseif (!$afterName && !$withName
                      && (strpos($t, '玩家') !== false || strpos($t, '用户') !== false || $tl === 'username')) {
                // 玩家名段：本身由 payGuardLen 统一拼装，这里只切换分段状态（重复的玩家名变量忽略）
                $withName = true;
                $afterName = true;
                $valid = true;
                continue;
            } else {
                continue; // 未知变量忽略
            }
            if ($afterName) $post .= $seg; else $pre .= $seg;
            $valid = true;
        }
        if (!$valid) return null;
        return array($pre, $post, $withName);
    }
}

if (!function_exists('payMakeFixedOrderNo')) {
    /**
     * 固定订单号格式分支：前缀码 + 订单号格式模板。
     *   模板 [] 去掉；
     *   - 纯数字（如 00000000）→ 按【模板位数】递增序列：00000001、00000002…
     *     （计数器持久化在 db/pay_order_seq.txt，flock 防撞单；当前无订单则从 1 开始）；
     *   - 非纯数字或为空 → 保底 6 位递增序列 000001、000002…。
     * @param array $cfg pay.md 配置区
     * @return string
     */
    function payMakeFixedOrderNo($cfg) {
        $p = payOrderPrefix();
        $tpl = isset($cfg['订单号格式']) ? (string)$cfg['订单号格式'] : '';
        $tpl = str_replace(array('[', ']', "\u{FF3B}", "\u{FF3D}", ' ', "\t"), '', $tpl);
        if ($tpl !== '' && ctype_digit($tpl)) {
            // ★ 纯数字模板：按模板位数递增序列（00000000 → CYZJ00000001, CYZJ00000002…）
            $no = payMakeSequentialOrderNo($tpl);
        } else {
            // 非纯数字模板（或空）→ 保底 6 位递增序列
            $no = payMakeSequentialOrderNo('000000');
        }
        $max = defined('PAY_ORDER_MAX_LEN') ? (int)PAY_ORDER_MAX_LEN : 64;
        if ($max > 0 && strlen($no) > $max) $no = substr($no, 0, $max);
        return $no;
    }
}

if (!function_exists('payMakeSequentialOrderNo')) {
    /**
     * 递增序列订单号：前缀 + 模板位数的序列（000001、000002…，默认 6 位）。
     * 计数器持久化在 db/pay_order_seq.txt（flock 排他锁读写改，防并发撞单）；
     * 计数器不可写时退化为「前缀+日期时间+3位随机」，保底不与历史单撞号。
     *
     * @param string $tpl 位数模板（纯数字，如 '00000000'），决定序列补零位数
     * @return string
     */
    function payMakeSequentialOrderNo($tpl = '000000') {
        $p = payOrderPrefix();
        $width = strlen(ctype_digit($tpl) ? $tpl : '000000');
        $dir = dirname(__DIR__) . '/db';
        if (!is_dir($dir)) @mkdir($dir, 0755, true);
        $file = $dir . '/pay_order_seq.txt';
        $n = 0;
        $fp = @fopen($file, 'c+');
        if ($fp !== false) {
            if (flock($fp, LOCK_EX)) {
                $raw = trim((string)stream_get_contents($fp));
                $n = (is_numeric($raw) && (int)$raw > 0) ? (int)$raw : 0;
                $n++;
                ftruncate($fp, 0);
                rewind($fp);
                fwrite($fp, (string)$n);
                fflush($fp);
                flock($fp, LOCK_UN);
            }
            fclose($fp);
        }
        if ($n <= 0) {
            return $p . date('ymdHis') . str_pad((string)mt_rand(0, 999), 3, '0', STR_PAD_LEFT);
        }
        return $p . str_pad((string)$n, $width, '0', STR_PAD_LEFT);
    }
}

if (!function_exists('payGuardLen')) {
    /**
     * 长度护栏：平台 out_trade_no 默认 varchar(64)。
     * 订单号 = $pre . 玩家名 . $post（$withName=false 时玩家名不参与）。
     * 超长时先裁玩家名段，其次整体裁尾（前缀在头部绝不被裁 —— 前缀一旦被截，
     * 补单器就再也认不出这单了）。
     *
     * @return string
     */
    function payGuardLen($pre, $post, $name, $withName) {
        $max = defined('PAY_ORDER_MAX_LEN') ? (int)PAY_ORDER_MAX_LEN : 64;
        if ($max <= 0) return $withName ? $pre . $name . $post : $pre . $post;
        if (!$withName || $name === '') {
            $no = $pre . $post;
            return strlen($no) > $max ? substr($no, 0, $max) : $no;
        }
        $fixed = strlen($pre) + strlen($post);
        $allow = $max - $fixed;
        if ($allow >= strlen($name)) {
            return $pre . $name . $post;
        }
        if ($allow > 0) {
            return $pre . substr($name, 0, $allow) . $post;
        }
        // 极端情况（前缀配得极长）：先退回不含玩家名的形态，仍超长则整体裁尾保前缀
        $no = $pre . $post;
        return strlen($no) > $max ? substr($no, 0, $max) : $no;
    }
}

if (!function_exists('payMakeOrderNo')) {
    /**
     * 生成本端订单号（2026-10-07 改读 pay.md 配置区）
     *
     * 分支：
     *   1) 「是否固定订单号格式」= true  → payMakeFixedOrderNo（模板纯数字0换随机 /
     *      非纯数字回退 6 位递增序列），不再读组合规则；
     *   2) = false（或 pay.md 缺失/解析失败）→ 按「当前配置」组合；组合规则缺失时
     *      回退旧版默认（前缀 + 年月日时分秒 + 4位随机校验码 + 玩家名，
     *      例：CYZJ20261006171418Q101youpaishidifu），行为与改造前完全一致。
     *
     * 玩家名只保留 [A-Za-z0-9_]（Minecraft 用户名规则），其余字符剔除；
     * 剔除后为空则整段省略，保证前缀/时间戳/校验码绝不被破坏。
     *
     * @param string $playerName 下单玩家名
     * @return string
     */
    function payMakeOrderNo($playerName = '') {
        $cfg = payMdOrderConfig();

        // 玩家名净化：只留字母数字下划线
        $name = preg_replace('/[^A-Za-z0-9_]/', '', (string)$playerName);
        if ($name === '') $name = '';

        // —— 分支 1：固定订单号格式 ——
        if (is_array($cfg) && !empty($cfg['是否固定订单号格式'])) {
            return payMakeFixedOrderNo($cfg);
        }

        // —— 分支 2：按「当前配置」组合规则 ——
        if (is_array($cfg) && isset($cfg['当前配置']) && trim((string)$cfg['当前配置']) !== '') {
            $codeLen = 4;
            if (isset($cfg['订单验证码长度'])) {
                $l = (int)$cfg['订单验证码长度'];
                if ($l >= 1 && $l <= 32) $codeLen = $l;
            }
            $r = payComposeOrderNo(trim((string)$cfg['当前配置']), $codeLen, $name);
            if ($r !== null && ($r[0] . $r[1]) !== '') {
                return payGuardLen($r[0], $r[1], $name, $r[2]);
            }
        }

        // —— 回退：旧版默认组合（pay.md 缺失/解析失败/无有效规则）——
        $p     = payOrderPrefix();
        $stamp = date('YmdHis');                       // 14位：20261006171418
        $code  = payRandomCode(4);                     // 4位校验码（去掉易混的 I/O/0/1）
        return payGuardLen($p . $stamp . $code, '', $name, true);
    }
}
