package tech.xingyunqi.template.modules.appprofile.web;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.contract.api.AppProfileApi;
import tech.xingyunqi.template.contract.model.AppProfile;
import tech.xingyunqi.template.modules.appprofile.AppProfileService;

@RestController
@PublicEndpoint
class AppProfileController implements AppProfileApi {

    private final AppProfileService profiles;

    AppProfileController(AppProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    public AppProfile getAppProfile(@Nullable Object acceptLanguage) {
        return view(profiles.get());
    }

    static AppProfile view(Map<String, @Nullable String> profile) {
        return new AppProfile(
                profile.get("appName"),
                profile.get("slogan"),
                profile.get("companyName"),
                profile.get("copyright"),
                profile.get("icpLicense"),
                profile.get("servicePhone"),
                profile.get("serviceHours"),
                profile.get("serviceEmail"),
                profile.get("shareTitle"));
    }
}
