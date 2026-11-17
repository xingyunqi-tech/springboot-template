package tech.xingyunqi.template.modules.addresses;

import org.springframework.stereotype.Component;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;

/** 删除该用户的全部地址。 */
@Component
class AddressesAccountCleaner implements AccountDataCleaner {

    static final int ORDER = 110;

    private final AddressesService addresses;

    AddressesAccountCleaner(AddressesService addresses) {
        this.addresses = addresses;
    }

    @Override
    public String name() {
        return "addresses";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void clean(int userId) {
        addresses.removeByUser(userId);
    }
}
