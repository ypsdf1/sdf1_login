package Sdf1_login;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一时长解析器（供 /tempban 与 /chat mute 共用）
 *
 * parseToExpireMs(String) 返回「绝对到期时间戳(毫秒)」，调用方自行校验最小/最大区间。
 *
 * 支持的写法：
 *  1) 纯数字（Unix 秒时间戳）：   253402271999
 *  2) 中文日期（绝对）：          2026年7月9日
 *  3) ISO 日期（绝对）：          2025-07-09
 *  4) 相对时长（中文/英文单位）：  1秒 / 1s / 1年 / 1年2月3天 / 2天12小时
 *  5) 英文数字词（2026-10-04 新增，供 /cypay freeze 用）：
 *     one min / two hours / twenty days / twenty one minutes
 *
 * <p>注意：纯数字仍按「Unix 秒时间戳」处理（如 0000000000）；
 * 过期时间戳由调用方自行判定（/cypay freeze 视为永久冻结）。
 *
 * 解析规则优先级：纯数字 → 时间戳；含「年/月/日」且为完整日期格式 → 绝对日期；
 * 否则按「数字+单位」逐段累加为相对时长。
 */
public class DurationParser {

    private static final Map<String, Long> UNIT_MS = new HashMap<>();
    static {
        // 秒
        UNIT_MS.put("秒", 1000L);
        UNIT_MS.put("秒钟", 1000L);
        UNIT_MS.put("s", 1000L);
        UNIT_MS.put("sec", 1000L);
        // 分
        UNIT_MS.put("分", 60000L);
        UNIT_MS.put("分钟", 60000L);
        UNIT_MS.put("mins", 60000L);
        UNIT_MS.put("min", 60000L);
        UNIT_MS.put("m", 60000L);
        // 时
        UNIT_MS.put("时", 3600000L);
        UNIT_MS.put("小时", 3600000L);
        UNIT_MS.put("钟头", 3600000L);
        UNIT_MS.put("h", 3600000L);
        UNIT_MS.put("hr", 3600000L);
        // 天
        UNIT_MS.put("天", 86400000L);
        UNIT_MS.put("日", 86400000L);
        UNIT_MS.put("d", 86400000L);
        // 周
        UNIT_MS.put("周", 604800000L);
        UNIT_MS.put("星期", 604800000L);
        UNIT_MS.put("礼拜", 604800000L);
        UNIT_MS.put("w", 604800000L);
        // 月（按30天近似）
        UNIT_MS.put("个月", 2592000000L);
        UNIT_MS.put("月", 2592000000L);
        UNIT_MS.put("mo", 2592000000L);
        // 年（按365天近似）
        UNIT_MS.put("年", 31536000000L);
        UNIT_MS.put("y", 31536000000L);
    }

    /** 解析为绝对到期毫秒；无法解析抛出 IllegalArgumentException */
    public static long parseToExpireMs(String input)
            throws IllegalArgumentException {
        if (input == null || input.trim().isEmpty())
            throw new IllegalArgumentException("时长为空");
        // ★ 先把英文数字词换成阿拉伯数字（one min -> 1 min）
        String s = normalizeEnglishNumber(input.trim()).replace(" ", "");

        // 1) 纯数字 → Unix 秒时间戳
        if (s.matches("\\d+")) {
            long ts = Long.parseLong(s);
            return ts * 1000L;
        }

        // 2) 中文日期 YYYY年M月D日
        Matcher mc = Pattern.compile(
                "(\\d{4})年(\\d{1,2})月(\\d{1,2})日").matcher(s);
        if (mc.find()) {
            return buildDateMillis(Integer.parseInt(mc.group(1)),
                    Integer.parseInt(mc.group(2)),
                    Integer.parseInt(mc.group(3)));
        }

        // 3) ISO 日期 YYYY-MM-DD / YYYY/MM/DD
        Matcher mi = Pattern.compile(
                "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(s);
        if (mi.find()) {
            return buildDateMillis(Integer.parseInt(mi.group(1)),
                    Integer.parseInt(mi.group(2)),
                    Integer.parseInt(mi.group(3)));
        }

        // 4) 相对时长（可多段组合）
        long rel = parseRelative(s);
        if (rel >= 0) {
            return System.currentTimeMillis() + rel;
        }

        throw new IllegalArgumentException("无法解析时长: " + input);
    }

    /** 英文数字词 -> 阿拉伯数字（zero..ninety，支持 twenty one / twenty-one 连写） */
    private static String normalizeEnglishNumber(String in) {
        try {
            String[] words = {"zero", "one", "two", "three", "four",
                    "five", "six", "seven", "eight", "nine", "ten",
                    "eleven", "twelve", "thirteen", "fourteen",
                    "fifteen", "sixteen", "seventeen", "eighteen",
                    "nineteen", "twenty", "thirty", "forty", "fifty",
                    "sixty", "seventy", "eighty", "ninety"};
            StringBuilder alt = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) alt.append('|');
                alt.append(words[i]);
            }
            Pattern p = Pattern.compile(
                    "(?i)\\b(" + alt + ")(?:[\\s-]+(" + alt + "))?\\b");
            Matcher m = p.matcher(in);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int v = enWordValue(m.group(1));
                if (m.group(2) != null) v += enWordValue(m.group(2));
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        String.valueOf(v)));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            return in;   // 归一化失败就原样返回
        }
    }

    private static int enWordValue(String w) {
        switch (w.toLowerCase(java.util.Locale.ROOT)) {
            case "zero": return 0;
            case "one": return 1;
            case "two": return 2;
            case "three": return 3;
            case "four": return 4;
            case "five": return 5;
            case "six": return 6;
            case "seven": return 7;
            case "eight": return 8;
            case "nine": return 9;
            case "ten": return 10;
            case "eleven": return 11;
            case "twelve": return 12;
            case "thirteen": return 13;
            case "fourteen": return 14;
            case "fifteen": return 15;
            case "sixteen": return 16;
            case "seventeen": return 17;
            case "eighteen": return 18;
            case "nineteen": return 19;
            case "twenty": return 20;
            case "thirty": return 30;
            case "forty": return 40;
            case "fifty": return 50;
            case "sixty": return 60;
            case "seventy": return 70;
            case "eighty": return 80;
            case "ninety": return 90;
            default: return 0;
        }
    }

    private static long parseRelative(String s) {
        // 按「数字 + 单位」逐段匹配并累加
        StringBuilder units = new StringBuilder();
        for (String u : UNIT_MS.keySet()) {
            if (units.length() > 0) units.append("|");
            // 转义，避免正则特殊字符
            units.append(Pattern.quote(u));
        }
        Pattern p = Pattern.compile(
                "([0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖]+)(" + units + ")");
        Matcher m = p.matcher(s);
        long total = 0;
        int found = 0;
        while (m.find()) {
            found++;
            long num = parseNumber(m.group(1));
            Long u = UNIT_MS.get(m.group(2));
            if (u == null) continue;
            total += num * u;
        }
        return found > 0 ? total : -1;
    }

    private static long buildDateMillis(int y, int mo, int d) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 解析数字（阿拉伯数字优先，其次中文数字 零-九/十百千万/两/壹贰叁…） */
    private static long parseNumber(String s) {
        if (s.matches("\\d+")) return Long.parseLong(s);

        Map<Character, Long> cn = new HashMap<>();
        cn.put('零', 0L); cn.put('〇', 0L);
        cn.put('一', 1L); cn.put('壹', 1L);
        cn.put('二', 2L); cn.put('贰', 2L); cn.put('两', 2L);
        cn.put('三', 3L); cn.put('叁', 3L);
        cn.put('四', 4L); cn.put('肆', 4L);
        cn.put('五', 5L); cn.put('伍', 5L);
        cn.put('六', 6L); cn.put('陆', 6L);
        cn.put('七', 7L); cn.put('柒', 7L);
        cn.put('八', 8L); cn.put('捌', 8L);
        cn.put('九', 9L); cn.put('玖', 9L);

        long section = 0, num = 0;
        boolean hasNum = false;
        for (char ch : s.toCharArray()) {
            if (cn.containsKey(ch)) {
                num = cn.get(ch);
                hasNum = true;
            } else if (ch == '十') {
                if (!hasNum) num = 1;
                section += num * 10; hasNum = false; num = 0;
            } else if (ch == '百') {
                if (!hasNum) num = 1;
                section += num * 100; hasNum = false; num = 0;
            } else if (ch == '千') {
                if (!hasNum) num = 1;
                section += num * 1000; hasNum = false; num = 0;
            } else if (ch == '万') {
                if (!hasNum) num = 1;
                section = (section + num) * 10000; hasNum = false; num = 0;
            } else {
                num = 0;
            }
        }
        return section + (hasNum ? num : 0);
    }

    /** 格式化绝对到期时间戳为「yyyy年M月d日 HH:mm」 */
    public static String formatExpire(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format("%d年%d月%d日 %02d:%02d",
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE));
    }
}
