package tech.xingyunqi.template.infrastructure.throttler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClientIpsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            emptyValue = "",
            value = {
                "203.0.113.7|203.0.113.7",
                "''|''",
                "not-an-ip|not-an-ip",
                "::1|::1",
                "0:0:0:0:0:0:0:1|::1",
                "::ffff:198.51.100.4|198.51.100.4",
                "2001:db8:abcd:12:1:2:3:4|2001:db8:abcd:12::/64",
                "2001:DB8::1%eth0|2001:db8::/64",
                "2001:db8:0:0:1::|2001:db8::/64"
            })
    void matchesNestjs(String ip, String expected) {
        assertThat(ClientIps.normalize(ip)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "0:1:0:0:2:0:0:0|0:1:0:0:2::",
                "1:0:0:2:0:0:3:4|1::2:0:0:3:4",
                "1:2:3:4:5:6:0:8|1:2:3:4:5:6:0:8",
                "0:0:0:0:0:0:0:0|::",
                "abcd:0:0:0:0:0:0:0|abcd::"
            })
    void formatsRfc5952(String hextets, String expected) {
        String[] parts = hextets.split(":", -1);
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            int value = Integer.parseInt(parts[i], 16);
            bytes[2 * i] = (byte) (value >> 8);
            bytes[2 * i + 1] = (byte) value;
        }
        assertThat(ClientIps.format(bytes)).isEqualTo(expected);
    }
}
