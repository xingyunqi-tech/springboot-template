package tech.xingyunqi.template.infrastructure.files.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.oss.ConditionalOnFileDriver;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.contract.api.FilesApi;
import tech.xingyunqi.template.contract.model.FileResponseDto;

/** {@code FILE_DRIVER=s3}：接收后上传到桶。 */
@RestController
@ConditionalOnFileDriver(FileDriver.S3)
class FilesS3Controller implements FilesApi {

    private final FileUploads uploads;

    FilesS3Controller(FileUploads uploads) {
        this.uploads = uploads;
    }

    @Override
    @AuthenticatedOnly
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponseDto uploadFile(@Nullable String purpose, @Nullable Object acceptLanguage) {
        try {
            return uploads.uploadMultipart(CurrentRequest.get());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
