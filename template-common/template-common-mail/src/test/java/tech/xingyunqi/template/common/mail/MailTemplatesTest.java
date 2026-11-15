package tech.xingyunqi.template.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MailTemplatesTest {

    final MailTemplates templates = new MailTemplates();

    @Test
    @DisplayName("{{x}} 的转义与 handlebars.js 相同，{{{x}}} 不转义")
    void escapesLikeHandlebarsJs() {
        assertThat(templates.render("probe", Map.of("value", "a=b&c<d>'\"`")))
                .isEqualTo("a&#x3D;b&amp;c&lt;d&gt;&#x27;&quot;&#x60;|a=b&c<d>'\"`");
    }

    @Test
    @DisplayName("模板变量缺失时抛错")
    void missingVariableFails() {
        assertThatThrownBy(() -> templates.render("probe", Map.of())).hasMessageContaining("value");
    }

    @Test
    @DisplayName("收件人脱敏保留首字符与域名")
    void masksAddress() {
        assertThat(MailTransport.mask("john@example.com")).isEqualTo("j***@example.com");
        assertThat(MailTransport.mask("invalid")).isEqualTo("***");
    }
}
