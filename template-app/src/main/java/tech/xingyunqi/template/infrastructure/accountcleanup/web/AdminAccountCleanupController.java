package tech.xingyunqi.template.infrastructure.accountcleanup.web;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.PermissionsGuarded;
import tech.xingyunqi.template.common.web.route.RequirePermissions;
import tech.xingyunqi.template.contract.api.AdminAccountCleanupApi;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupService;

@RestController
@AuthenticatedOnly
@PermissionsGuarded
class AdminAccountCleanupController implements AdminAccountCleanupApi {

    private final AccountCleanupService service;

    AdminAccountCleanupController(AccountCleanupService service) {
        this.service = service;
    }

    @Override
    @RequirePermissions("user:delete")
    public void adminRetriggerAccountCleanup(BigDecimal id, @Nullable Object acceptLanguage) {
        service.retrigger(id.intValueExact());
    }
}
