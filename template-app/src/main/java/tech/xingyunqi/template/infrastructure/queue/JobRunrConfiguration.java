package tech.xingyunqi.template.infrastructure.queue;

import java.util.List;
import javax.sql.DataSource;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.BackgroundJobServerConfiguration;
import org.jobrunr.server.JobActivator;
import org.jobrunr.spring.autoconfigure.JobRunrProperties;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tech.xingyunqi.template.cli.AppRole;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;

/** JobRunr 存储（独立 schema）与 background server（重试与指标由 {@link QueueJobFilter} 负责）。 */
@Configuration(proxyBeanMethods = false)
class JobRunrConfiguration {

    @Bean(name = "storageProvider", destroyMethod = "close")
    @DependsOnDatabaseInitialization
    StorageProvider storageProvider(DataSource dataSource, JobMapper jobMapper, JobRunrProperties properties) {
        StorageProvider storage =
                JobStorage.open(dataSource, properties.getDatabase().isSkipCreate());
        storage.setJobMapper(jobMapper);
        return storage;
    }

    @Bean
    QueueJobFilter queueJobFilter(AppMetrics metrics) {
        return new QueueJobFilter(metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jobrunr.background-job-server", name = "enabled", havingValue = "true")
    BackgroundJobServer backgroundJobServer(
            StorageProvider storageProvider,
            JsonMapper jobRunrJsonMapper,
            JobActivator jobActivator,
            BackgroundJobServerConfiguration configuration,
            QueueJobFilter filter,
            Environment environment) {
        checkInProcessAllowed(environment);
        BackgroundJobServer server =
                new BackgroundJobServer(storageProvider, jobRunrJsonMapper, jobActivator, configuration);
        server.setJobFilters(List.of(filter));
        return server;
    }

    /** 生产环境的 api 进程不运行任务。 */
    static void checkInProcessAllowed(Environment environment) {
        if ("api".equals(AppRole.of(environment))
                && "production".equalsIgnoreCase(environment.getProperty("app.env"))) {
            throw new IllegalStateException(
                    "JOBS_IN_PROCESS must not be true in production, run jobs with APP_ROLE=worker");
        }
    }
}
