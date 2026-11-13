package tech.xingyunqi.template.common.json;

import java.math.BigDecimal;

/** ECMAScript {@code Number.prototype.toString()} 的十进制格式。 */
public final class JsNumbers {

    private JsNumbers() {}

    /** NaN 与 ±Infinity 返回 JS 文本；JSON 输出时由调用方转为 null。 */
    public static String toString(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value == 0) {
            return "0";
        }
        if (value < 0) {
            return "-" + toString(-value);
        }
        BigDecimal decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        String digits = decimal.unscaledValue().toString();
        int k = digits.length();
        int n = k - decimal.scale();
        if (k <= n && n <= 21) {
            return digits + "0".repeat(n - k);
        }
        if (0 < n && n <= 21) {
            return digits.substring(0, n) + "." + digits.substring(n);
        }
        if (-6 < n && n <= 0) {
            return "0." + "0".repeat(-n) + digits;
        }
        int exponent = n - 1;
        String sign = exponent < 0 ? "-" : "+";
        String mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return mantissa + "e" + sign + Math.abs(exponent);
    }

    public static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
