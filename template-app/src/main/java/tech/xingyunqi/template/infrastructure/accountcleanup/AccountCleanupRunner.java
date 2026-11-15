package tech.xingyunqi.template.infrastructure.accountcleanup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.alert.AlertReporter;

/** 按 order 依次执行全部清理器，单个失败不影响其余清理器，全部执行后汇总抛出；全部成功时写 {@code account_cleanup}。 */
@Component
public class AccountCleanupRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AccountCleanupRunner.class);

    private final List<AccountDataCleaner> cleaners;
    private final AccountCleanupMapper mapper;
    private final AlertReporter alerts;

    public AccountCleanupRunner(List<AccountDataCleaner> cleaners, AccountCleanupMapper mapper, AlertReporter alerts) {
        this.cleaners = cleaners.stream()
                .sorted(Comparator.comparingInt(AccountDataCleaner::order))
                .toList();
        this.mapper = mapper;
        this.alerts = alerts;
    }

    public List<String> cleanerNames() {
        return cleaners.stream().map(AccountDataCleaner::name).toList();
    }

    public void run(int userId) {
        List<String> failed = new ArrayList<>();
        List<Exception> causes = new ArrayList<>();
        for (AccountDataCleaner cleaner : cleaners) {
            try {
                cleaner.clean(userId);
            } catch (Exception e) {
                failed.add(cleaner.name());
                causes.add(e);
                LOG.error("Cleaner {} failed for user {}", cleaner.name(), userId, e);
            }
        }
        if (!failed.isEmpty()) {
            throw new AccountCleanupException(userId, failed, causes);
        }
        mapper.markCompleted(userId);
        LOG.info("Account data of user {} cleaned", userId);
    }

    public void reportFailure(int userId, Exception error) {
        AccountCleanupAlerts.raise(
                alerts,
                userId,
                error,
                error instanceof AccountCleanupException cleanup ? cleanup.failedCleaners() : null);
    }
}
