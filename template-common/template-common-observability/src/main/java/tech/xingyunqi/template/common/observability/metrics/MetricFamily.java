package tech.xingyunqi.template.common.observability.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/** 一个指标族（counter / gauge / histogram），按 prom-client 的格式输出，标签组合按首次出现的顺序排列。 */
abstract sealed class MetricFamily {

    final String name;
    final String help;
    final List<String> labelNames;

    MetricFamily(String name, String help, String... labelNames) {
        this.name = name;
        this.help = help;
        this.labelNames = List.of(labelNames);
    }

    abstract String type();

    abstract void writeSamples(StringBuilder out, String app);

    final void write(StringBuilder out, String app) {
        out.append("# HELP ")
                .append(name)
                .append(' ')
                .append(PromText.escapeHelp(help))
                .append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type());
        writeSamples(out, app);
    }

    final List<String> key(String... values) {
        if (values.length != labelNames.size()) {
            throw new IllegalArgumentException(name + " expects labels " + labelNames);
        }
        return List.of(values);
    }

    static <V> List<Map.Entry<List<String>, V>> snapshot(Map<List<String>, V> series) {
        synchronized (series) {
            return new ArrayList<>(series.entrySet());
        }
    }

    static <V> V series(Map<List<String>, V> series, List<String> key, java.util.function.Supplier<V> create) {
        synchronized (series) {
            return series.computeIfAbsent(key, ignored -> create.get());
        }
    }

    static List<String> withApp(List<String> names) {
        List<String> all = new ArrayList<>(names);
        all.add("app");
        return all;
    }

    static List<String> withApp(List<String> values, String app) {
        List<String> all = new ArrayList<>(values);
        all.add(app);
        return all;
    }

    static final class Counter extends MetricFamily {

        private final Map<List<String>, DoubleAdder> values = Collections.synchronizedMap(new LinkedHashMap<>());

        Counter(String name, String help, String... labelNames) {
            super(name, help, labelNames);
            if (labelNames.length == 0) {
                values.put(List.of(), new DoubleAdder());
            }
        }

        @Override
        String type() {
            return "counter";
        }

        void inc(String... labels) {
            series(values, key(labels), DoubleAdder::new).add(1);
        }

        double value(String... labels) {
            DoubleAdder adder = values.get(key(labels));
            return adder == null ? 0 : adder.sum();
        }

        @Override
        void writeSamples(StringBuilder out, String app) {
            for (Map.Entry<List<String>, DoubleAdder> entry : snapshot(values)) {
                out.append('\n').append(name);
                PromText.labels(out, withApp(labelNames), withApp(entry.getKey(), app));
                out.append(' ').append(PromText.number(entry.getValue().sum()));
            }
        }
    }

    static final class Gauge extends MetricFamily {

        private final LongAdder value = new LongAdder();

        Gauge(String name, String help) {
            super(name, help);
        }

        @Override
        String type() {
            return "gauge";
        }

        void inc() {
            value.increment();
        }

        void dec() {
            value.decrement();
        }

        double value() {
            return value.sum();
        }

        @Override
        void writeSamples(StringBuilder out, String app) {
            out.append('\n').append(name);
            PromText.labels(out, List.of("app"), List.of(app));
            out.append(' ').append(PromText.number(value.sum()));
        }
    }

    static final class Histogram extends MetricFamily {

        private final double[] buckets;
        private final Map<List<String>, Series> values = Collections.synchronizedMap(new LinkedHashMap<>());

        Histogram(String name, String help, double[] buckets, String... labelNames) {
            super(name, help, labelNames);
            this.buckets = buckets.clone();
        }

        @Override
        String type() {
            return "histogram";
        }

        void observe(double seconds, String... labels) {
            series(values, key(labels), () -> new Series(buckets.length)).observe(buckets, seconds);
        }

        @Nullable
        Series series(String... labels) {
            return values.get(key(labels));
        }

        @Override
        void writeSamples(StringBuilder out, String app) {
            List<String> bucketNames = new ArrayList<>();
            bucketNames.add("le");
            bucketNames.add("app");
            bucketNames.addAll(labelNames);
            List<String> appFirst = new ArrayList<>();
            appFirst.add("app");
            appFirst.addAll(labelNames);
            for (Map.Entry<List<String>, Series> entry : snapshot(values)) {
                Series series = entry.getValue();
                long[] counts = series.counts();
                long cumulative = 0;
                for (int i = 0; i <= buckets.length; i++) {
                    cumulative += counts[i];
                    List<String> bucketValues = new ArrayList<>();
                    bucketValues.add(i < buckets.length ? PromText.number(buckets[i]) : "+Inf");
                    bucketValues.add(app);
                    bucketValues.addAll(entry.getKey());
                    out.append('\n').append(name).append("_bucket");
                    PromText.labels(out, bucketNames, bucketValues);
                    out.append(' ').append(cumulative);
                }
                List<String> appValues = new ArrayList<>();
                appValues.add(app);
                appValues.addAll(entry.getKey());
                out.append('\n').append(name).append("_sum");
                PromText.labels(out, appFirst, appValues);
                out.append(' ').append(PromText.number(series.sum.sum()));
                out.append('\n').append(name).append("_count");
                PromText.labels(out, appFirst, appValues);
                out.append(' ').append(cumulative);
            }
        }

        static final class Series {

            private final LongAdder[] counts;
            private final DoubleAdder sum = new DoubleAdder();

            Series(int buckets) {
                counts = new LongAdder[buckets + 1];
                Arrays.setAll(counts, ignored -> new LongAdder());
            }

            void observe(double[] buckets, double value) {
                int index = 0;
                while (index < buckets.length && value > buckets[index]) {
                    index++;
                }
                counts[index].increment();
                sum.add(value);
            }

            long[] counts() {
                long[] snapshot = new long[counts.length];
                for (int i = 0; i < counts.length; i++) {
                    snapshot[i] = counts[i].sum();
                }
                return snapshot;
            }

            long count() {
                long total = 0;
                for (LongAdder count : counts) {
                    total += count.sum();
                }
                return total;
            }

            double sum() {
                return sum.sum();
            }
        }
    }
}
