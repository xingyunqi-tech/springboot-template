package tech.xingyunqi.template.infrastructure.files.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.oss.ConditionalOnFileDriver;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.oss.FileStorage;
import tech.xingyunqi.template.common.web.envelope.RawResponse;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.contract.api.FilesApi;
import tech.xingyunqi.template.contract.model.FileResponseDto;
import tech.xingyunqi.template.infrastructure.files.FileErrors;
import tech.xingyunqi.template.infrastructure.files.FilesService;

/** {@code FILE_DRIVER=local}：上传写入 {@code ./files}，公开文件经 {@code GET files/:path} 读取。 */
@RestController
@ConditionalOnFileDriver(FileDriver.LOCAL)
class FilesLocalController implements FilesApi {

    private final FileUploads uploads;
    private final FilesService files;
    private final FileStorage storage;

    FilesLocalController(FileUploads uploads, FilesService files, FileStorage storage) {
        this.uploads = uploads;
        this.files = files;
        this.storage = storage;
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

    @GetMapping("/api/v1/files/{path}")
    @PublicEndpoint
    @RawResponse
    public void downloadFile(@PathVariable String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (path.indexOf('/') >= 0 || path.indexOf('\\') >= 0 || files.findPublicByFileName(path) == null) {
            throw FileErrors.notFound();
        }
        SendFile.send(request, response, storage.localFile(path));
    }
}
