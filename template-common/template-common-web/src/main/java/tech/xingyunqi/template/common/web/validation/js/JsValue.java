package tech.xingyunqi.template.common.web.validation.js;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** JavaScript 运行时值。 */
public sealed interface JsValue
        permits JsValue.Undefined,
                JsValue.Null,
                JsValue.Bool,
                JsValue.Num,
                JsValue.Str,
                JsValue.Array,
                JsValue.Obj,
                JsValue.Function,
                JsDate {

    Undefined UNDEFINED = Undefined.INSTANCE;
    Null NULL = Null.INSTANCE;
    Bool TRUE = new Bool(true);
    Bool FALSE = new Bool(false);

    static Bool bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    static Num num(double value) {
        return new Num(value);
    }

    static Str str(String value) {
        return new Str(value);
    }

    enum Undefined implements JsValue {
        INSTANCE
    }

    enum Null implements JsValue {
        INSTANCE
    }

    record Bool(boolean value) implements JsValue {}

    record Num(double value) implements JsValue {}

    record Str(String value) implements JsValue {}

    /** JS 数组；按引用比较。 */
    final class Array implements JsValue {
        private final List<JsValue> items;

        public Array(List<JsValue> items) {
            this.items = new ArrayList<>(items);
        }

        public Array() {
            this.items = new ArrayList<>();
        }

        public List<JsValue> items() {
            return items;
        }
    }

    /** JS 对象；{@code cls} 为 class-transformer 实例的类名，普通对象为 null。 */
    sealed class Obj implements JsValue permits ArrayInstance {
        private final Map<String, JsValue> props = new LinkedHashMap<>();
        private final @Nullable String cls;

        public Obj(@Nullable String cls) {
            this.cls = cls;
        }

        public Obj() {
            this(null);
        }

        public @Nullable String cls() {
            return cls;
        }

        public Map<String, JsValue> props() {
            return props;
        }

        public JsValue get(String key) {
            JsValue value = props.get(key);
            return value == null ? UNDEFINED : value;
        }

        public JsValue read(String key) {
            return get(key);
        }
    }

    /** constructor 被改写为 DTO 类的数组：索引键可被白名单删除，length 与原型方法仍可读取。 */
    final class ArrayInstance extends Obj {
        private static final Set<String> PROTOTYPE_METHODS = Set.of(
                "at",
                "concat",
                "copyWithin",
                "entries",
                "every",
                "fill",
                "filter",
                "find",
                "findIndex",
                "findLast",
                "findLastIndex",
                "flat",
                "flatMap",
                "forEach",
                "includes",
                "indexOf",
                "join",
                "keys",
                "lastIndexOf",
                "map",
                "pop",
                "push",
                "reduce",
                "reduceRight",
                "reverse",
                "shift",
                "slice",
                "some",
                "sort",
                "splice",
                "toLocaleString",
                "toReversed",
                "toSorted",
                "toSpliced",
                "toString",
                "unshift",
                "values",
                "with",
                "constructor",
                "hasOwnProperty",
                "isPrototypeOf",
                "propertyIsEnumerable",
                "valueOf");

        private final int length;

        public ArrayInstance(String cls, List<JsValue> items) {
            super(cls);
            this.length = items.size();
            for (int i = 0; i < items.size(); i++) {
                props().put(Integer.toString(i), items.get(i));
            }
        }

        public int length() {
            return length;
        }

        @Override
        public JsValue read(String key) {
            JsValue value = props().get(key);
            if (value != null) {
                return value;
            }
            if (key.equals("length")) {
                return num(length);
            }
            if (PROTOTYPE_METHODS.contains(key)) {
                return new Function(key);
            }
            return UNDEFINED;
        }
    }

    /** 内置函数值，只参与类型判断与真值判断；按引用比较。 */
    final class Function implements JsValue {
        private final String name;

        public Function(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }
}
