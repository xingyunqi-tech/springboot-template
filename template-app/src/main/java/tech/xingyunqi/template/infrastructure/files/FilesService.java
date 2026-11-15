package tech.xingyunqi.template.infrastructure.files;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** file 表的读写与访问控制（nestjs {@code FilesService} 与各上传驱动的 {@code create}）。 */
@Service
public class FilesService {

    /** nestjs {@code ParseUUIDPipe}（validator.js {@code isUUID(value, 'all')}）。 */
    private static final Pattern UUID =
            Pattern.compile("(?i)(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                    + "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)");

    private final FileMapper mapper;
    private final FileUrls urls;

    public FilesService(FileMapper mapper, FileUrls urls) {
        this.mapper = mapper;
        this.urls = urls;
    }

    public FileRecord create(String path, FilePurpose purpose, int uploaderId, @Nullable String mimeType, long size) {
        return mapper.insert(path, purpose.owner(uploaderId), purpose.visibility(), mimeType, Math.toIntExact(size));
    }

    /** 非法 uuid 400；不存在 404 {@code fileNotFound}；私有且非本人 403 {@code fileNotAccessible}。 */
    public FileRecord findAccessible(String id, int userId) {
        if (!UUID.matcher(id).matches()) {
            throw HttpStatusException.badRequest("Validation failed (uuid is expected)");
        }
        FileRecord file = mapper.findById(id);
        if (file == null) {
            throw FileErrors.notFound();
        }
        if (!file.accessibleBy(userId)) {
            throw FileErrors.notAccessible();
        }
        return file;
    }

    /** local 驱动：按存储键查找 PUBLIC 文件，私有文件视为不存在。 */
    public @Nullable FileRecord findPublicByFileName(String fileName) {
        FileRecord file = mapper.findByPath(urls.localPath(fileName));
        return file != null && FileRecord.PUBLIC.equals(file.visibility()) ? file : null;
    }

    /** local 驱动库中 {@code path} 列的值。 */
    public String localPath(String key) {
        return urls.localPath(key);
    }

    public List<FileRecord> findByOwner(int ownerUserId, @Nullable String afterId, int limit) {
        return mapper.findByOwner(ownerUserId, afterId, limit);
    }
}
