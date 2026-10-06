<?php
/**
 * 收银台订单前缀 / 订单号生成 —— 统一读配置的公共小工具（2026-10-06）
 *
 * 背景：平台 MySQL 的 pay_order 是多商户共用表，创单与三个补单脚本此前都把 'RE'
 *      前缀写死在各自代码里。生产服 / 测试服共用一个前缀就会互相捡单 —— 测试服
 *      补单器把生产服的已支付单补进测试库，反过来也一样（就是这次串数据的根因）。
 *
 * 现在前缀收口到 config.php 的 PAY_ORDER_PREFIX：
 *      pay.php            创单时用它拼订单号
 *      poller_online.php  补单时用它 LIKE 扫平台 + strncmp 兜底校验
 *      pay_poller.php     同上
 *      recharge_orders_api.php  同上
 *   ★ 创单端与补单端必须读同一份配置：创单用什么前缀，补单器就用什么前缀扫平台，
 *     两边一旦不一致就会「创出来的单补不回来」。这四个文件都要跟着改，只改一个会出事。
 *
 * ★ 本文件不依赖 core.php，任何脚本（含自包含的 poller）都能直接 require。
 *   config.php 由 security.php 加载；若仍缺常量（线上老版 config.php），一律回退
 *   'RE' 默认值，行为与改造前完全一致，保证向后兼容。
 *
 * ★ 债券换算（estimateBonds）不在本文件：金额→债券由后台「充值商店配置」
 *   (shop_configs.bond_reward) 决定，代码不干预比例。
 */

if (!function_exists('payOrderPrefix')) {
    /**
     * 订单号前缀（永不返回空串；未定义/为空一律回退 'RE'）
     * 只保留字母数字：它要拼进 SQL 的 LIKE '前缀%'，也与平台侧约定一致。
     */
    function payOrderPrefix() {
        $p = defined('PAY_ORDER_PREFIX') ? trim((string)PAY_ORDER_PREFIX) : '';
        if ($p === '') return 'RE';
        $p = preg_replace('/[^A-Za-z0-9]/', '', $p);
        return $p === '' ? 'RE' : $p;
    }
}

if (!function_exists('payOrderPrefixLen')) {
    /** 前缀长度（strncmp 用，避免写死 2） */
    function payOrderPrefixLen() {
        return strlen(payOrderPrefix());
    }
}

if (!function_exists('payOrderIsOurs')) {
    /**
     * 判断一个 out_trade_no 是否本端订单（补单器的兜底校验）
     * @param string $outNo
     * @return bool
     */
    function payOrderIsOurs($outNo) {
        $p = payOrderPrefix();
        return strncmp((string)$outNo, $p, strlen($p)) === 0;
    }
}

if (!function_exists('payOrderPrefixLike')) {
    /** SQL 片段：out_trade_no LIKE '前缀%'（前缀只含字母数字，无需转义） */
    function payOrderPrefixLike() {
        return payOrderPrefix() . '%';
    }
}

if (!function_exists('payMakeOrderNo')) {
    /**
     * 生成本端订单号：前缀 + 年月日时分秒 + 4位随机校验码 + 玩家名
     *   例：RE20261006171418Q101youpaishidifu
     *
     * 加玩家名是为了在共用的 pay_order 表里一眼看出这单属于谁，排查串服问题最快。
     * 玩家名只保留 [A-Za-z0-9_]（Minecraft 用户名规则），其余字符剔除；
     * 若剔除后为空则整段省略，保证订单号前缀/时间戳/校验码绝不被破坏 ——
     * 前缀一旦被截断，补单器就再也认不出这单了。
     *
     * @param string $playerName 下单玩家名
     * @return string
     */
    function payMakeOrderNo($playerName = '') {
        $p     = payOrderPrefix();
        $stamp = date('YmdHis');                       // 14位：20261006171418
        $code  = strtoupper(substr(str_shuffle('ABCDEFGHJKLMNPQRSTUVWXYZ23456789'), 0, 4)); // 4位校验码（去掉易混的 I/O/0/1）

        // 玩家名净化：只留字母数字下划线
        $name = preg_replace('/[^A-Za-z0-9_]/', '', (string)$playerName);
        if ($name === '') $name = '';

        $no = $p . $stamp . $code . $name;

        // 长度护栏：平台 out_trade_no 默认 varchar(64)。超长时优先裁短玩家名段，
        // 绝不裁前缀/时间戳/校验码。
        $max = defined('PAY_ORDER_MAX_LEN') ? (int)PAY_ORDER_MAX_LEN : 64;
        if ($max > 0 && strlen($no) > $max) {
            $fixed = strlen($p) + strlen($stamp) + strlen($code);   // 不可裁部分
            $allow = $max - $fixed;
            if ($allow <= 0) {
                // 极端情况（前缀配得极长）：宁可退回不含玩家名的最短形态
                $no = $p . $stamp . $code;
            } else {
                $no = $p . $stamp . $code . substr($name, 0, $allow);
            }
        }
        return $no;
    }
}
