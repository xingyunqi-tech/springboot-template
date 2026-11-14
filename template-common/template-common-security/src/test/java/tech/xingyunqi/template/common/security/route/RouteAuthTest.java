package tech.xingyunqi.template.common.security.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.OptionalAuth;
import tech.xingyunqi.template.common.web.route.PermissionsGuarded;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.common.web.route.RequireAnyPermission;
import tech.xingyunqi.template.common.web.route.RequirePermissions;

class RouteAuthTest {

    @AuthenticatedOnly
    @PermissionsGuarded
    @RequireAnyPermission({"a:read", "a:list"})
    static class Guarded {
        public void inherited() {}

        @RequirePermissions("b:write")
        public void overridden() {}

        @PublicEndpoint
        public void publicOverride() {}
    }

    static class Undeclared {
        public void handler() {}
    }

    static class Conflicting {
        @PublicEndpoint
        @OptionalAuth
        public void handler() {}
    }

    static RouteAuth route(Object bean, String method) throws Exception {
        return RouteAuth.of(new HandlerMethod(bean, bean.getClass().getMethod(method)));
    }

    @Test
    @DisplayName("类级权限声明作用于未覆盖的方法")
    void classLevelPermissions() throws Exception {
        assertThat(route(new Guarded(), "inherited"))
                .isEqualTo(new RouteAuth(RouteAuth.Mode.JWT, true, java.util.List.of("a:read", "a:list"), true));
    }

    @Test
    @DisplayName("方法级权限声明覆盖类级，与注解种类无关")
    void methodLevelOverrides() throws Exception {
        assertThat(route(new Guarded(), "overridden"))
                .isEqualTo(new RouteAuth(RouteAuth.Mode.JWT, true, java.util.List.of("b:write"), false));
    }

    @Test
    @DisplayName("方法级鉴权方式覆盖类级")
    void methodLevelMode() throws Exception {
        assertThat(route(new Guarded(), "publicOverride").mode()).isEqualTo(RouteAuth.Mode.PUBLIC);
    }

    @Test
    @DisplayName("未声明或声明多种鉴权方式时报错")
    void requiresExactlyOneMode() {
        assertThatThrownBy(() -> route(new Undeclared(), "handler")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> route(new Conflicting(), "handler")).isInstanceOf(IllegalStateException.class);
    }
}
