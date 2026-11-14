package tech.xingyunqi.template.common.security.route;

import tools.jackson.databind.node.ObjectNode;

/** access 令牌签名有效后的会话 / 用户校验（nestjs JwtStrategy.validate），失败时抛 401。 */
public interface AccessTokenValidator {

    AuthPrincipal validate(ObjectNode payload);
}
