package tech.xingyunqi.template.infrastructure.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.mybatis.WriteTransactions;
import tech.xingyunqi.template.common.oss.FileStorage;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;

/** 删除该用户上传的文件记录与存储对象，每批 100 条；仍被外键引用的文件保留。 */
@Component
class FilesAccountCleaner implements AccountDataCleaner {

    static final int ORDER = 1000;
    static final int BATCH_SIZE = 100;
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final Logger LOG = LoggerFactory.getLogger(FilesAccountCleaner.class);

    private final FilesService files;
    private final FileMapper mapper;
    private final FileStorage storage;
    private final WriteTransactions transactions;

    FilesAccountCleaner(FilesService files, FileMapper mapper, FileStorage storage, WriteTransactions transactions) {
        this.files = files;
        this.mapper = mapper;
        this.storage = storage;
        this.transactions = transactions;
    }

    @Override
    public String name() {
        return "files";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void clean(int userId) {
        String afterId = null;
        while (true) {
            List<FileRecord> batch = files.findByOwner(userId, afterId, BATCH_SIZE);
            for (FileRecord file : batch) {
                remove(file);
            }
            if (batch.size() < BATCH_SIZE) {
                return;
            }
            afterId = batch.getLast().id();
        }
    }

    /** 同一事务内先删记录再删存储对象，存储删除失败时回滚该记录并抛出。 */
    void remove(FileRecord file) {
        try {
            transactions.run(() -> {
                mapper.deleteById(file.id());
                try {
                    storage.delete(file.path());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (RuntimeException e) {
            if (!isForeignKeyViolation(e)) {
                throw e;
            }
            LOG.warn("File {} is still referenced and kept", file.id());
        }
    }

    static boolean isForeignKeyViolation(@Nullable Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
