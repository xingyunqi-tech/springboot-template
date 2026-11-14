package tech.xingyunqi.template.core.users.domain;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** 请求字段的三种状态：未提供（undefined）、显式 {@code null}、有值。 */
public final class Patch<T> {

    private static final Patch<?> ABSENT = new Patch<>(false, null);

    private final boolean present;
    private final @Nullable T value;

    private Patch(boolean present, @Nullable T value) {
        this.present = present;
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    public static <T> Patch<T> absent() {
        return (Patch<T>) ABSENT;
    }

    public static <T> Patch<T> of(@Nullable T value) {
        return new Patch<>(true, value);
    }

    public boolean present() {
        return present;
    }

    /** 已提供且非 null。 */
    public boolean given() {
        return present && value != null;
    }

    public @Nullable T value() {
        return value;
    }

    public T required() {
        if (value == null) {
            throw new IllegalStateException("patch has no value");
        }
        return value;
    }

    public <R> Patch<R> map(Function<T, R> mapper) {
        if (!present) {
            return absent();
        }
        return value == null ? of(null) : of(mapper.apply(value));
    }
}
