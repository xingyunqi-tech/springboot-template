package tech.xingyunqi.template.infrastructure.accountcleanup;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 部分清理器失败：消息为 {@code Account cleanup for user <id> failed: <name> (<原因>), …}。 */
public class AccountCleanupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> failedCleaners;

    public AccountCleanupException(int userId, List<String> failedCleaners, List<Exception> causes) {
        super("Account cleanup for user " + userId + " failed: "
                + IntStream.range(0, failedCleaners.size())
                        .mapToObj(
                                i -> failedCleaners.get(i) + " (" + AccountCleanupAlerts.messageOf(causes.get(i)) + ")")
                        .collect(Collectors.joining(", ")));
        this.failedCleaners = List.copyOf(failedCleaners);
        causes.forEach(this::addSuppressed);
    }

    public List<String> failedCleaners() {
        return failedCleaners;
    }
}
