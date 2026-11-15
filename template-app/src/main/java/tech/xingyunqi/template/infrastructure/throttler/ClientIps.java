package tech.xingyunqi.template.infrastructure.throttler;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.regex.Pattern;

/** 与 {@code @nestjs/throttler} 的 {@code normalizeIp} 相同：IPv6 按 /64 子网归并，回环与 IPv4 映射地址特殊处理，非法字符串原样返回。 */
public final class ClientIps {

    static final int IPV6_SUBNET_PREFIX = 64;

    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]\\d|\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");
    private static final int HEXTETS = 8;

    private ClientIps() {}

    public static String normalize(String ip) {
        if (ip.isEmpty()) {
            return ip;
        }
        int zone = ip.indexOf('%');
        String bare = zone < 0 ? ip : ip.substring(0, zone);
        if (IPV4.matcher(bare).matches()) {
            return bare;
        }
        if (bare.indexOf(':') < 0) {
            return ip;
        }
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(bare);
        } catch (IllegalArgumentException e) {
            return ip;
        }
        if (address instanceof Inet4Address mapped) {
            return mapped.getHostAddress();
        }
        if (address.isLoopbackAddress()) {
            return "::1";
        }
        byte[] bytes = address.getAddress();
        for (int i = IPV6_SUBNET_PREFIX / 8; i < bytes.length; i++) {
            bytes[i] = 0;
        }
        return format(bytes) + "/" + IPV6_SUBNET_PREFIX;
    }

    /** RFC 5952：小写、去前导零，最长（并列取第一段）的两组及以上连续零压缩为 {@code ::}。 */
    static String format(byte[] bytes) {
        int[] hextets = new int[HEXTETS];
        for (int i = 0; i < HEXTETS; i++) {
            hextets[i] = ((bytes[2 * i] & 0xff) << 8) | (bytes[2 * i + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLength = 0;
        for (int i = 0; i < HEXTETS; ) {
            if (hextets[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < HEXTETS && hextets[i] == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        if (bestLength < 2) {
            bestStart = -1;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < HEXTETS; i++) {
            if (i == bestStart) {
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(hextets[i]));
        }
        return text.toString();
    }
}
