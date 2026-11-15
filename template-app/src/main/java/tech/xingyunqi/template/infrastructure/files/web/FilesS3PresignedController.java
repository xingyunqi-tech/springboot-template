package tech.xingyunqi.template.infrastructure.files.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.oss.ConditionalOnFileDriver;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.web.filter.JsonBodyFilter;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;

/** {@code FILE_DRIVER=s3-presigned}：请求体为 {@code FileUploadDto}，返回文件与 PUT 预签名 URL。 */
@RestController
@ConditionalOnFileDriver(FileDriver.S3_PRESIGNED)
class FilesS3PresignedController {

    private final FileUploads uploads;

    FilesS3PresignedController(FileUploads uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/api/v1/files/upload")
    @AuthenticatedOnly
    @ResponseStatus(HttpStatus.CREATED)
    public PresignedFileResponse uploadFile(HttpServletRequest request) {
        return uploads.uploadPresigned(request, JsonBodyFilter.parsedBody(request));
    }
}
