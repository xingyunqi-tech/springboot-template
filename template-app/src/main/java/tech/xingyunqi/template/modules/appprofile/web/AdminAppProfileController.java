package tech.xingyunqi.template.modules.appprofile.web;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.web.bind.ValidatedBodyArgumentResolver;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.PermissionsGuarded;
import tech.xingyunqi.template.common.web.route.RequirePermissions;
import tech.xingyunqi.template.contract.api.AdminAppProfileApi;
import tech.xingyunqi.template.contract.model.AppProfile;
import tech.xingyunqi.template.contract.model.UpdateAppProfileDto;
import tech.xingyunqi.template.modules.appprofile.AppProfileService;

@RestController
@AuthenticatedOnly
@PermissionsGuarded
@RequirePermissions("system:config")
class AdminAppProfileController implements AdminAppProfileApi {

    private final AppProfileService profiles;

    AdminAppProfileController(AppProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    public AppProfile adminUpdateAppProfile(UpdateAppProfileDto updateAppProfileDto, @Nullable Object acceptLanguage) {
        return AppProfileController.view(
                profiles.update(ValidatedBodyArgumentResolver.validatedBody(CurrentRequest.get())));
    }
}
