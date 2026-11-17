import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * -Pci 门禁：JaCoCo 聚合报告的行 / 分支覆盖率阈值，surefire（单元）与 failsafe（集成）实际执行用例数下限（{@code ci/test-floor.properties}）。
 *
 * <p>用法：{@code java ci/CiGate.java <仓库根目录> <jacoco.xml> <行覆盖率下限> <分支覆盖率下限>}
 */
public final class CiGate {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path jacoco = Path.of(args[1]);
        double lineMin = Double.parseDouble(args[2]);
        double branchMin = Double.parseDouble(args[3]);
        DocumentBuilder xml = parser();
        List<String> failures = new ArrayList<>();

        Element report = xml.parse(jacoco.toFile()).getDocumentElement();
        for (Node node = report.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element counter && counter.getTagName().equals("counter")) {
                String type = counter.getAttribute("type");
                double min = type.equals("LINE") ? lineMin : type.equals("BRANCH") ? branchMin : -1;
                if (min < 0) {
                    continue;
                }
                long covered = Long.parseLong(counter.getAttribute("covered"));
                long total = covered + Long.parseLong(counter.getAttribute("missed"));
                double ratio = total == 0 ? 1 : (double) covered / total;
                System.out.printf("[ci] %s 覆盖率 %.2f%%（%d / %d），下限 %.0f%%%n", type, ratio * 100, covered, total, min * 100);
                if (ratio < min) {
                    failures.add(type + " 覆盖率低于下限");
                }
            }
        }

        Properties floor = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("ci/test-floor.properties"))) {
            floor.load(in);
        }
        check(xml, root, "surefire-reports", "unit", floor, failures);
        check(xml, root, "failsafe-reports", "integration", floor, failures);

        if (!failures.isEmpty()) {
            failures.forEach(f -> System.err.println("[ci] " + f));
            System.exit(1);
        }
    }

    static void check(DocumentBuilder xml, Path root, String dir, String key, Properties floor, List<String> failures)
            throws Exception {
        long min = Long.parseLong(floor.getProperty(key).strip());
        long executed = 0;
        for (Path file : reports(root, dir)) {
            Element suite = xml.parse(file.toFile()).getDocumentElement();
            executed += Long.parseLong(suite.getAttribute("tests")) - Long.parseLong(suite.getAttribute("skipped"));
        }
        System.out.printf("[ci] %s 测试 %d 个，下限 %d%n", key, executed, min);
        if (executed < min) {
            failures.add(key + " 测试数 " + executed + " 低于下限 " + min);
        }
    }

    static List<Path> reports(Path root, String dir) throws IOException {
        try (Stream<Path> paths = Files.walk(root, 5)) {
            return paths.filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString().equals(dir)
                            && p.getParent().getParent().getFileName().toString().equals("target")
                            && !root.relativize(p).startsWith("tests")
                            && p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .toList();
        }
    }

    static DocumentBuilder parser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder();
    }
}
