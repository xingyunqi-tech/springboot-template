package tech.xingyunqi.template.common.web.docs;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.web.envelope.RawResponse;
import tech.xingyunqi.template.common.web.http.JsonResponses;
import tech.xingyunqi.template.common.web.http.Routes;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.common.web.route.SkipThrottle;

/** {@code /docs-json} 逐字节输出 nestjs 基线，{@code /docs} 为指向它的 Scalar 页面；关闭时两者 404。 */
@RestController
@RawResponse
@PublicEndpoint
@SkipThrottle
public class DocsController {

    static final String SCALAR_VERSION = "1.76.0";
    static final String DOCS_CSP = "default-src 'self';script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net;"
            + "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net;img-src 'self' data: https:;"
            + "font-src 'self' https: data:;connect-src 'self';object-src 'none';frame-ancestors 'self'";
    static final String HTML = """
            <!doctype html>
            <html>
              <head>
                <title>API</title>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
              </head>
              <body>
                <script id="api-reference" data-url="/docs-json"></script>
                <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference@%s"></script>
              </body>
            </html>
            """.formatted(SCALAR_VERSION);

    private final byte[] baseline;
    private final boolean enabled;

    public DocsController(byte[] baseline, boolean enabled) {
        this.baseline = baseline.clone();
        this.enabled = enabled;
    }

    @GetMapping("/docs-json")
    public ResponseEntity<byte[]> docsJson(HttpServletRequest request) {
        if (!enabled) {
            throw Routes.cannot(request);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, JsonResponses.CONTENT_TYPE)
                .body(baseline.clone());
    }

    @GetMapping("/docs")
    public ResponseEntity<String> docs(HttpServletRequest request) {
        if (!enabled) {
            throw Routes.cannot(request);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .header("Content-Security-Policy", DOCS_CSP)
                .body(HTML);
    }
}
