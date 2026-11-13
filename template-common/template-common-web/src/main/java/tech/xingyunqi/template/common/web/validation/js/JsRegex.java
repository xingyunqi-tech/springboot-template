package tech.xingyunqi.template.common.web.validation.js;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** JS 正则（非 unicode 模式）的等价物；匹配前把代理码元平移到 U+E000–U+E7FF，按 UTF-16 码元匹配。 */
public final class JsRegex {

    private static final String WS = "\\t\\n\\x{B}\\f\\r \\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}"
            + "\\x{205F}\\x{3000}\\x{FEFF}";
    private static final String LINE_TERMINATORS = "\\n\\r\\x{2028}\\x{2029}";
    private static final String ANY = "[\\x{0}-\\x{FFFF}]";
    private static final int SURROGATE_SHIFT = 0x800;
    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\d+(?:,\\d*)?}");

    private final String source;
    private final String flags;
    private final Pattern pattern;

    private JsRegex(String source, String flags, boolean unicodeCase) {
        this.source = source;
        this.flags = flags;
        int javaFlags = 0;
        if (flags.indexOf('i') >= 0) {
            javaFlags |= Pattern.CASE_INSENSITIVE;
            if (unicodeCase) {
                javaFlags |= Pattern.UNICODE_CASE;
            }
        }
        this.pattern = Pattern.compile(
                new Translator(source, flags.indexOf('s') >= 0, flags.indexOf('m') >= 0).translate(), javaFlags);
    }

    /** 忽略大小写时只折叠 ASCII 字母，与 validator.js 内置正则的实际效果一致。 */
    public static JsRegex of(String source, String flags) {
        return new JsRegex(source, flags, false);
    }

    public static JsRegex of(String source) {
        return of(source, "");
    }

    /** 忽略大小写时按 Unicode 折叠，用于 DTO 中声明的正则。 */
    public static JsRegex unicodeCase(String source, String flags) {
        return new JsRegex(source, flags, true);
    }

    public String source() {
        return source;
    }

    public String flags() {
        return flags;
    }

    @Override
    public String toString() {
        return "/" + source + "/" + flags;
    }

    public boolean test(String input) {
        return pattern.matcher(units(input)).find();
    }

    public @Nullable Match exec(String input) {
        Matcher matcher = pattern.matcher(units(input));
        return matcher.find() ? new Match(input, matcher) : null;
    }

    public String replaceAll(String input, String replacement) {
        Matcher matcher = pattern.matcher(units(input));
        StringBuilder out = new StringBuilder();
        int from = 0;
        int search = 0;
        while (search <= input.length() && matcher.find(search)) {
            out.append(input, from, matcher.start()).append(replacement);
            from = matcher.end();
            search = matcher.end() == matcher.start() ? matcher.end() + 1 : matcher.end();
        }
        return out.append(input, from, input.length()).toString();
    }

    static String units(String input) {
        int first = -1;
        for (int i = 0; i < input.length(); i++) {
            if (Character.isSurrogate(input.charAt(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return input;
        }
        char[] chars = input.toCharArray();
        for (int i = first; i < chars.length; i++) {
            chars[i] = shift(chars[i]);
        }
        return new String(chars);
    }

    private static char shift(char c) {
        return Character.isSurrogate(c) ? (char) (c + SURROGATE_SHIFT) : c;
    }

    /** 一次匹配；分组文本取自原始字符串。 */
    public static final class Match {
        private final String input;
        private final int[] bounds;

        private Match(String input, Matcher matcher) {
            this.input = input;
            this.bounds = new int[(matcher.groupCount() + 1) * 2];
            for (int g = 0; g <= matcher.groupCount(); g++) {
                bounds[g * 2] = matcher.start(g);
                bounds[g * 2 + 1] = matcher.end(g);
            }
        }

        public int start() {
            return bounds[0];
        }

        public int end() {
            return bounds[1];
        }

        public @Nullable String group(int index) {
            int start = bounds[index * 2];
            return start < 0 ? null : input.substring(start, bounds[index * 2 + 1]);
        }
    }

    private static final class Translator {
        private final String src;
        private final boolean dotAll;
        private final boolean multiline;
        private final int groups;
        private final boolean namedGroups;
        private final StringBuilder out = new StringBuilder();
        private boolean inClass;
        private boolean negatedClass;
        private int classContentStart;
        private int classOpenStart;

        Translator(String src, boolean dotAll, boolean multiline) {
            this.src = src;
            this.dotAll = dotAll;
            this.multiline = multiline;
            this.groups = countGroups(src);
            this.namedGroups = src.contains("(?<");
        }

        String translate() {
            int i = 0;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '\\') {
                    i = escape(i);
                } else if (inClass) {
                    classChar(c);
                    i++;
                } else {
                    i = atom(i, c);
                }
            }
            return out.toString();
        }

        private void classChar(char c) {
            if (c == ']') {
                if (out.length() == classContentStart) {
                    out.setLength(classOpenStart);
                    out.append(negatedClass ? ANY : "(?!)");
                } else {
                    out.append(']');
                }
                inClass = false;
            } else if (c == '[' || c == '&') {
                out.append('\\').append(c);
            } else if (Character.isSurrogate(c)) {
                literal(c);
            } else {
                out.append(c);
            }
        }

        private int atom(int i, char c) {
            switch (c) {
                case '[' -> {
                    inClass = true;
                    classOpenStart = out.length();
                    negatedClass = i + 1 < src.length() && src.charAt(i + 1) == '^';
                    out.append(negatedClass ? "[^" : "[");
                    classContentStart = out.length();
                    return i + (negatedClass ? 2 : 1);
                }
                case '.' -> out.append(dotAll ? ANY : "[^" + LINE_TERMINATORS + "]");
                case '$' -> out.append(multiline ? "(?=[" + LINE_TERMINATORS + "]|\\z)" : "\\z");
                case '^' -> out.append(multiline ? "(?:^|(?<=[" + LINE_TERMINATORS + "]))" : "^");
                case '{' -> {
                    Matcher quantifier = QUANTIFIER.matcher(src).region(i, src.length());
                    if (quantifier.lookingAt()) {
                        out.append(quantifier.group());
                        return quantifier.end();
                    }
                    out.append("\\{");
                }
                case '}', ']' -> out.append('\\').append(c);
                default -> {
                    if (Character.isSurrogate(c)) {
                        literal(c);
                    } else {
                        out.append(c);
                    }
                }
            }
            return i + 1;
        }

        private int escape(int i) {
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\\';
            int end = i + 2;
            switch (n) {
                case 'd', 'D', 'w', 'W' -> out.append('\\').append(n);
                case 's' -> out.append(inClass ? WS : "[" + WS + "]");
                case 'S' -> out.append("[^" + WS + "]");
                case 'b' -> out.append(inClass ? "\\x{8}" : "\\b");
                case 'B' -> {
                    if (inClass) {
                        literal(n);
                    } else {
                        out.append("\\B");
                    }
                }
                case 'n' -> literal('\n');
                case 'r' -> literal('\r');
                case 't' -> literal('\t');
                case 'f' -> literal('\f');
                case 'v' -> literal('\u000B');
                case 'c' -> {
                    if (end < src.length() && isAsciiLetter(src.charAt(end))) {
                        literal((char) (src.charAt(end) % 32));
                        return end + 1;
                    }
                    literal('\\');
                    literal('c');
                }
                case 'x' -> {
                    if (isHex(end, 2)) {
                        literal((char) Integer.parseInt(src.substring(end, end + 2), 16));
                        return end + 2;
                    }
                    literal(n);
                }
                case 'u' -> {
                    if (isHex(end, 4)) {
                        literal((char) Integer.parseInt(src.substring(end, end + 4), 16));
                        return end + 4;
                    }
                    literal(n);
                }
                case 'k' -> {
                    int close = src.indexOf('>', end);
                    if (namedGroups && !inClass && end < src.length() && src.charAt(end) == '<' && close > 0) {
                        out.append(src, i, close + 1);
                        return close + 1;
                    }
                    literal(n);
                }
                default -> {
                    if (n >= '1' && n <= '9' && !inClass) {
                        int digitsEnd = i + 1;
                        while (digitsEnd < src.length() && Character.isDigit(src.charAt(digitsEnd))) {
                            digitsEnd++;
                        }
                        String digits = src.substring(i + 1, digitsEnd);
                        if (digits.length() < 4 && Integer.parseInt(digits) <= groups) {
                            out.append("(?:\\").append(digits).append(')');
                            return digitsEnd;
                        }
                    }
                    return octalOrIdentity(i);
                }
            }
            return end;
        }

        private int octalOrIdentity(int i) {
            char n = src.charAt(i + 1);
            if (n >= '0' && n <= '7') {
                int width = n <= '3' ? 3 : 2;
                int j = i + 1;
                while (j < src.length() && j - (i + 1) < width && src.charAt(j) >= '0' && src.charAt(j) <= '7') {
                    j++;
                }
                literal((char) Integer.parseInt(src.substring(i + 1, j), 8));
                return j;
            }
            literal(n);
            return i + 2;
        }

        private boolean isHex(int start, int count) {
            if (start + count > src.length()) {
                return false;
            }
            for (int j = start; j < start + count; j++) {
                if (Character.digit(src.charAt(j), 16) < 0 || src.charAt(j) > 'f') {
                    return false;
                }
            }
            return true;
        }

        private void literal(char c) {
            out.append("\\x{").append(Integer.toHexString(shift(c))).append('}');
        }

        private static boolean isAsciiLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        private static int countGroups(String source) {
            int count = 0;
            boolean inClass = false;
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (inClass) {
                    inClass = c != ']';
                } else if (c == '[') {
                    inClass = true;
                } else if (c == '('
                        && (!source.startsWith("?", i + 1) || source.startsWith("?<", i + 1))
                        && !source.startsWith("?<=", i + 1)
                        && !source.startsWith("?<!", i + 1)) {
                    count++;
                }
            }
            return count;
        }
    }
}
