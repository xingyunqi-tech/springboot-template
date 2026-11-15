package tech.xingyunqi.template.infrastructure.accountcleanup;

import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.core.users.UsersService;

@Service
public class AccountCleanupService {

    private final UsersService users;
    private final AccountCleanupProducer producer;

    public AccountCleanupService(UsersService users, AccountCleanupProducer producer) {
        this.users = users;
        this.producer = producer;
    }

    /** 仅已注销用户：未注销 422 {@code {user: userNotDeleted}}，不存在 404 {@code userNotFound}。 */
    public void retrigger(int userId) {
        if (!users.isDeleted(userId)) {
            if (users.findById(userId).isPresent()) {
                throw HttpStatusException.validationError("user", "userNotDeleted");
            }
            throw HttpStatusException.notFoundError("userNotFound");
        }
        producer.enqueueCleanup(userId);
    }
}
