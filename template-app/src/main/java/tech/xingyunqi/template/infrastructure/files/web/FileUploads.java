package tech.xingyunqi.template.infrastructure.files.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.oss.FileStorage;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.security.route.AuthPrincipal;
import tech.xingyunqi.template.common.security.route.PermissionResolver;
import tech.xingyunqi.template.common.web.validation.ClassValidatorEngine;
import tech.xingyunqi.template.common.web.validation.ParamType;
import tech.xingyunqi.template.contract.model.FileResponseDto;
import tech.xingyunqi.template.infrastructure.files.FileErrors;
import tech.xingyunqi.template.infrastructure.files.FilePurpose;
import tech.xingyunqi.template.infrastructure.files.FileRecord;
import tech.xingyunqi.template.infrastructure.files.FilesService;
import tech.xingyunqi.template.infrastructure.files.UploadLimits;
import tech.xingyunqi.template.infrastructure.files.upload.FileSink;
import tech.xingyunqi.template.infrastructure.files.upload.LocalFileSink;
import tech.xingyunqi.template.infrastructure.files.upload.MultipartReceiver;
import tech.xingyunqi.template.infrastructure.files.upload.ReceivedFile;
import tech.xingyunqi.template.infrastructure.files.upload.S3FileSink;
import tools.jackson.databind.JsonNode;

/**
 * 上传流程（JWT 之后）：用途守卫（用途合法、权限、{@code Content-Length} 未明显超限）→ 流式接收 multipart → 查询参数校验 → 无文件 / 超过用途上限 → 写入 file
 * 记录；任一步失败时删除已存储的文件。
 */
@Component
class FileUploads {

    static final long MULTIPART_OVERHEAD_BYTES = 16 * 1024;
    static final String QUERY_DTO = "UploadFileQueryDto";
    private static final int KEY_LENGTH = 21;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FilesService files;
    private final FileStorage storage;
    private final UploadLimits limits;
    private final PermissionResolver permissions;
    private final ClassValidatorEngine engine;
    private final FileViews views;

    FileUploads(
            FilesService files,
            FileStorage storage,
            UploadLimits limits,
            PermissionResolver permissions,
            ClassValidatorEngine engine,
            FileViews views) {
        this.files = files;
        this.storage = storage;
        this.limits = limits;
        this.permissions = permissions;
        this.engine = engine;
        this.views = views;
    }

    /** nestjs {@code FileUploadPurposeGuard}。 */
    FilePurpose guard(HttpServletRequest request) {
        FilePurpose purpose = FilePurpose.parse(rawPurpose(request));
        if (purpose == null) {
            throw FileErrors.invalidPurpose();
        }
        String permission = purpose.permission();
        if (permission != null) {
            Integer roleId = AuthContext.current().roleId();
            if (roleId == null || !permissions.permissionsForRole(roleId).contains(permission)) {
                throw HttpStatusException.forbidden("Missing permissions: " + permission);
            }
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0 && contentLength > limits.maxSize(purpose) + MULTIPART_OVERHEAD_BYTES) {
            throw FileErrors.tooLarge();
        }
        return purpose;
    }

    /** local / s3 驱动：multipart 上传。 */
    FileResponseDto uploadMultipart(HttpServletRequest request) throws IOException {
        FilePurpose purpose = guard(request);
        ReceivedFile received = MultipartReceiver.receive(
                request.getContentType(),
                request.getInputStream(),
                fileName -> {
                    if (purpose.mimeType(fileName) == null) {
                        throw FileErrors.typeNotAllowed();
                    }
                },
                fileName -> openSink(purpose, fileName),
                limits.maxFileSize());
        try {
            validateQuery(request);
            if (received == null) {
                throw FileErrors.selectFile();
            }
            if (received.size() > limits.maxSize(purpose)) {
                throw FileErrors.tooLarge();
            }
            String key = received.sink().key();
            String path = storage.driver() == FileDriver.LOCAL ? files.localPath(key) : key;
            FileRecord file = files.create(
                    path, purpose, currentUser().id(), purpose.mimeType(received.originalName()), received.size());
            return new FileResponseDto(views.of(file));
        } catch (RuntimeException | Error e) {
            if (received != null) {
                received.sink().discard();
            }
            throw e;
        }
    }

    /** s3-presigned 驱动：按 {@code FileUploadDto} 校验请求体，签发 PUT URL 并立即写入 file 记录。 */
    PresignedFileResponse uploadPresigned(HttpServletRequest request, @Nullable JsonNode body) {
        FilePurpose purpose = guard(request);
        JsonNode dto = engine.run("FileUploadDto", body, ParamType.BODY);
        validateQuery(request);
        String fileName = dto.path("fileName").stringValue("");
        double fileSize = dto.path("fileSize").asDouble();
        String mimeType = purpose.mimeType(fileName);
        if (mimeType == null) {
            throw FileErrors.typeNotAllowed();
        }
        if (fileSize > limits.maxSize(purpose)) {
            throw FileErrors.tooLarge();
        }
        long size = integerColumn(fileSize);
        String key = randomKey(fileName);
        String signedUrl = storage.presignedPutUrl(key, size);
        FileRecord file = files.create(key, purpose, currentUser().id(), mimeType, size);
        return new PresignedFileResponse(views.of(file), signedUrl);
    }

    /** {@code file.size} 为 integer 列：非整数或超出 int 范围时抛出（500）。 */
    static long integerColumn(double fileSize) {
        if (fileSize != Math.rint(fileSize) || Math.abs(fileSize) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid value for integer column file.size: " + fileSize);
        }
        return (long) fileSize;
    }

    void validateQuery(HttpServletRequest request) {
        engine.run(QUERY_DTO, QueryString.parse(request.getQueryString()), ParamType.QUERY);
    }

    private FileSink openSink(FilePurpose purpose, String fileName) throws IOException {
        String key = randomKey(fileName);
        if (storage.driver() == FileDriver.LOCAL) {
            return new LocalFileSink(storage.localRoot(), key);
        }
        String mimeType = purpose.mimeType(fileName);
        return new S3FileSink(storage, key, mimeType == null ? "application/octet-stream" : mimeType);
    }

    /** nestjs {@code randomStringGenerator()}（21 位小写十六进制）+ {@code .} + 小写扩展名。 */
    static String randomKey(String fileName) {
        byte[] bytes = new byte[(KEY_LENGTH + 1) / 2];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, KEY_LENGTH) + "." + FilePurpose.extension(fileName);
    }

    private static @Nullable Object rawPurpose(HttpServletRequest request) {
        JsonNode value = QueryString.parse(request.getQueryString()).get("purpose");
        if (value == null) {
            return null;
        }
        return value.isString() ? value.stringValue() : value;
    }

    private static AuthPrincipal currentUser() {
        return AuthContext.current();
    }
}
