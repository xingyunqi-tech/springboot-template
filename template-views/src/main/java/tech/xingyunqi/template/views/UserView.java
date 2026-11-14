package tech.xingyunqi.template.views;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * nestjs {@code User} 经 ClassSerializerInterceptor 输出后的形状：键顺序、{@code @Expose} 分组导致的键缺省、关联的 {@code __entity}
 * 都按调用方给定的顺序原样输出。
 */
public final class UserView {

    private final Map<String, @Nullable Object> fields;

    public UserView(Map<String, @Nullable Object> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    @JsonValue
    public Map<String, @Nullable Object> fields() {
        return fields;
    }
}
