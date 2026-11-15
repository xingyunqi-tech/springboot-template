package tech.xingyunqi.template.core.users;

import org.springframework.stereotype.Component;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;

/** 解除头像引用；头像文件由文件清理器（order 1000）删除。 */
@Component
class UsersAccountCleaner implements AccountDataCleaner {

    static final int ORDER = 900;

    private final UsersService users;

    UsersAccountCleaner(UsersService users) {
        this.users = users;
    }

    @Override
    public String name() {
        return "users";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void clean(int userId) {
        users.detachPhoto(userId);
    }
}
