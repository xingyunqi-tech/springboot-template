package tech.xingyunqi.template.infrastructure.files.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.oss.FileStorage;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.web.envelope.RawResponse;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.infrastructure.files.FileRecord;
import tech.xingyunqi.template.infrastructure.files.FileUrls;
import tech.xingyunqi.template.infrastructure.files.FilesService;

/** {@code GET files/private/:id}：local 驱动直接输出文件，S3 驱动 302 到预签名 URL。 */
@RestController
class FilesController {

    private final FilesService files;
    private final FileStorage storage;
    private final FileUrls urls;

    FilesController(FilesService files, FileStorage storage, FileUrls urls) {
        this.files = files;
        this.storage = storage;
        this.urls = urls;
    }

    @GetMapping("/api/v1/files/private/{id}")
    @AuthenticatedOnly
    @RawResponse
    public void readPrivateFile(@PathVariable String id, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        FileRecord file = files.findAccessible(id, AuthContext.current().id());
        if (storage.driver() == FileDriver.LOCAL) {
            SendFile.send(request, response, storage.localFile(file.path()));
            return;
        }
        SendFile.redirect(request, response, urls.build(file));
    }
}
