package tech.xingyunqi.template.common.web.validation.js;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** JS Date：只支持 ES 规范的 ISO 日期字符串与数字时间戳，无偏移的时间按 UTC 解释。 */
public final class JsDate implements JsValue {

    private static final Pattern ISO_DATE =
            Pattern.compile("(?<year>[+-]\\d{6}|\\d{4})(?:-(?<month>\\d{2})(?:-(?<day>\\d{2}))?)?"
                    + "(?:T(?<hour>\\d{2}):(?<minute>\\d{2})(?::(?<second>\\d{2})(?:\\.(?<fraction>\\d+))?)?"
                    + "(?<offset>Z|[+-]\\d{2}:\\d{2})?)?");
    private static final long MS_PER_DAY = 86_400_000L;
    private static final double MAX_TIME = 8.64e15;

    private final double time;

    private JsDate(double time) {
        this.time = time;
    }

    public static JsDate of(JsValue value) {
        if (value instanceof JsDate date) {
            return new JsDate(date.time);
        }
        if (value instanceof JsValue.Str str) {
            return new JsDate(parseIso(str.value()));
        }
        double number = Js.toNumber(value);
        return new JsDate(Math.abs(number) <= MAX_TIME ? truncate(number) : Double.NaN);
    }

    private static double truncate(double number) {
        return number < 0 ? Math.ceil(number) : Math.floor(number);
    }

    public double time() {
        return time;
    }

    public boolean valid() {
        return !Double.isNaN(time);
    }

    public @Nullable String toJson() {
        if (!valid()) {
            return null;
        }
        long t = (long) time;
        long days = Math.floorDiv(t, MS_PER_DAY);
        long millis = Math.floorMod(t, MS_PER_DAY);
        long z = days + 719_468;
        long era = Math.floorDiv(z, 146_097);
        long doe = z - era * 146_097;
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long day = doy - (153 * mp + 2) / 5 + 1;
        long month = mp < 10 ? mp + 3 : mp - 9;
        long year = yoe + era * 400 + (month <= 2 ? 1 : 0);
        long seconds = millis / 1000;
        long ms = millis % 1000;
        String yearText = year >= 0 && year <= 9999
                ? String.format("%04d", year)
                : (year > 0 ? "+" : "-") + String.format("%06d", Math.abs(year));
        return String.format(
                "%s-%02d-%02dT%02d:%02d:%02d.%03dZ",
                yearText, month, day, seconds / 3600, seconds / 60 % 60, seconds % 60, ms);
    }

    static long daysFromCivil(long year, long month, long day) {
        long y = year + Math.floorDiv(month - 1, 12L);
        long m = Math.floorMod(month - 1, 12L) + 1;
        y -= m <= 2 ? 1 : 0;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097 + doe - 719_468;
    }

    private static double parseIso(String text) {
        Matcher match = ISO_DATE.matcher(text);
        if (!match.matches() || "-000000".equals(match.group("year"))) {
            return Double.NaN;
        }
        long year = Long.parseLong(stripPlus(match.group("year")));
        int month = intGroup(match, "month", 1);
        int day = intGroup(match, "day", 1);
        int hour = intGroup(match, "hour", 0);
        int minute = intGroup(match, "minute", 0);
        int second = intGroup(match, "second", 0);
        String fraction = match.group("fraction");
        String fractionDigits = fraction == null ? "000" : (fraction + "00").substring(0, 3);
        int millis = Integer.parseInt(fractionDigits);
        if (month < 1 || month > 12 || day < 1 || day > 31 || minute >= 60 || second >= 60) {
            return Double.NaN;
        }
        if (hour > 24 || (hour == 24 && (minute != 0 || second != 0 || millis != 0))) {
            return Double.NaN;
        }
        long time = daysFromCivil(year, month, day) * MS_PER_DAY;
        time += ((hour * 60L + minute) * 60 + second) * 1000 + millis;
        String offset = match.group("offset");
        if (offset != null && !offset.equals("Z")) {
            int sign = offset.charAt(0) == '+' ? 1 : -1;
            int minutes = Integer.parseInt(offset.substring(1, 3)) * 60 + Integer.parseInt(offset.substring(4, 6));
            time -= (long) sign * minutes * 60_000L;
        }
        return Math.abs(time) <= MAX_TIME ? time : Double.NaN;
    }

    private static String stripPlus(String year) {
        return year.startsWith("+") ? year.substring(1) : year;
    }

    private static int intGroup(Matcher match, String name, int fallback) {
        String group = match.group(name);
        return group == null ? fallback : Integer.parseInt(group);
    }
}
