package Sdf1_login;

/**
 * Unicode 特殊字符常量表。
 *
 * <p>背景：项目里原先大量使用 Unicode 转义字面量（写成 U+XXXX 形式），
 * 读代码的人必须逐个心算码位才能看懂（例如 U+00A7 其实是 MC 颜色码 §）。
 * 尤其不可见字符（零宽空格、BOM、全角空格…）直接写成字面量后
 * 在编辑器里根本"看不见"，反而更难排查。</p>
 *
 * <p>因此本类把两类字符分开处理：</p>
 * <ul>
 *   <li><b>可见字符</b>（§、：、全角数字、汉字等）→ 直接在业务代码里写字面量，
 *       一眼可读；</li>
 *   <li><b>不可见字符</b>（零宽空格、BOM、全角空格、U+0001 分隔符等）→
 *       统一引用本类的具名常量，既可读又不会因"看不见"而误判。</li>
 * </ul>
 *
 * <p>注意：正则表达式字符串里的 Unicode 转义属于<b>正则层</b>转义
 * （Java 字符串里存的是字面反斜杠+u，由正则引擎在运行期解释），
 * 改写会改变正则语义。本项目已把正则层里<b>可见</b>的转义全部改写成
 * 真实字符或本类的具名常量；<b>不可见</b>的转义则通过本类常量参与拼接。</p>
 */
public final class UnicodeChars {

    private UnicodeChars() {
    }

    // ==================== 不可见字符（必须用具名常量） ====================

    /** U+0001 SOH —— SnManager 与 PHP 命令执行结果之间的分隔符 */
    public static final char SEPARATOR = '\u0001';

    /** U+00A0 不间断空格（NO-BREAK SPACE） */
    public static final char NBSP = '\u00A0';

    /** U+00AD 软连字符（SOFT HYPHEN） */
    public static final char SOFT_HYPHEN = '\u00AD';

    /** U+2000 EN QUAD —— Unicode 各种空格区起点 */
    public static final char SPACE_U2000 = '\u2000';

    /** U+200A HAIR SPACE —— Unicode 各种空格区终点 */
    public static final char SPACE_U200A = '\u200A';

    /** U+200B 零宽空格（ZERO WIDTH SPACE） */
    public static final char ZERO_WIDTH_SPACE = '\u200B';

    /** U+200C 零宽非连接符（ZERO WIDTH NON-JOINER） */
    public static final char ZERO_WIDTH_NON_JOINER = '\u200C';

    /** U+200D 零宽连接符（ZERO WIDTH JOINER） */
    public static final char ZERO_WIDTH_JOINER = '\u200D';

    /** U+200E 从左至右标记（LTR MARK） */
    public static final char LTR_MARK = '\u200E';

    /** U+200F 从右至左标记（RTL MARK） */
    public static final char RTL_MARK = '\u200F';

    /** U+2028 行分隔符（LINE SEPARATOR） */
    public static final char LINE_SEPARATOR = '\u2028';

    /** U+2029 段分隔符（PARAGRAPH SEPARATOR） */
    public static final char PARAGRAPH_SEPARATOR = '\u2029';

    /** U+2060 字连接符（WORD JOINER） */
    public static final char WORD_JOINER = '\u2060';

    /** U+3000 表意空格（IDEOGRAPHIC SPACE，即"全角空格"） */
    public static final char IDEOGRAPHIC_SPACE = '\u3000';

    /** U+FEFF 零宽不换行空格 / BOM（ZERO WIDTH NO-BREAK SPACE） */
    public static final char BOM = '\uFEFF';

    // ==================== 区间常量（不可见/边界判定用） ====================

    /** CJK 基本汉字区起点 U+4E00（一） */
    public static final char CJK_START = '一';

    /** CJK 基本汉字区终点 U+9FFF */
    public static final char CJK_END = '鿿';

    /** CJK 兼容汉字常用终点 U+9FA5（龥） */
    public static final char CJK_COMPAT_END = '龥';

    /** CJK 扩展 A 区起点 U+3400（㐀） */
    public static final char CJK_EXT_A_START = '㐀';

    /** CJK 扩展 A 区终点 U+4DBF（䶿） */
    public static final char CJK_EXT_A_END = '䶿';

    /** 全角 ASCII 起点 U+FF01（！） */
    public static final char FULLWIDTH_START = '！';

    /** 全角 ASCII 终点 U+FF5E（～） */
    public static final char FULLWIDTH_END = '～';

    /** 全角数字 0 U+FF10 */
    public static final char FULLWIDTH_ZERO = '０';

    /** 全角数字 9 U+FF19 */
    public static final char FULLWIDTH_NINE = '９';

    /** 全角大写 A U+FF21 */
    public static final char FULLWIDTH_A_UPPER = 'Ａ';

    /** 全角大写 Z U+FF3A */
    public static final char FULLWIDTH_Z_UPPER = 'Ｚ';

    /** 全角小写 a U+FF41 */
    public static final char FULLWIDTH_A_LOWER = 'ａ';

    /** 全角小写 z U+FF5A */
    public static final char FULLWIDTH_Z_LOWER = 'ｚ';

    /** 全角左括号 U+FF08 */
    public static final char FULLWIDTH_LPAREN = '（';

    /** 全角右括号 U+FF09 */
    public static final char FULLWIDTH_RPAREN = '）';

    /** 全角井号 U+FF03 */
    public static final char FULLWIDTH_HASH = '＃';

    /** 全角逗号 U+FF0C */
    public static final char FULLWIDTH_COMMA = '，';

    /** 全角分号 U+FF1B */
    public static final char FULLWIDTH_SEMICOLON = '；';

    /** 全角冒号 U+FF1A */
    public static final char FULLWIDTH_COLON = '：';

    /** 全角句点 U+FF0E */
    public static final char FULLWIDTH_STOP = '．';

    /** 全角波浪号 U+FF5E */
    public static final char FULLWIDTH_TILDE = '～';

    /** 半角片假名中点 U+FF65 */
    public static final char HALFWIDTH_KATAKANA_MIDDLE_DOT = '･';

    /** 小型句点 U+FE52 */
    public static final char SMALL_FULL_STOP = '﹒';

    /** 二点引导符 U+2025 */
    public static final char TWO_DOT_LEADER = '‥';

    /** 水平省略号 U+2026 */
    public static final char HORIZONTAL_ELLIPSIS = '…';

    /** 中点 U+00B7 */
    public static final char MIDDLE_DOT = '·';

    /** 项目符号 U+2022 */
    public static final char BULLET = '•';

    /** 表意句点 U+3002 */
    public static final char IDEOGRAPHIC_STOP = '。';

    /** 表意逗号 U+3001 */
    public static final char IDEOGRAPHIC_COMMA = '、';

    /** 表意数字零 U+3007（〇） */
    public static final char IDEOGRAPHIC_ZERO = '〇';

    /** 左黑 lenticular 方括号 U+3010（【） */
    public static final char LEFT_BLACK_LENTICULAR = '【';

    /** 右黑 lenticular 方括号 U+3011（】） */
    public static final char RIGHT_BLACK_LENTICULAR = '】';

    /** 左 tortoise shell 括号 U+3014（〔） */
    public static final char LEFT_TORTOISE_SHELL = '〔';

    /** 右 tortoise shell 括号 U+3015（〕） */
    public static final char RIGHT_TORTOISE_SHELL = '〕';

    /** 左直角引号 U+300C（「） */
    public static final char LEFT_CORNER_BRACKET = '「';

    /** 右直角引号 U+300D」（」） */
    public static final char RIGHT_CORNER_BRACKET = '」';

    /** 左白直角引号 U+300E（『） */
    public static final char LEFT_WHITE_CORNER_BRACKET = '『';

    /** 右白直角引号 U+300F（』） */
    public static final char RIGHT_WHITE_CORNER_BRACKET = '』';

    // ==================== 常用字符串常量 ====================

    /** MC 颜色码前缀 § */
    public static final String COLOR_SIGN = "§";

    /** 全角冒号 "：" */
    public static final String WIDE_COLON = "：";

    // ==================== 字符串版（用于拼接场景） ====================
    // 注意：char 常量参与字符串拼接时若两侧都是 char 会被当成数值加法，
    // 因此需要拼接的不可见字符一律用这里的 String 版常量。

    /** U+FEFF BOM 的字符串版 */
    public static final String BOM_TEXT = "\uFEFF";

    /** U+0001 分隔符的字符串版 */
    public static final String SEPARATOR_TEXT = "\u0001";

    /** U+3000 全角空格的字符串版 */
    public static final String IDEOGRAPHIC_SPACE_TEXT = "\u3000";

    /**
     * 零宽/不可见字符集合（聊天变形 URL 归一前剔除用）。
     * 含：零宽空格、零宽非连接、零宽连接、字连接符、BOM、软连字符。
     */
    public static final String INVISIBLE_CHARS = "\u200B\u200C\u200D\u2060\uFEFF\u00AD";

    /** U+200B 零宽空格的字符串版 */
    public static final String ZERO_WIDTH_SPACE_TEXT = "\u200B";

    /** U+200D 零宽连接符的字符串版 */
    public static final String ZERO_WIDTH_JOINER_TEXT = "\u200D";

    /** U+2060 字连接符的字符串版 */
    public static final String WORD_JOINER_TEXT = "\u2060";

    /** U+00A0 不间断空格的字符串版 */
    public static final String NBSP_TEXT = "\u00A0";

    /**
     * 需要剔除的不可见字符（正则字符类形式，净化玩家输入用）。
     * 含：零宽空格~零宽连接（区间）、字连接符、BOM、不间断空格、全角空格。
     * <p>这里用具名常量拼接，而不是转义写法——这样源码里能直接看出
     * 每个字符的用途，读代码的人不必去心算码位。</p>
     */
    public static final String INVISIBLE_STRIP_CLASS =
            "[" + ZERO_WIDTH_SPACE_TEXT + "-" + ZERO_WIDTH_JOINER_TEXT
                    + WORD_JOINER_TEXT + BOM_TEXT + NBSP_TEXT + IDEOGRAPHIC_SPACE_TEXT + "]";
}
