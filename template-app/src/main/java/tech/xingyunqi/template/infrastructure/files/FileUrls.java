package tech.xingyunqi.template.infrastructure.files;

import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.oss.FileStorage;
import tech.xingyunqi.template.common.web.config.AppProperties;

/**
 * 文件对外 URL（nestjs {@code buildFileUrl}）：local 驱动 PUBLIC 为 {@code BACKEND_DOMAIN + path}、PRIVATE 为私有访问接口；S3 驱动为预签名 GET
 * URL。
 */
@Component
public class FileUrls {

    private final AppProperties app;
    private final FileStorage storage;

    public FileUrls(AppProperties app, FileStorage storage) {
        this.app = app;
        this.storage = storage;
    }

    public String build(FileRecord file) {
        if (storage.driver() != FileDriver.LOCAL) {
            return storage.presignedGetUrl(file.path());
        }
        if (FileRecord.PRIVATE.equals(file.visibility())) {
            return app.backendDomain() + "/" + app.apiPrefix() + "/v1/files/private/" + file.id();
        }
        return app.backendDomain() + file.path();
    }

    /** local 驱动库中 {@code path} 列的值：{@code /<API_PREFIX>/v1/files/<key>}。 */
    public String localPath(String key) {
        return "/" + app.apiPrefix() + "/v1/files/" + key;
    }
}
