package tech.xingyunqi.template.tools.generator;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** 由生成的 {@code *Api} 接口反射出 controller 桩的方法签名、鉴权注解与 import。 */
record Stubs(List<String> imports, @Nullable String classAuth, List<StubMethod> methods) {

    static final String PUBLIC = "PublicEndpoint";
    static final String JWT = "AuthenticatedOnly";
    static final String ROUTE_PACKAGE = "tech.xingyunqi.template.common.web.route.";

    /** 一个桩方法；{@code auth} 为方法级鉴权注解名，与类级相同时为空串。 */
    record StubMethod(String name, String returnType, String parameters, String auth) {

        Map<String, String> model() {
            return Map.of("name", name, "returnType", returnType, "parameters", parameters, "auth", auth);
        }
    }

    static Stubs of(Class<?> api, Map<String, ModuleGenerator.Operation> operations) {
        Set<String> imports = new TreeSet<>();
        imports.add(api.getName());
        imports.add("org.springframework.web.bind.annotation.RestController");
        imports.add("tech.xingyunqi.template.common.core.exception.HttpStatusException");
        List<Method> declared = Arrays.stream(api.getMethods())
                .filter(method -> !method.isDefault() && !Modifier.isStatic(method.getModifiers()))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
        Set<String> auths = new TreeSet<>();
        for (Method method : declared) {
            auths.add(auth(method, operations));
        }
        String classAuth = auths.size() == 1 ? auths.iterator().next() : null;
        auths.forEach(name -> imports.add(ROUTE_PACKAGE + name));
        List<StubMethod> methods = new ArrayList<>();
        for (Method method : declared) {
            String auth = auth(method, operations);
            methods.add(new StubMethod(
                    method.getName(),
                    render(method.getAnnotatedReturnType(), imports),
                    Arrays.stream(method.getParameters())
                            .map(parameter -> parameter(parameter, imports))
                            .collect(Collectors.joining(", ")),
                    auth.equals(classAuth) ? "" : auth));
        }
        return new Stubs(List.copyOf(imports), classAuth, methods);
    }

    private static String auth(Method method, Map<String, ModuleGenerator.Operation> operations) {
        ModuleGenerator.Operation operation = operations.get(method.getName());
        if (operation == null) {
            throw new ModuleGenerator.GeneratorException(method.getDeclaringClass()
                            .getSimpleName() + "." + method.getName() + " is not an operation of the tag");
        }
        return operation.secured() ? JWT : PUBLIC;
    }

    private static String parameter(Parameter parameter, Set<String> imports) {
        boolean nullable = isNullable(parameter.getAnnotations())
                || isNullable(parameter.getAnnotatedType().getAnnotations());
        String type = render(parameter.getAnnotatedType(), imports);
        if (nullable) {
            imports.add("org.jspecify.annotations.Nullable");
            type = "@Nullable " + type;
        }
        return type + " " + parameter.getName();
    }

    private static boolean isNullable(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .anyMatch(a -> a.annotationType().getSimpleName().equals("Nullable"));
    }

    static String render(AnnotatedType annotated, Set<String> imports) {
        if (annotated instanceof AnnotatedParameterizedType parameterized
                && parameterized.getType() instanceof ParameterizedType type) {
            String raw = render((Class<?>) type.getRawType(), imports);
            return raw + "<"
                    + Arrays.stream(parameterized.getAnnotatedActualTypeArguments())
                            .map(argument -> render(argument, imports))
                            .collect(Collectors.joining(", "))
                    + ">";
        }
        if (annotated.getType() instanceof Class<?> type) {
            return render(type, imports);
        }
        throw new ModuleGenerator.GeneratorException("unsupported type " + annotated.getType());
    }

    private static String render(Class<?> type, Set<String> imports) {
        if (type.isArray()) {
            return render(type.getComponentType(), imports) + "[]";
        }
        if (!type.isPrimitive() && !type.getPackageName().equals("java.lang")) {
            imports.add(type.getCanonicalName());
        }
        return type.getSimpleName();
    }
}
