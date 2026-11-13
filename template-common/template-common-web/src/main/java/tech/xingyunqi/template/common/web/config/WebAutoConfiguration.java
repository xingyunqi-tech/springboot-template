package tech.xingyunqi.template.common.web.config;

import jakarta.servlet.Filter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.core.StandardHost;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.filter.UrlHandlerFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import tech.xingyunqi.template.common.web.bind.IdPathVariableResolver;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.docs.DocsController;
import tech.xingyunqi.template.common.web.envelope.ApiResponseAdvice;
import tech.xingyunqi.template.common.web.envelope.VoidEnvelopeReturnValueHandler;
import tech.xingyunqi.template.common.web.error.ErrorResponseWriter;
import tech.xingyunqi.template.common.web.error.ErrorTranslator;
import tech.xingyunqi.template.common.web.error.GlobalExceptionHandler;
import tech.xingyunqi.template.common.web.filter.CatchAllFilter;
import tech.xingyunqi.template.common.web.filter.CorsFilter;
import tech.xingyunqi.template.common.web.filter.ExpressRoutingFilter;
import tech.xingyunqi.template.common.web.filter.JsonBodyFilter;
import tech.xingyunqi.template.common.web.filter.RequestContextFilter;
import tech.xingyunqi.template.common.web.filter.SecurityHeadersFilter;
import tech.xingyunqi.template.common.web.http.ExpressContentType;
import tech.xingyunqi.template.common.web.http.JsonErrorReportValve;
import tech.xingyunqi.template.common.web.i18n.I18n;
import tech.xingyunqi.template.common.web.validation.ClassValidatorEngine;
import tools.jackson.databind.json.JsonMapper;

/** nestjs 全局 HTTP 契约：过滤器顺序、信封、错误翻译、校验执行器、Express 路由语义与文档端点。 */
@AutoConfiguration
@EnableConfigurationProperties(AppProperties.class)
public class WebAutoConfiguration implements WebMvcConfigurer {

    public static final int CONTENT_TYPE_ORDER = Ordered.HIGHEST_PRECEDENCE;
    public static final int REQUEST_CONTEXT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;
    public static final int CORS_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    public static final int SECURITY_HEADERS_ORDER = Ordered.HIGHEST_PRECEDENCE + 30;
    public static final int JSON_BODY_ORDER = Ordered.HIGHEST_PRECEDENCE + 40;
    public static final int CATCH_ALL_ORDER = Ordered.HIGHEST_PRECEDENCE + 2;
    public static final int TRAILING_SLASH_ORDER = Ordered.HIGHEST_PRECEDENCE + 50;
    public static final int ROUTING_ORDER = Ordered.HIGHEST_PRECEDENCE + 60;

    static final String VALIDATION_RESOURCE = "openapi/validation.json";
    static final String BASELINE_RESOURCE = "openapi/nestjs-baseline.json";

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.ignoreAcceptHeader(true).defaultContentType(MediaType.APPLICATION_JSON);
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        configurer.setPatternParser(parser);
    }

    @Bean
    WebMvcRegistrations apiPrefixRegistrations(AppProperties properties) {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new ApiPrefixHandlerMapping(properties.apiPrefix());
            }
        };
    }

    @Bean
    I18n i18n(AppProperties properties) {
        return I18n.load(properties.fallbackLanguage());
    }

    @Bean
    ErrorTranslator errorTranslator(I18n i18n, JsonMapper mapper) {
        return new ErrorTranslator(i18n, mapper);
    }

    @Bean
    ErrorResponseWriter errorResponseWriter(ErrorTranslator translator, JsonMapper mapper) {
        return new ErrorResponseWriter(translator, mapper);
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler(ErrorResponseWriter writer) {
        return new GlobalExceptionHandler(writer);
    }

    @Bean
    ApiResponseAdvice apiResponseAdvice(JsonMapper mapper) {
        return new ApiResponseAdvice(mapper);
    }

    @Bean
    ClassValidatorEngine classValidatorEngine() throws IOException {
        try (InputStream in = new ClassPathResource(VALIDATION_RESOURCE).getInputStream()) {
            return ClassValidatorEngine.load(in);
        }
    }

    @Bean
    DocsController docsController(AppProperties properties) throws IOException {
        byte[] baseline;
        try (InputStream in = new ClassPathResource(BASELINE_RESOURCE).getInputStream()) {
            baseline = in.readAllBytes();
        }
        return new DocsController(baseline, properties.docsEnabled());
    }

    @Bean
    static BeanPostProcessor envelopeHandlerAdapterCustomizer(
            ObjectProvider<JsonMapper> mapper, ObjectProvider<ClassValidatorEngine> engine) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RequestMappingHandlerAdapter adapter) {
                    List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
                    handlers.add(new VoidEnvelopeReturnValueHandler(mapper.getObject()));
                    handlers.addAll(nonNull(adapter.getReturnValueHandlers()));
                    adapter.setReturnValueHandlers(handlers);
                    List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
                    resolvers.add(new IdPathVariableResolver());
                    resolvers.add(new ValidatedBodyArgumentResolver(engine.getObject(), mapper.getObject()));
                    resolvers.addAll(nonNull(adapter.getArgumentResolvers()));
                    adapter.setArgumentResolvers(resolvers);
                }
                return bean;
            }
        };
    }

    private static <T> List<T> nonNull(@org.jspecify.annotations.Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatContractCustomizer() {
        return factory -> {
            factory.addContextValves(new ExpressContentType.CoyoteValve());
            factory.addConnectorCustomizers(connector -> {
                connector.setEncodedSolidusHandling("passthrough");
                connector.setAllowTrace(true);
            });
            factory.addContextCustomizers(context -> {
                if (context.getParent() instanceof StandardHost host) {
                    host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
                }
            });
        };
    }

    @Bean
    JsonErrorReportValve.Settings jsonErrorReportValveSettings(ErrorTranslator translator, AppProperties properties) {
        JsonErrorReportValve.Settings settings =
                new JsonErrorReportValve.Settings(translator::defaultMessage, corsFilter(properties));
        JsonErrorReportValve.configure(settings);
        return settings;
    }

    @Bean
    FilterRegistrationBean<ExpressContentType.Filter> contractContentTypeFilter() {
        return registration(new ExpressContentType.Filter(), CONTENT_TYPE_ORDER);
    }

    @Bean
    FilterRegistrationBean<RequestContextFilter> contractRequestContextFilter(I18n i18n) {
        return registration(new RequestContextFilter(i18n), REQUEST_CONTEXT_ORDER);
    }

    @Bean
    FilterRegistrationBean<CorsFilter> contractCorsFilter(AppProperties properties) {
        return registration(corsFilter(properties), CORS_ORDER);
    }

    static CorsFilter corsFilter(AppProperties properties) {
        String frontendDomain = properties.frontendDomain();
        return properties.isProduction() && frontendDomain != null && !frontendDomain.isBlank()
                ? CorsFilter.production(frontendDomain)
                : CorsFilter.reflecting();
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> contractSecurityHeadersFilter() {
        return registration(new SecurityHeadersFilter(), SECURITY_HEADERS_ORDER);
    }

    @Bean
    FilterRegistrationBean<JsonBodyFilter> contractJsonBodyFilter(ErrorResponseWriter errors) {
        return registration(new JsonBodyFilter(errors), JSON_BODY_ORDER);
    }

    @Bean
    FilterRegistrationBean<CatchAllFilter> contractCatchAllFilter(ErrorResponseWriter errors) {
        return registration(new CatchAllFilter(errors), CATCH_ALL_ORDER);
    }

    @Bean
    FilterRegistrationBean<UrlHandlerFilter> contractTrailingSlashFilter() {
        return registration(
                UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build(), TRAILING_SLASH_ORDER);
    }

    @Bean
    FilterRegistrationBean<ExpressRoutingFilter> contractExpressRoutingFilter(
            ObjectProvider<RequestMappingHandlerMapping> mappings, ErrorResponseWriter errors) {
        return registration(new ExpressRoutingFilter(mappings, errors), ROUTING_ORDER);
    }

    private static <F extends Filter> FilterRegistrationBean<F> registration(F filter, int order) {
        FilterRegistrationBean<F> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        return registration;
    }
}
