package tech.xingyunqi.template.common.web.validation.js;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.json.JsNumbers;

/** validator.js 13.15.35 字符串校验函数；options 中值为 null 的键等同 JS 的 undefined。 */
public final class ValidatorJs {

    private static final JsRegex SPLIT_NAME_ADDRESS = JsRegex.of("^([^\\x00-\\x1F\\x7F-\\x9F\\cX]+)<", "i");
    private static final JsRegex EMAIL_USER_PART = JsRegex.of("^[a-z\\d!#\\$%&'\\*\\+\\-\\/=\\?\\^_`{\\|}~]+$", "i");
    private static final JsRegex GMAIL_USER_PART = JsRegex.of("^[a-z\\d]+$");
    private static final JsRegex QUOTED_EMAIL_USER = JsRegex.of(
            "^([\\s\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f\\x21\\x23-\\x5b\\x5d-\\x7e]|(\\\\[\\x01-\\x09\\x0b\\x0c\\x0d-\\x7f]))*$",
            "i");
    private static final JsRegex EMAIL_USER_UTF8_PART = JsRegex.of(
            "^[a-z\\d!#\\$%&'\\*\\+\\-\\/=\\?\\^_`{\\|}~\\u00A1-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]+$", "i");
    private static final JsRegex QUOTED_EMAIL_USER_UTF8 = JsRegex.of(
            "^([\\s\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f\\x21\\x23-\\x5b\\x5d-\\x7e\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]"
                    + "|(\\\\[\\x01-\\x09\\x0b\\x0c\\x0d-\\x7f\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]))*$",
            "i");
    private static final int DEFAULT_MAX_EMAIL_LENGTH = 254;
    private static final JsRegex DISPLAY_NAME_QUOTES = JsRegex.of("^\"(.+)\"$");
    private static final JsRegex DISPLAY_NAME_ILLEGAL = JsRegex.of("[\\.\";<>]");
    private static final JsRegex ANGLE_BRACKETS = JsRegex.of("(^<|>$)");
    private static final JsRegex DOTS = JsRegex.of("\\.");

    private static final JsRegex FQDN_TLD = JsRegex.of(
            "^([a-z\\u00A1-\\u00A8\\u00AA-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]{2,}|xn[a-z0-9-]{2,})$", "i");
    private static final JsRegex FQDN_WHITESPACE = JsRegex.of("\\s");
    private static final JsRegex FQDN_NUMERIC = JsRegex.of("^\\d+$");
    private static final JsRegex FQDN_PART = JsRegex.of("^[a-z_\\u00a1-\\uffff0-9-]+$", "i");
    private static final JsRegex FQDN_FULL_WIDTH = JsRegex.of("[\\uff01-\\uff5e]");
    private static final JsRegex FQDN_HYPHEN_EDGE = JsRegex.of("^-|-$");

    private static final String IPV4_SEGMENT = "(?:[0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])";
    private static final String IPV4_ADDRESS = "(" + IPV4_SEGMENT + "[.]){3}" + IPV4_SEGMENT;
    private static final JsRegex IPV4 = JsRegex.of("^" + IPV4_ADDRESS + "$");
    private static final String V6 = "(?:[0-9a-fA-F]{1,4})";
    private static final String V4 = IPV4_ADDRESS;
    private static final JsRegex IPV6 = JsRegex.of("^("
            + "(?:" + V6 + ":){7}(?:" + V6 + "|:)|"
            + "(?:" + V6 + ":){6}(?:" + V4 + "|:" + V6 + "|:)|"
            + "(?:" + V6 + ":){5}(?::" + V4 + "|(:" + V6 + "){1,2}|:)|"
            + "(?:" + V6 + ":){4}(?:(:" + V6 + "){0,1}:" + V4 + "|(:" + V6 + "){1,3}|:)|"
            + "(?:" + V6 + ":){3}(?:(:" + V6 + "){0,2}:" + V4 + "|(:" + V6 + "){1,4}|:)|"
            + "(?:" + V6 + ":){2}(?:(:" + V6 + "){0,3}:" + V4 + "|(:" + V6 + "){1,5}|:)|"
            + "(?:" + V6 + ":){1}(?:(:" + V6 + "){0,4}:" + V4 + "|(:" + V6 + "){1,6}|:)|"
            + "(?::((?::" + V6 + "){0,5}:" + V4 + "|(?::" + V6 + "){1,7}|:))"
            + ")(%[0-9a-zA-Z.]{1,})?$");

    private static final JsRegex WRAPPED_IPV6 = JsRegex.of("^\\[([^\\]]+)\\](?::([0-9]+))?$");
    private static final JsRegex URL_FORBIDDEN = JsRegex.of("[\\s<>]");
    private static final JsRegex URL_PROTOCOL = JsRegex.of("^([a-z][a-z0-9+\\-.]*):", "i");
    private static final JsRegex URL_VALID_AUTH = JsRegex.of("^[a-zA-Z0-9\\-_.%:]*$");
    private static final JsRegex URL_ENCODED_CONTENT = JsRegex.of("%[0-9a-fA-F]{2}");
    private static final JsRegex URL_LOOKS_LIKE_PORT = JsRegex.of("^[0-9]");
    private static final JsRegex URL_PORT = JsRegex.of("^[0-9]+$");

    private static final Map<String, JsRegex> UUID_PATTERNS = uuidPatterns();
    private static final Set<String> OBJECT_PROTOTYPE_KEYS = Set.of(
            "__proto__",
            "__defineGetter__",
            "__defineSetter__",
            "__lookupGetter__",
            "__lookupSetter__",
            "constructor",
            "hasOwnProperty",
            "isPrototypeOf",
            "propertyIsEnumerable",
            "toLocaleString",
            "toString",
            "valueOf");

    private static final String ISO8601_DATE = "^[\\+-]?\\d{4}(?!\\d{2}\\b)(?:(?<dash>-?)(?:(?:0[1-9]|1[0-2])"
            + "(?:\\k<dash>(?:[12]\\d|0[1-9]|3[01]))?|W(?:[0-4]\\d|5[0-3])(?:-?[1-7])?"
            + "|(?:00[1-9]|0[1-9]\\d|[12]\\d{2}|3(?:[0-5]\\d|6[1-6])))";
    private static final String ISO8601_FRACTION = "(?:[\\.,]\\d+(?!:))?";
    private static final String ISO8601_SECONDS_TAIL = "[0-5]\\d(?:[\\.,]\\d+)?)?";
    private static final String ISO8601_TIME = "(?:(?:[01]\\d|2[0-3])(?<sep>:?)[0-5]\\d" + ISO8601_FRACTION
            + "(?:\\k<sep>" + ISO8601_SECONDS_TAIL
            + "|(?:[01]\\d|2[0-3]|24:?00)" + ISO8601_FRACTION + "(?:" + ISO8601_SECONDS_TAIL
            + "|(?:" + ISO8601_SECONDS_TAIL + ")";
    private static final String ISO8601_ZONE = "(?:[zZ]|[\\+-](?:[01]\\d|2[0-3]):?(?:[0-5]\\d)?)?";
    private static final JsRegex ISO8601 =
            JsRegex.of(ISO8601_DATE + "(?:[T\\s]" + ISO8601_TIME + ISO8601_ZONE + ")?)?$");
    private static final JsRegex ISO8601_STRICT_SEPARATOR =
            JsRegex.of(ISO8601_DATE + "(?:[T]" + ISO8601_TIME + ISO8601_ZONE + ")?)?$");
    private static final JsRegex ORDINAL_DATE = JsRegex.of("^(\\d{4})-?(\\d{3})([ T]{1}\\.*|$)");
    private static final JsRegex DATE_PARTS = JsRegex.of("(\\d{4})-?(\\d{0,2})-?(\\d*)");
    private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private ValidatorJs() {}

    private static Map<String, JsRegex> uuidPatterns() {
        Map<String, JsRegex> patterns = new HashMap<>();
        for (int v = 1; v <= 8; v++) {
            patterns.put(
                    Integer.toString(v),
                    JsRegex.of("^[0-9A-F]{8}-[0-9A-F]{4}-" + v + "[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$", "i"));
        }
        patterns.put("nil", JsRegex.of("^00000000-0000-0000-0000-000000000000$", "i"));
        patterns.put("max", JsRegex.of("^ffffffff-ffff-ffff-ffff-ffffffffffff$", "i"));
        patterns.put("loose", JsRegex.of("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$", "i"));
        patterns.put(
                "all",
                JsRegex.of(
                        "^(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                                + "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$",
                        "i"));
        return Map.copyOf(patterns);
    }

    private static String assertString(@Nullable Object value) {
        if (value instanceof String s) {
            return s;
        }
        String type = value == null ? "null" : value.getClass().getSimpleName();
        throw new JsError("Expected a string but received a " + type);
    }

    static boolean truthy(@Nullable Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    private static double toDouble(@Nullable Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof String s) {
            return Js.stringToNumber(s);
        }
        return value == null ? 0 : Double.NaN;
    }

    private static String jsToString(Object value) {
        if (value instanceof Number n) {
            return JsNumbers.toString(n.doubleValue());
        }
        return String.valueOf(value);
    }

    private static Map<String, @Nullable Object> merge(@Nullable Object options, Map<String, Object> defaults) {
        Map<String, @Nullable Object> merged = new LinkedHashMap<>();
        if (options instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v != null) {
                    merged.put(String.valueOf(k), v);
                }
            });
        }
        defaults.forEach(merged::putIfAbsent);
        return merged;
    }

    private static List<?> list(@Nullable Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static boolean checkHost(String host, @Nullable Object matches) {
        for (Object match : list(matches)) {
            if (match instanceof String s && host.equals(s)) {
                return true;
            }
            if (match instanceof JsRegex regex && regex.test(host)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** {@code String.prototype.split}，保留所有空段。 */
    static List<String> split(String value, String separator) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        int index;
        while ((index = value.indexOf(separator, from)) >= 0) {
            parts.add(value.substring(from, index));
            from = index + separator.length();
        }
        parts.add(value.substring(from));
        return parts;
    }

    private static int utf8Length(String value) {
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                length += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new JsError("URI malformed");
            } else {
                length += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            }
        }
        return length;
    }

    public static boolean isByteLength(Object value, @Nullable Object options) {
        String s = assertString(value);
        Object min = null;
        Object max = null;
        if (options instanceof Map<?, ?> map) {
            Object rawMin = map.get("min");
            min = truthy(rawMin) ? rawMin : Integer.valueOf(0);
            max = map.get("max");
        }
        int length = utf8Length(s);
        if (min == null) {
            return false;
        }
        return length >= toDouble(min) && (max == null || length <= toDouble(max));
    }

    public static boolean isLength(Object value, @Nullable Object options) {
        String s = assertString(value);
        double min = 0;
        Object max = null;
        Map<?, ?> map = options instanceof Map<?, ?> m ? m : null;
        if (map != null) {
            Object rawMin = map.get("min");
            min = truthy(rawMin) ? toDouble(rawMin) : 0;
            max = map.get("max");
        }
        int presentationSequences = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (!isVariationSelector(s.charAt(i)) && isVariationSelector(s.charAt(i + 1))) {
                presentationSequences++;
                i++;
            }
        }
        int surrogatePairs = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (Character.isHighSurrogate(s.charAt(i)) && Character.isLowSurrogate(s.charAt(i + 1))) {
                surrogatePairs++;
                i++;
            }
        }
        int length = s.length() - presentationSequences - surrogatePairs;
        boolean inside = length >= min && (max == null || length <= toDouble(max));
        Object discrete = map == null ? null : map.get("discreteLengths");
        if (inside && discrete instanceof List<?> lengths) {
            return lengths.stream().anyMatch(d -> d instanceof Number n && n.doubleValue() == length);
        }
        return inside;
    }

    private static boolean isVariationSelector(char c) {
        return c == '️' || c == '︎';
    }

    private static final Map<String, Object> DEFAULT_FQDN_OPTIONS = Map.of(
            "require_tld", true,
            "allow_underscores", false,
            "allow_trailing_dot", false,
            "allow_numeric_tld", false,
            "allow_wildcard", false,
            "ignore_max_length", false);

    public static boolean isFQDN(Object value, @Nullable Object options) {
        String s = assertString(value);
        Map<String, @Nullable Object> opts = merge(options, DEFAULT_FQDN_OPTIONS);
        if (truthy(opts.get("allow_trailing_dot")) && s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (Boolean.TRUE.equals(opts.get("allow_wildcard")) && s.startsWith("*.")) {
            s = s.substring(2);
        }
        List<String> parts = split(s, ".");
        String tld = parts.get(parts.size() - 1);
        if (truthy(opts.get("require_tld"))) {
            if (parts.size() < 2) {
                return false;
            }
            if (!truthy(opts.get("allow_numeric_tld")) && !FQDN_TLD.test(tld)) {
                return false;
            }
            if (FQDN_WHITESPACE.test(tld)) {
                return false;
            }
        }
        if (!truthy(opts.get("allow_numeric_tld")) && FQDN_NUMERIC.test(tld)) {
            return false;
        }
        for (String part : parts) {
            if (part.length() > 63 && !truthy(opts.get("ignore_max_length"))) {
                return false;
            }
            if (!FQDN_PART.test(part) || FQDN_FULL_WIDTH.test(part) || FQDN_HYPHEN_EDGE.test(part)) {
                return false;
            }
            if (!truthy(opts.get("allow_underscores")) && part.contains("_")) {
                return false;
            }
        }
        return true;
    }

    /** {@code isIP}；options 可为 {@code {version: ...}} 或直接传版本号。 */
    public static boolean isIP(Object value, @Nullable Object options) {
        String s = assertString(value);
        Object raw = options instanceof Map<?, ?> map ? map.get("version") : options;
        if (raw == null || !truthy(raw)) {
            return isIP(s, Map.of("version", 4)) || isIP(s, Map.of("version", 6));
        }
        String version = jsToString(raw);
        if (version.equals("4")) {
            return IPV4.test(s);
        }
        if (version.equals("6")) {
            return IPV6.test(s);
        }
        return false;
    }

    private static final Map<String, Object> DEFAULT_EMAIL_OPTIONS = Map.of(
            "allow_display_name", false,
            "allow_underscores", false,
            "require_display_name", false,
            "allow_utf8_local_part", true,
            "require_tld", true,
            "blacklisted_chars", "",
            "ignore_max_length", false,
            "host_blacklist", List.of(),
            "host_whitelist", List.of());

    private static boolean validateDisplayName(String displayName) {
        JsRegex.Match quoted = DISPLAY_NAME_QUOTES.exec(displayName);
        String group = quoted == null ? null : quoted.group(1);
        String withoutQuotes = group == null ? displayName : group;
        if (Js.trim(withoutQuotes).isEmpty()) {
            return false;
        }
        if (DISPLAY_NAME_ILLEGAL.test(withoutQuotes)) {
            if (withoutQuotes.equals(displayName)) {
                return false;
            }
            if (count(withoutQuotes, "\"") != count(withoutQuotes, "\\\"")) {
                return false;
            }
        }
        return true;
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        int index;
        while ((index = text.indexOf(needle, from)) >= 0) {
            count++;
            from = index + needle.length();
        }
        return count;
    }

    public static boolean isEmail(Object value, @Nullable Object options) {
        String s = assertString(value);
        Map<String, @Nullable Object> opts = merge(options, DEFAULT_EMAIL_OPTIONS);

        if (truthy(opts.get("require_display_name")) || truthy(opts.get("allow_display_name"))) {
            JsRegex.Match displayEmail = SPLIT_NAME_ADDRESS.exec(s);
            String displayName = displayEmail == null ? null : displayEmail.group(1);
            if (displayName != null) {
                int at = s.indexOf(displayName);
                s = ANGLE_BRACKETS.replaceAll(s.substring(0, at) + s.substring(at + displayName.length()), "");
                if (displayName.endsWith(" ")) {
                    displayName = displayName.substring(0, displayName.length() - 1);
                }
                if (!validateDisplayName(displayName)) {
                    return false;
                }
            } else if (truthy(opts.get("require_display_name"))) {
                return false;
            }
        }

        if (!truthy(opts.get("ignore_max_length")) && s.length() > DEFAULT_MAX_EMAIL_LENGTH) {
            return false;
        }

        int lastAt = s.lastIndexOf('@');
        String user = lastAt < 0 ? "" : s.substring(0, lastAt);
        String domain = s.substring(lastAt + 1);
        String lowerDomain = lower(domain);

        if (!list(opts.get("host_blacklist")).isEmpty() && checkHost(lowerDomain, opts.get("host_blacklist"))) {
            return false;
        }
        if (!list(opts.get("host_whitelist")).isEmpty() && !checkHost(lowerDomain, opts.get("host_whitelist"))) {
            return false;
        }

        if (truthy(opts.get("domain_specific_validation"))
                && (lowerDomain.equals("gmail.com") || lowerDomain.equals("googlemail.com"))) {
            user = lower(user);
            String username = split(user, "+").get(0);
            if (!isByteLength(DOTS.replaceAll(username, ""), Map.of("min", 6, "max", 30))) {
                return false;
            }
            for (String part : split(username, ".")) {
                if (!GMAIL_USER_PART.test(part)) {
                    return false;
                }
            }
        }

        if (Boolean.FALSE.equals(opts.get("ignore_max_length"))
                && (!isByteLength(user, Map.of("max", 64)) || !isByteLength(domain, Map.of("max", 254)))) {
            return false;
        }

        Map<String, @Nullable Object> fqdnOptions = new HashMap<>();
        fqdnOptions.put("require_tld", opts.get("require_tld"));
        fqdnOptions.put("ignore_max_length", opts.get("ignore_max_length"));
        fqdnOptions.put("allow_underscores", opts.get("allow_underscores"));
        if (!isFQDN(domain, fqdnOptions)) {
            if (!truthy(opts.get("allow_ip_domain"))) {
                return false;
            }
            if (!isIP(domain, null)) {
                if (!domain.startsWith("[") || !domain.endsWith("]")) {
                    return false;
                }
                String noBracketDomain = domain.substring(1, domain.length() - 1);
                if (noBracketDomain.isEmpty() || !isIP(noBracketDomain, null)) {
                    return false;
                }
            }
        }

        Object blacklistedChars = opts.get("blacklisted_chars");
        if (truthy(blacklistedChars)
                && JsRegex.of("[" + blacklistedChars + "]+", "g").test(user)) {
            return false;
        }

        if (user.startsWith("\"") && user.endsWith("\"")) {
            user = user.length() < 2 ? "" : user.substring(1, user.length() - 1);
            JsRegex quoted = truthy(opts.get("allow_utf8_local_part")) ? QUOTED_EMAIL_USER_UTF8 : QUOTED_EMAIL_USER;
            return quoted.test(user);
        }
        JsRegex pattern = truthy(opts.get("allow_utf8_local_part")) ? EMAIL_USER_UTF8_PART : EMAIL_USER_PART;
        for (String part : split(user, ".")) {
            if (!pattern.test(part)) {
                return false;
            }
        }
        return true;
    }

    private static final Map<String, Object> DEFAULT_URL_OPTIONS = Map.ofEntries(
            Map.entry("protocols", List.of("http", "https", "ftp")),
            Map.entry("require_tld", true),
            Map.entry("require_protocol", false),
            Map.entry("require_host", true),
            Map.entry("require_port", false),
            Map.entry("require_valid_protocol", true),
            Map.entry("allow_underscores", false),
            Map.entry("allow_trailing_dot", false),
            Map.entry("allow_protocol_relative_urls", false),
            Map.entry("allow_fragments", true),
            Map.entry("allow_query_components", true),
            Map.entry("validate_length", true),
            Map.entry("max_allowed_length", 2084));

    private static double parsePort(String portStr) {
        int i = 0;
        while (i < portStr.length() - 1 && portStr.charAt(i) == '0') {
            i++;
        }
        String digits = portStr.substring(i);
        return digits.length() > 6 ? Double.POSITIVE_INFINITY : Integer.parseInt(digits);
    }

    public static boolean isURL(Object value, @Nullable Object options) {
        String url = assertString(value);
        if (url.isEmpty() || URL_FORBIDDEN.test(url)) {
            return false;
        }
        if (url.startsWith("mailto:")) {
            return false;
        }
        Map<String, @Nullable Object> opts = merge(options, DEFAULT_URL_OPTIONS);
        if (truthy(opts.get("validate_length")) && url.length() > toDouble(opts.get("max_allowed_length"))) {
            return false;
        }
        if (!truthy(opts.get("allow_fragments")) && url.contains("#")) {
            return false;
        }
        if (!truthy(opts.get("allow_query_components")) && (url.contains("?") || url.contains("&"))) {
            return false;
        }
        url = split(url, "#").get(0);
        url = split(url, "?").get(0);

        JsRegex.Match protocolMatch = URL_PROTOCOL.exec(url);
        boolean hadExplicitProtocol = false;
        if (protocolMatch != null) {
            int prefixLength = protocolMatch.end();
            String potentialProtocol = String.valueOf(protocolMatch.group(1));
            String afterColon = url.substring(prefixLength);
            boolean cleanUp;
            if (!afterColon.startsWith("//")) {
                int firstSlash = afterColon.indexOf('/');
                String beforeSlash = firstSlash == -1 ? afterColon : afterColon.substring(0, firstSlash);
                int atPosition = beforeSlash.indexOf('@');
                if (atPosition != -1) {
                    String beforeAt = beforeSlash.substring(0, atPosition);
                    boolean validAuth = URL_VALID_AUTH.test(beforeAt);
                    boolean encoded = URL_ENCODED_CONTENT.test(beforeAt);
                    if (validAuth && !encoded) {
                        if (truthy(opts.get("require_protocol"))) {
                            return false;
                        }
                        cleanUp = false;
                    } else {
                        cleanUp = true;
                    }
                } else if (URL_LOOKS_LIKE_PORT.test(afterColon)) {
                    if (truthy(opts.get("require_protocol"))) {
                        return false;
                    }
                    cleanUp = false;
                } else {
                    cleanUp = true;
                }
            } else {
                cleanUp = true;
            }
            if (cleanUp) {
                hadExplicitProtocol = true;
                String protocol = lower(potentialProtocol);
                if (truthy(opts.get("require_valid_protocol"))
                        && !list(opts.get("protocols")).contains(protocol)) {
                    return false;
                }
                url = url.substring(prefixLength);
            }
        } else if (truthy(opts.get("require_protocol"))) {
            return false;
        }

        if (url.startsWith("//")) {
            if (!hadExplicitProtocol && !truthy(opts.get("allow_protocol_relative_urls"))) {
                return false;
            }
            url = url.substring(2);
        }
        if (url.isEmpty()) {
            return false;
        }
        url = split(url, "/").get(0);
        if (url.isEmpty() && !truthy(opts.get("require_host"))) {
            return true;
        }

        List<String> split = new ArrayList<>(split(url, "@"));
        if (split.size() > 1) {
            if (truthy(opts.get("disallow_auth"))) {
                return false;
            }
            if (split.get(0).isEmpty()) {
                return false;
            }
            String auth = split.remove(0);
            List<String> authParts = split(auth, ":");
            if (auth.contains(":") && authParts.size() > 2) {
                return false;
            }
            String user = authParts.get(0);
            String password = authParts.size() > 1 ? authParts.get(1) : null;
            if (user.isEmpty() && "".equals(password)) {
                return false;
            }
        }
        String hostname = String.join("@", split);

        String portStr = null;
        String ipv6 = null;
        String host;
        JsRegex.Match ipv6Match = WRAPPED_IPV6.exec(hostname);
        if (ipv6Match != null) {
            host = "";
            ipv6 = ipv6Match.group(1);
            String port = ipv6Match.group(2);
            portStr = port == null || port.isEmpty() ? null : port;
        } else {
            List<String> hostParts = new ArrayList<>(split(hostname, ":"));
            host = hostParts.remove(0);
            if (!hostParts.isEmpty()) {
                portStr = String.join(":", hostParts);
            }
        }

        if (portStr != null && !portStr.isEmpty()) {
            if (!URL_PORT.test(portStr)) {
                return false;
            }
            double port = parsePort(portStr);
            if (port <= 0 || port > 65535) {
                return false;
            }
        } else if (truthy(opts.get("require_port"))) {
            return false;
        }

        if (truthy(opts.get("host_whitelist"))) {
            return checkHost(host, opts.get("host_whitelist"));
        }
        if (host.isEmpty() && !truthy(opts.get("require_host"))) {
            return true;
        }
        if (!isIP(host, null) && !isFQDN(host, opts) && (ipv6 == null || !isIP(ipv6, 6))) {
            return false;
        }
        String finalHost = !host.isEmpty() ? host : ipv6 != null ? ipv6 : "";
        return !(truthy(opts.get("host_blacklist")) && checkHost(finalHost, opts.get("host_blacklist")));
    }

    /** {@code isUUID}；version 为 Object.prototype 上的属性名时与 JS 一样抛错。 */
    public static boolean isUUID(Object value, @Nullable Object version) {
        String s = assertString(value);
        String key = version == null ? "all" : jsToString(version);
        if (OBJECT_PROTOTYPE_KEYS.contains(key)) {
            throw new JsError("uuid['" + key + "'].test is not a function");
        }
        JsRegex pattern = UUID_PATTERNS.get(key);
        return pattern != null && pattern.test(s);
    }

    private static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private static boolean localOffsetIsPositive(int year, int month, int day) {
        return ZoneId.systemDefault()
                        .getRules()
                        .getOffset(LocalDateTime.of(year, month, day, 0, 0))
                        .getTotalSeconds()
                > 0;
    }

    private static boolean jsDateMatches(int year, int month, int day) {
        if (month < 1 || month > 12) {
            return false;
        }
        int days = month == 2 && isLeapYear(year) ? 29 : DAYS_IN_MONTH[month - 1];
        if (day < 1 || day > days || year < 100) {
            return false;
        }
        return year >= 1000 || !localOffsetIsPositive(year, month, day);
    }

    private static boolean isValidDate(String s) {
        JsRegex.Match ordinal = ORDINAL_DATE.exec(s);
        if (ordinal != null) {
            int year = Integer.parseInt(String.valueOf(ordinal.group(1)));
            int day = Integer.parseInt(String.valueOf(ordinal.group(2)));
            return day <= (isLeapYear(year) ? 366 : 365);
        }
        JsRegex.Match match = DATE_PARTS.exec(s);
        if (match == null) {
            return true;
        }
        int year = Integer.parseInt(String.valueOf(match.group(1)));
        String monthText = String.valueOf(match.group(2));
        int month = monthText.isEmpty() ? 0 : Integer.parseInt(monthText);
        String dayDigits = String.valueOf(match.group(3)).replaceFirst("^0+", "");
        if (month != 0 && !dayDigits.isEmpty()) {
            return dayDigits.length() <= 2 && jsDateMatches(year, month, Integer.parseInt(dayDigits));
        }
        return true;
    }

    public static boolean isISO8601(Object value, @Nullable Object options) {
        String s = assertString(value);
        Map<?, ?> opts = options instanceof Map<?, ?> map ? map : Map.of();
        JsRegex pattern = truthy(opts.get("strictSeparator")) ? ISO8601_STRICT_SEPARATOR : ISO8601;
        boolean check = pattern.test(s);
        if (check && truthy(opts.get("strict"))) {
            return isValidDate(s);
        }
        return check;
    }
}
