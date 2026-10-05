<?php
/**
 * debug 日志保留策略（2026-10-05）
 *
 * 规则（两条同时满足才算干净，达成任一条超标即触发清理）：
 *   1. 只保留最近 3 天的日志行（按行首 [Y-m-d H:i:s] 时间戳判断）；
 *   2. 文件总大小 ≤ 5MB。
 *   清理时大小目标压到 4.5MB 水位线（90%），留 0.5MB 缓冲——否则压到 5MB 上限后
 *   下一条日志写入即再次超标，会造成「每次写入都全量重建」的 I/O 放大。
 *
 * 设计要点：
 *   - 自包含：不依赖 core.php / config.php（captcha_guard、poller 等独立入口都能用）；
 *   - 廉价预检：每次写入后只做 1 次 stat + 读前几行找最旧时间戳，命中才做全量清理，
 *     绝大多数调用在预检处直接返回，开销微秒级；
 *   - append-only 假设：日志按时间追加，文件开头即最旧行；
 *   - 并发安全：清理期间 flock 日志文件排他（写入方 file_put_contents(LOCK_EX) 会短暂
 *     等待），锁内先复查，避免多个请求排队重复重建；
 *   - 流式处理：逐行读写、临时文件中转，60MB+ 大文件也不会撑爆 memory_limit；
 *   - 无时间戳前缀的行（多行消息的续行）跟随上一行去留，避免留下孤儿行；
 *   - 临时文件放在同目录（db/ 已被 nginx 整目录 404 屏蔽，外网不可见）。
 *
 * 接入点：core.php:debugLog / orders.php 内联 debugLog / captcha_guard.php:cgLog
 *        / api/poller_online.php:debugLog（api/pay_poller.php 为无引用模板，不接入）。
 */

if (!function_exists('logMaintain')) {

/** 从行首解析 [Y-m-d H:i:s] → unix 时间戳；解析失败返回 0 */
function logMaintainLineTs($line) {
    if (preg_match('/^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]/', $line, $m)) {
        $ts = strtotime($m[1]);
        return ($ts === false) ? 0 : $ts;
    }
    return 0;
}

/**
 * 读取文件最旧的可解析时间戳（最多扫前 maxLines 行找第一个带时间戳的行）。
 * 找不到返回 0（调用方视为「无法判定年龄」，不触发超龄清理）。
 * @param resource $fh 已打开的文件句柄（会被 rewind）
 */
function logMaintainOldestTs($fh, $maxLines = 10) {
    if (rewind($fh) === false) return 0;
    for ($i = 0; $i < $maxLines; $i++) {
        $line = fgets($fh);
        if ($line === false) break;
        $ts = logMaintainLineTs($line);
        if ($ts > 0) return $ts;
    }
    return 0;
}

/**
 * 重建日志：过滤掉 cutoff 之前的行；若剩余仍 > maxBytes，再从最旧端按行丢弃到 ≤ maxBytes。
 * 调用方必须已持有 $fh 的排他锁。
 * @param resource $fh   日志文件句柄（读写）
 * @param string   $logFile 日志路径（临时文件建在同目录）
 * @return bool 成功 true
 */
function logMaintainRebuild($fh, $logFile, $maxBytes, $cutoff) {
    $tmp = $logFile . '.tmp';

    // ===== 第一遍：读原文件，过滤超龄行，写入临时文件 =====
    $out = @fopen($tmp, 'wb');
    if (!$out) return false;
    if (rewind($fh) === false) { fclose($out); @unlink($tmp); return false; }

    $keepPrev = true;   // 无时间戳的续行跟随上一行；首行无时间戳时保守保留
    $kept = 0;
    while (($line = fgets($fh)) !== false) {
        $ts = logMaintainLineTs($line);
        if ($ts > 0) {
            $keepPrev = ($ts >= $cutoff);
        }
        if ($keepPrev) {
            fwrite($out, $line);
            $kept += strlen($line);
        }
    }
    fflush($out);
    fclose($out);
    clearstatcache(true, $tmp);

    // ===== 第二步：剩余仍超水位线 → 从最旧端按整行丢弃到 ≤ 90% maxBytes =====
    // 水位线（4.5MB）留 0.5MB 缓冲：若压到 5MB 上限，下一条日志写入即再次超标，
    // 会导致「每次写入都全量重建」的 I/O 放大；留缓冲后常态每 ~2500 条日志才重建一次。
    $target = (int) ($maxBytes * 9 / 10);
    $skipBytes = ($kept > $target) ? ($kept - $target) : 0;

    $in = @fopen($tmp, 'rb');
    if (!$in) { @unlink($tmp); return false; }
    if ($skipBytes > 0) {
        $acc = 0;
        // 逐行累计丢弃；按整行丢，最终大小 kept-acc 必 ≤ target
        while ($acc < $skipBytes && ($line = fgets($in)) !== false) {
            $acc += strlen($line);
        }
    }

    // ===== 写回日志本体（仍持锁，写入方在锁外等待，不会丢）=====
    if (ftruncate($fh, 0) === false) { fclose($in); @unlink($tmp); return false; }
    rewind($fh);
    while (!feof($in)) {
        $chunk = fread($in, 1048576);
        if ($chunk === false) break;
        if ($chunk !== '') fwrite($fh, $chunk);
    }
    fflush($fh);
    fclose($in);
    @unlink($tmp);
    return true;
}

/**
 * 入口：日志写入后调用。任一条件超标（>5MB 或 含 3 天前的行）即清理，
 * 清理到两个条件同时满足为止（最多 3 轮，正常第 1 轮后复查即通过）。
 *
 * @param string $logFile 日志文件绝对路径
 * @param int    $maxBytes 大小上限（默认 5MB）
 * @param int    $keepDays 保留天数（默认 3 天）
 * @return bool true=干净（无需清理或已清理完成）；false=清理失败
 */
function logMaintain($logFile, $maxBytes = 5242880, $keepDays = 3) {
    if (!is_string($logFile) || $logFile === '') return true;
    clearstatcache(true, $logFile);
    if (!is_file($logFile)) return true;
    $size = @filesize($logFile);
    if ($size === false || $size <= 0) return true;

    $cutoff = time() - $keepDays * 86400;

    // ---- 廉价预检：两条都没超标就直接返回（绝大多数调用在这里结束）----
    $need = ($size > $maxBytes);
    if (!$need) {
        $fh = @fopen($logFile, 'rb');
        if ($fh) {
            $oldest = logMaintainOldestTs($fh);
            fclose($fh);
            $need = ($oldest > 0 && $oldest < $cutoff);
        }
    }
    if (!$need) return true;

    // ---- 清理：排他锁内执行（写入方 file_put_contents(LOCK_EX) 短暂等待）----
    $fh = @fopen($logFile, 'c+');
    if (!$fh) return false;
    if (!flock($fh, LOCK_EX)) { fclose($fh); return false; }

    $ok = false;
    for ($round = 0; $round < 3; $round++) {
        // 锁内复查：可能别的进程刚清理完
        $stat = fstat($fh);
        $size = $stat ? (int) $stat['size'] : 0;
        $oldest = logMaintainOldestTs($fh);
        if ($size <= $maxBytes && !($oldest > 0 && $oldest < $cutoff)) {
            $ok = true;
            break;
        }
        if (!logMaintainRebuild($fh, $logFile, $maxBytes, $cutoff)) {
            break;  // 重建失败（如目录不可写）→ 解锁退出，下次写入重试
        }
    }

    flock($fh, LOCK_UN);
    fclose($fh);
    clearstatcache(true, $logFile);
    return $ok;
}

} // end if (!function_exists('logMaintain'))
