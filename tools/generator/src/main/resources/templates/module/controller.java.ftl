package ${package}.web;

<#list imports as import>
import ${import};
</#list>
import ${package}.${type}Service;

@RestController
<#if classAuth?has_content>
@${classAuth}
</#if>
class ${type}Controller implements ${api} {

    final ${type}Service service;

    ${type}Controller(${type}Service service) {
        this.service = service;
    }
<#list methods as method>

    @Override
<#if method.auth?has_content>
    @${method.auth}
</#if>
    public ${method.returnType} ${method.name}(${method.parameters}) {
        throw notImplemented();
    }
</#list>

    private static HttpStatusException notImplemented() {
        return HttpStatusException.of(501, null);
    }
}
