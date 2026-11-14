package tech.xingyunqi.template.common.security.route;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/** access 令牌校验通过后的当前用户；{@code roleId} 取自数据库（或 30s 鉴权缓存）。 */
public record AuthPrincipal(int id, int sessionId, @Nullable Integer roleId, ObjectNode payload) {}
