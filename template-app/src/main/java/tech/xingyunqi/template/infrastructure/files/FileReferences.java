package tech.xingyunqi.template.infrastructure.files;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** 业务记录引用文件前的检查：不存在 404 {@code fileNotFound}，私有且非本人 403 {@code fileNotAccessible}。 */
@Component
public class FileReferences {

    private final FileMapper mapper;

    public FileReferences(FileMapper mapper) {
        this.mapper = mapper;
    }

    public List<FileRecord> assertReferenceable(Collection<String> ids, @Nullable Integer userId) {
        List<String> unique = List.copyOf(new LinkedHashSet<>(ids));
        List<FileRecord> files = unique.isEmpty() ? List.of() : mapper.findByIds(unique);
        if (files.size() != unique.size()) {
            throw HttpStatusException.notFound(Map.of("error", "fileNotFound"));
        }
        if (files.stream().anyMatch(file -> !file.accessibleBy(userId))) {
            throw HttpStatusException.forbidden(Map.of("error", "fileNotAccessible"));
        }
        return files;
    }
}
