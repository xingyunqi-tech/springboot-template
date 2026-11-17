package tech.xingyunqi.template.integrations.sms;

import org.jspecify.annotations.Nullable;

/** nestjs {@code maskPhone}：保留前 3 位与后 4 位。 */
public final class PhoneMask {

    private PhoneMask() {}

    public static String mask(@Nullable String phone) {
        if (phone == null || phone.isEmpty()) {
            return "[invalid]";
        }
        if (phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
