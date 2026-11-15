package tech.xingyunqi.template.infrastructure.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HealthPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HealthProperties.class)
    static class Config {}

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("HEALTH_MEMORY_HEAP_THRESHOLD_MB 不大于 0 时启动失败")
    void rejectsNonPositive(String value) {
        runner.withPropertyValues("app.health.memory-heap-threshold-mb=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"300", "500"})
    @DisplayName("阈值按 MB 换算为字节")
    void convertsToBytes(String value) {
        runner.withPropertyValues("app.health.memory-heap-threshold-mb=" + value)
                .run(context -> assertThat(
                                context.getBean(HealthProperties.class).memoryHeapThresholdBytes())
                        .isEqualTo(Long.parseLong(value) * 1024 * 1024));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 314_572_800})
    @DisplayName("已用堆不超过阈值为 up，超过为 down 并带 terminus 文本")
    void memoryHeap(long used) {
        long threshold = 300L * 1024 * 1024;
        assertThat(HealthIndicators.memoryHeap("memory_heap", used, threshold).data())
                .containsExactly(java.util.Map.entry("status", "up"));
        assertThat(HealthIndicators.memoryHeap("memory_heap", threshold + 1, threshold)
                        .data())
                .containsExactly(
                        java.util.Map.entry("message", "Used heap exceeded the set threshold"),
                        java.util.Map.entry("status", "down"));
    }
}
