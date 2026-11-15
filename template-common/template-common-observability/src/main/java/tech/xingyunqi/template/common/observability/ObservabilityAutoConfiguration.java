package tech.xingyunqi.template.common.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tech.xingyunqi.template.common.observability.logging.RequestLoggingFilter;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.common.observability.metrics.HttpMetricsFilter;
import tech.xingyunqi.template.common.observability.metrics.MetricsEndpoint;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.common.web.config.MetricsProperties;
import tech.xingyunqi.template.common.web.config.WebAutoConfiguration;

/** 自定义指标、HTTP 指标过滤器、{@code METRICS_PATH} 端点与请求日志过滤器。 */
@AutoConfiguration(after = WebAutoConfiguration.class)
public class ObservabilityAutoConfiguration {

    static final String HANDLER_MAPPING = "requestMappingHandlerMapping";

    @Bean
    AppMetrics appMetrics(AppProperties app) {
        return new AppMetrics(app.name());
    }

    @Bean
    FilterRegistrationBean<HttpMetricsFilter> httpMetricsFilter(AppMetrics metrics, MetricsProperties properties) {
        FilterRegistrationBean<HttpMetricsFilter> registration =
                new FilterRegistrationBean<>(new HttpMetricsFilter(metrics));
        registration.setOrder(WebAutoConfiguration.HTTP_METRICS_ORDER);
        registration.setEnabled(properties.isEnabled());
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.setOrder(WebAutoConfiguration.REQUEST_LOGGING_ORDER);
        return registration;
    }

    @Bean
    MetricsEndpoint metricsEndpoint(
            AppMetrics metrics, MetricsProperties properties, ObjectProvider<PrometheusMeterRegistry> defaults) {
        return new MetricsEndpoint(metrics, properties.isEnabled() ? defaults.getIfAvailable() : null);
    }

    @Bean
    static SmartInitializingSingleton metricsRouteRegistrar(
            BeanFactory beans, ObjectProvider<MetricsEndpoint> endpoint, ObjectProvider<MetricsProperties> properties) {
        return () -> {
            if (!beans.containsBean(HANDLER_MAPPING)) {
                return;
            }
            RequestMappingHandlerMapping mapping = beans.getBean(HANDLER_MAPPING, RequestMappingHandlerMapping.class);
            RequestMappingInfo info = RequestMappingInfo.paths(
                            properties.getObject().route())
                    .methods(RequestMethod.GET)
                    .options(mapping.getBuilderConfiguration())
                    .build();
            try {
                mapping.registerMapping(info, endpoint.getObject(), MetricsEndpoint.class.getMethod("getMetrics"));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
