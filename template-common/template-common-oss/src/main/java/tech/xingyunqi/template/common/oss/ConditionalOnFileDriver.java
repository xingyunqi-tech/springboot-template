package tech.xingyunqi.template.common.oss;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** {@link FileStorageProperties#fileDriver()} 等于 {@link #value()} 时匹配；{@code FILE_DRIVER} 非法时启动失败。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(OnFileDriverCondition.class)
public @interface ConditionalOnFileDriver {

    FileDriver value();
}
