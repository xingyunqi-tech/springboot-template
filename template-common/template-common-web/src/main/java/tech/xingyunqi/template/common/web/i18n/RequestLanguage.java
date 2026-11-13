package tech.xingyunqi.template.common.web.i18n;

import org.jspecify.annotations.Nullable;

/** 当前请求解析出的语言，由请求上下文过滤器设置。 */
public final class RequestLanguage {

    private static final ThreadLocal<@Nullable String> CURRENT = new ThreadLocal<>();

    private RequestLanguage() {}

    public static @Nullable String current() {
        return CURRENT.get();
    }

    public static void set(@Nullable String language) {
        if (language == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(language);
        }
    }
}
