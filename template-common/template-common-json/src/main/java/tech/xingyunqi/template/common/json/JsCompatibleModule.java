package tech.xingyunqi.template.common.json;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/** 与 {@code JSON.stringify} 一致的数字与时间输出。 */
public final class JsCompatibleModule extends SimpleModule {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public JsCompatibleModule() {
        super("js-compatible");
        addSerializer(Double.class, new DoubleSerializer());
        addSerializer(double.class, new DoubleSerializer());
        addSerializer(Float.class, new FloatSerializer());
        addSerializer(float.class, new FloatSerializer());
        addSerializer(Instant.class, new InstantSerializer());
    }

    /** {@code Date.prototype.toISOString}：UTC、毫秒、以 Z 结尾。 */
    public static String isoTimestamp(Instant instant) {
        return ISO_MILLIS.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    static void writeNumber(double value, JsonGenerator gen) {
        if (!JsNumbers.isFinite(value)) {
            gen.writeNull();
        } else {
            gen.writeNumber(JsNumbers.toString(value));
        }
    }

    static final class DoubleSerializer extends ValueSerializer<Double> {
        @Override
        public void serialize(Double value, JsonGenerator gen, SerializationContext ctxt) {
            writeNumber(value, gen);
        }
    }

    static final class FloatSerializer extends ValueSerializer<Float> {
        @Override
        public void serialize(Float value, JsonGenerator gen, SerializationContext ctxt) {
            writeNumber(Double.parseDouble(Float.toString(value)), gen);
        }
    }

    static final class InstantSerializer extends ValueSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(isoTimestamp(value));
        }
    }
}
