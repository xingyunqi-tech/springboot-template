package tech.xingyunqi.template.infrastructure.files.web;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.contract.model.FileType;
import tech.xingyunqi.template.infrastructure.files.FileRecord;
import tech.xingyunqi.template.infrastructure.files.FileUrls;

/**
 * 响应中的文件：{@code {id, path, visibility, mimeType, size}}，{@code path} 为对外 URL，不输出 {@code ownerUserId}、{@code sha256}。
 */
@Component
class FileViews {

    private final FileUrls urls;

    FileViews(FileUrls urls) {
        this.urls = urls;
    }

    FileType of(FileRecord file) {
        Integer size = file.size();
        return new FileType(
                file.id(),
                urls.build(file),
                FileType.VisibilityEnum.fromValue(file.visibility()),
                file.mimeType(),
                size == null ? null : BigDecimal.valueOf(size));
    }
}
