package tech.xingyunqi.template.infrastructure.files;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** 上传相关错误（nestjs {@code file-purposes.ts}）。 */
public final class FileErrors {

    private FileErrors() {}

    public static HttpStatusException invalidPurpose() {
        return HttpStatusException.validationError("purpose", "invalidFilePurpose");
    }

    public static HttpStatusException typeNotAllowed() {
        return HttpStatusException.validationError("file", "cantUploadFileType");
    }

    public static HttpStatusException selectFile() {
        return HttpStatusException.validationError("file", "selectFile");
    }

    /** 413：{@code message} 为 {@code PAYLOAD_TOO_LARGE}，{@code data.file} 为 {@code fileTooLarge} 译文。 */
    public static HttpStatusException tooLarge() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 413);
        body.put("errors", Map.of("file", "fileTooLarge"));
        return HttpStatusException.of(413, body);
    }

    public static HttpStatusException notFound() {
        return HttpStatusException.notFound(Map.of("error", "fileNotFound"));
    }

    public static HttpStatusException notAccessible() {
        return HttpStatusException.forbidden(Map.of("error", "fileNotAccessible"));
    }
}
