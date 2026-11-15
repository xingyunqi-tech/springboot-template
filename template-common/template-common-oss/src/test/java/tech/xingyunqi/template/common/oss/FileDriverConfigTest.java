package tech.xingyunqi.template.common.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class FileDriverConfigTest {

    @Configuration(proxyBeanMethods = false)
    static class Controllers {

        @Bean
        @ConditionalOnFileDriver(FileDriver.LOCAL)
        String local() {
            return "local";
        }

        @Bean
        @ConditionalOnFileDriver(FileDriver.S3)
        String s3() {
            return "s3";
        }

        @Bean
        @ConditionalOnFileDriver(FileDriver.S3_PRESIGNED)
        String s3Presigned() {
            return "s3-presigned";
        }
    }

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Controllers.class);

    @Test
    @DisplayName("FILE_DRIVER 未设置时为 local")
    void unsetIsLocal() {
        assertThat(FileStorageProperties.fileDriver(null)).isEqualTo(FileDriver.LOCAL);
        runner.run(context -> assertThat(context.getBeansOfType(String.class)).containsOnlyKeys("local"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "s3", "s3-presigned"})
    @DisplayName("合法取值只注册对应驱动的 bean")
    void registersMatchingDriverOnly(String driver) {
        assertThat(FileStorageProperties.fileDriver(driver).value()).isEqualTo(driver);
        runner.withPropertyValues("app.file.driver=" + driver)
                .run(context -> assertThat(context.getBeansOfType(String.class).values())
                        .containsExactly(driver));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "LOCAL", "ftp"})
    @DisplayName("空串与非法取值启动失败")
    void rejectsEmptyAndInvalidValues(String driver) {
        assertThatThrownBy(() -> FileStorageProperties.fileDriver(driver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("FILE_DRIVER must be one of local, s3, s3-presigned");
        runner.withPropertyValues("app.file.driver=" + driver)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageStartingWith("FILE_DRIVER must be one of local, s3, s3-presigned"));
    }
}
