package tech.xingyunqi.template.common.web.bind;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import tech.xingyunqi.template.common.web.validation.ClassValidatorEngine;
import tech.xingyunqi.template.common.web.validation.ParamType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 查询参数按 nestjs 查询 DTO 复刻 ValidationPipe；参数对象同 Express 5 {@code simple} 解析（重复键为数组）。 */
public class ValidatedQuery {

    private final ClassValidatorEngine engine;
    private final JsonMapper mapper;

    public ValidatedQuery(ClassValidatorEngine engine, JsonMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    public JsonNode validate(String dto, HttpServletRequest request) {
        ObjectNode query = mapper.createObjectNode();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String[] values = entry.getValue();
            if (values.length == 1) {
                query.put(entry.getKey(), values[0]);
            } else {
                ArrayNode array = query.putArray(entry.getKey());
                for (String value : values) {
                    array.add(value);
                }
            }
        }
        return engine.run(dto, query, ParamType.QUERY);
    }
}
