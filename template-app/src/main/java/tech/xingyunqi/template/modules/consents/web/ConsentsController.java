package tech.xingyunqi.template.modules.consents.web;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.security.route.AuthContext;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.common.web.http.ClientInfo;
import tech.xingyunqi.template.common.web.http.CurrentRequest;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.contract.api.ConsentsApi;
import tech.xingyunqi.template.contract.model.AgreeConsentDto;
import tech.xingyunqi.template.contract.model.ConsentStatusDto;
import tech.xingyunqi.template.contract.model.ConsentType;
import tech.xingyunqi.template.modules.consents.ConsentsService;
import tech.xingyunqi.template.modules.consents.domain.ConsentStatus;

@RestController
@AuthenticatedOnly
class ConsentsController implements ConsentsApi {

    private final ConsentsService consents;
    private final AppProperties app;

    ConsentsController(ConsentsService consents, AppProperties app) {
        this.consents = consents;
        this.app = app;
    }

    @Override
    public List<ConsentStatusDto> getMyConsents(@Nullable Object acceptLanguage) {
        return consents.findStatus(AuthContext.current().id()).stream()
                .map(ConsentsController::view)
                .toList();
    }

    @Override
    public ConsentStatusDto agreeConsent(AgreeConsentDto agreeConsentDto, @Nullable Object acceptLanguage) {
        return view(consents.agree(
                AuthContext.current().id(),
                tech.xingyunqi.template.modules.consents.domain.ConsentType.valueOf(
                        agreeConsentDto.type().getValue()),
                agreeConsentDto.version(),
                ClientInfo.clientIp(CurrentRequest.get(), app.trustProxy())));
    }

    static ConsentStatusDto view(ConsentStatus status) {
        return new ConsentStatusDto(
                ConsentType.fromValue(status.type().name()),
                status.currentVersion(),
                status.agreedVersion(),
                status.agreedAt(),
                status.agreed(),
                status.needsConsent());
    }
}
