package tech.xingyunqi.template.common.oss;

import java.nio.file.Path;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 按 {@code FILE_DRIVER} 提供 {@link FileStorage}；配置不合法时启动失败。 */
@AutoConfiguration
@EnableConfigurationProperties(FileStorageProperties.class)
public class OssAutoConfiguration {

    @Bean
    FileStorage fileStorage(FileStorageProperties properties) {
        FileDriver driver = properties.fileDriver();
        if (driver == FileDriver.LOCAL) {
            return new LocalFileStorage(Path.of(properties.localRootOrDefault()));
        }
        return new S3FileStorage(driver, properties);
    }
}
