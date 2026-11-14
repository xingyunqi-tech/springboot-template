package tech.xingyunqi.template.infrastructure.files;

import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.web.config.AppProperties;

/** 文件对外 URL（local 驱动）：PUBLIC 为 {@code BACKEND_DOMAIN + path}，PRIVATE 为私有访问接口地址。 */
@Component
public class FileUrls {

    private final AppProperties app;

    public FileUrls(AppProperties app) {
        this.app = app;
    }

    public String build(FileRecord file) {
        if (FileRecord.PRIVATE.equals(file.visibility())) {
            return app.backendDomain() + "/" + app.apiPrefix() + "/v1/files/private/" + file.id();
        }
        return app.backendDomain() + file.path();
    }
}
