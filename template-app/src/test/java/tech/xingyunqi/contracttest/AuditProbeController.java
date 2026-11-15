package tech.xingyunqi.contracttest;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.contract.model.AuthUpdateDto;
import tech.xingyunqi.template.infrastructure.audit.Audit;
import tech.xingyunqi.template.infrastructure.audit.AuditAction;

@RestController
public class AuditProbeController {

    static final String TOO_LONG_ENTITY_TYPE = "EntityTypeLongerThanTheColumnAllowsEntityTypeLongerThanTheColumnAllows"
            + "EntityTypeLongerThanTheColumnAllows";

    @PatchMapping("/api/v1/audit-probe/items/{id}")
    @PublicEndpoint
    @Audit(action = AuditAction.UPDATE, entityType = "Item")
    public Map<String, Object> update(@PathVariable String id, @RequestBody AuthUpdateDto body) {
        return Map.of("ok", true);
    }

    @PatchMapping("/api/v1/audit-probe/me/items/{id}")
    @AuthenticatedOnly
    @Audit(action = AuditAction.UPDATE, entityType = "Item")
    public Map<String, Object> updateMine(@PathVariable String id, @RequestBody AuthUpdateDto body) {
        return Map.of("ok", true);
    }

    @PostMapping("/api/v1/audit-probe/items")
    @PublicEndpoint
    @Audit(
            action = AuditAction.CREATE,
            entityType = "Item",
            entityIdFrom = Audit.EntityIdFrom.RESPONSE,
            includeResponse = true)
    public Map<String, Object> create() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 99);
        item.put("name", "n");
        item.put("accessToken", "t");
        return item;
    }

    @PostMapping("/api/v1/audit-probe/fail")
    @PublicEndpoint
    @Audit(action = AuditAction.DELETE, entityType = "Item")
    public Map<String, Object> fail() {
        throw HttpStatusException.validationError("id", "invalid");
    }

    @PostMapping("/api/v1/audit-probe/broken")
    @PublicEndpoint
    @Audit(action = AuditAction.DELETE, entityType = TOO_LONG_ENTITY_TYPE)
    public Map<String, Object> broken() {
        return Map.of("ok", true);
    }
}
