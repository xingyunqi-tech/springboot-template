package tech.xingyunqi.template.common.json;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class JsonAutoConfiguration {

    @Bean
    JsCompatibleModule jsCompatibleModule() {
        return new JsCompatibleModule();
    }
}
