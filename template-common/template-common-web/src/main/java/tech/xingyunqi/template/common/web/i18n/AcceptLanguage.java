package tech.xingyunqi.template.common.web.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** nestjs-i18n {@code AcceptLanguageResolver}（accept-language-parser）的解析与匹配。 */
public final class AcceptLanguage {

    private static final Pattern FLOAT_PREFIX = Pattern.compile(
            "[\\t\\n\\x0B\\f\\r \\u00a0\\ufeff]*([+-]?(?:Infinity|\\d+\\.?\\d*(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?))");

    record Tag(
            String code, @Nullable String script, @Nullable String region, double quality) {}

    private AcceptLanguage() {}

    static Tag parseTag(String text, double quality) {
        String[] bits = text.split("-", -1);
        boolean hasScript = bits.length == 3;
        String region = null;
        if (hasScript) {
            region = bits[2];
        } else if (bits.length > 1 && !bits[1].isEmpty()) {
            region = bits[1];
        }
        return new Tag(bits[0], hasScript ? bits[1] : null, region, quality);
    }

    static double parseFloat(@Nullable String text) {
        if (text == null) {
            return Double.NaN;
        }
        Matcher matcher = FLOAT_PREFIX.matcher(text);
        if (!matcher.lookingAt()) {
            return Double.NaN;
        }
        String number = matcher.group(1);
        if (number.endsWith("Infinity")) {
            return number.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        return Double.parseDouble(number);
    }

    static List<Tag> parse(String header) {
        List<Tag> tags = new ArrayList<>();
        for (String part : header.split(",", -1)) {
            String item = part.strip();
            if (item.isEmpty()) {
                continue;
            }
            String[] bits = item.split(";", -1);
            double quality = 1.0;
            if (bits.length > 1 && !bits[1].isEmpty()) {
                String[] pieces = bits[1].split("=", -1);
                quality = parseFloat(pieces.length > 1 ? pieces[1] : null);
            }
            tags.add(parseTag(bits[0], quality));
        }
        for (int i = 1; i < tags.size(); i++) {
            Tag current = tags.get(i);
            int j = i - 1;
            while (j >= 0 && compare(tags.get(j), current) > 0) {
                tags.set(j + 1, tags.get(j));
                j--;
            }
            tags.set(j + 1, current);
        }
        return tags;
    }

    /** JS 比较函数 {@code b.quality - a.quality}，NaN 视为相等。 */
    private static int compare(Tag a, Tag b) {
        double diff = b.quality() - a.quality();
        if (Double.isNaN(diff) || diff == 0) {
            return 0;
        }
        return diff < 0 ? -1 : 1;
    }

    /** 按 q 值顺序在 {@code supported} 中挑选；{@code loose} 时忽略 script 与 region。 */
    public static @Nullable String pick(List<String> supported, @Nullable String header, boolean loose) {
        if (supported.isEmpty() || header == null || header.isEmpty()) {
            return null;
        }
        List<Tag> options = supported.stream().map(s -> parseTag(s, 1.0)).toList();
        for (Tag tag : parse(header)) {
            String code = lower(tag.code());
            String region = lower(tag.region());
            String script = lower(tag.script());
            for (int i = 0; i < options.size(); i++) {
                Tag option = options.get(i);
                if (code != null
                        && code.equals(lower(option.code()))
                        && (loose || script == null || script.isEmpty() || script.equals(lower(option.script())))
                        && (loose || region == null || region.isEmpty() || region.equals(lower(option.region())))) {
                    return supported.get(i);
                }
            }
        }
        return null;
    }

    private static @Nullable String lower(@Nullable String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
