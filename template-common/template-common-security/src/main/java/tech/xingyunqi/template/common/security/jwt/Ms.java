package tech.xingyunqi.template.common.security.jwt;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** npm {@code ms} 2.x 的字符串解析：无单位为毫秒，年按 365.25 天。 */
public final class Ms {

    private static final Pattern PATTERN = Pattern.compile(
            "(-?(?:\\d+)?\\.?\\d+) *(milliseconds?|msecs?|ms|seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h"
                    + "|days?|d|weeks?|w|years?|yrs?|y)?",
            Pattern.CASE_INSENSITIVE);
    private static final double SECOND = 1_000;
    private static final double MINUTE = 60 * SECOND;
    private static final double HOUR = 60 * MINUTE;
    private static final double DAY = 24 * HOUR;

    private Ms() {}

    public static double parse(String value) {
        Matcher matcher = value.length() <= 100 ? PATTERN.matcher(value) : null;
        if (matcher == null || !matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration: " + value);
        }
        double number = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "ms" : matcher.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "milliseconds", "millisecond", "msecs", "msec", "ms" -> number;
            case "seconds", "second", "secs", "sec", "s" -> number * SECOND;
            case "minutes", "minute", "mins", "min", "m" -> number * MINUTE;
            case "hours", "hour", "hrs", "hr", "h" -> number * HOUR;
            case "days", "day", "d" -> number * DAY;
            case "weeks", "week", "w" -> number * 7 * DAY;
            default -> number * 365.25 * DAY;
        };
    }
}
