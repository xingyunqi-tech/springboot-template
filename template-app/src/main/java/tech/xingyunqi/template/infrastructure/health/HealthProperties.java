package tech.xingyunqi.template.infrastructure.health;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code HEALTH_MEMORY_HEAP_THRESHOLD_MB}：GC 后的已用堆超过该值时 {@code memory_heap} 为 down（默认 300，与 nestjs 相同）。 */
@Validated
@ConfigurationProperties("app.health")
public record HealthProperties(@Positive long memoryHeapThresholdMb) {

    public long memoryHeapThresholdBytes() {
        return memoryHeapThresholdMb * 1024 * 1024;
    }
}
