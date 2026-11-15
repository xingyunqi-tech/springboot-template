package tech.xingyunqi.template.common.oss;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** {@link ConditionalOnFileDriver} 的判定。 */
class OnFileDriverCondition extends SpringBootCondition {

    static final String PROPERTY = "app.file.driver";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        FileDriver expected =
                metadata.getAnnotations().get(ConditionalOnFileDriver.class).getEnum("value", FileDriver.class);
        FileDriver actual =
                FileStorageProperties.fileDriver(context.getEnvironment().getProperty(PROPERTY));
        String message = "FILE_DRIVER is " + actual.value();
        return actual == expected ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
    }
}
