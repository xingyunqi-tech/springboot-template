package tech.xingyunqi.template.common.security.route;

/** refresh 令牌校验通过后的会话 id 与 hash。 */
public record RefreshPrincipal(int sessionId, String hash) {}
