package tech.xingyunqi.template.modules.home.web;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.json.JsCompatibleModule;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.contract.api.HomeApi;
import tech.xingyunqi.template.contract.model.GetAppInfo200Response;
import tech.xingyunqi.template.contract.model.SimpleHealthCheck200Response;
import tech.xingyunqi.template.contract.model.SimpleHealthCheck200ResponseMemory;

@RestController
@PublicEndpoint
public class HomeController implements HomeApi {

    private static final long MB = 1024 * 1024;
    private static final Pattern VM_RSS = Pattern.compile("VmRSS:\\s+(\\d+) kB");

    private final AppProperties properties;
    private final Clock clock;

    public HomeController(AppProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public GetAppInfo200Response getAppInfo(@Nullable Object acceptLanguage) {
        return new GetAppInfo200Response(properties.name());
    }

    @Override
    public SimpleHealthCheck200Response simpleHealthCheck(@Nullable Object acceptLanguage) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        Long rss = residentSetSize();
        long rssBytes = rss != null ? rss : heap.getCommitted() + nonHeap.getCommitted();
        return new SimpleHealthCheck200Response(
                "ok",
                JsCompatibleModule.isoTimestamp(clock.instant()),
                BigDecimal.valueOf(ManagementFactory.getRuntimeMXBean().getUptime() / 1000),
                new SimpleHealthCheck200ResponseMemory(
                        megabytes(heap.getUsed()), megabytes(heap.getCommitted()), megabytes(rssBytes)),
                "Java " + Runtime.version());
    }

    private static BigDecimal megabytes(long bytes) {
        return BigDecimal.valueOf(Math.round((double) bytes / MB));
    }

    private static @Nullable Long residentSetSize() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isReadable(status)) {
            return null;
        }
        try {
            Matcher matcher = VM_RSS.matcher(Files.readString(status));
            return matcher.find() ? Long.parseLong(matcher.group(1)) * 1024 : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
