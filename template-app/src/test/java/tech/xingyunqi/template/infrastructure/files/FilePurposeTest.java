package tech.xingyunqi.template.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FilePurposeTest {

    @Test
    @DisplayName("未传或空串按 image 处理，大小写不同、数组与未知值不合法")
    void parse() {
        assertThat(FilePurpose.parse(null)).isEqualTo(FilePurpose.IMAGE);
        assertThat(FilePurpose.parse("")).isEqualTo(FilePurpose.IMAGE);
        assertThat(FilePurpose.parse("audio")).isEqualTo(FilePurpose.AUDIO);
        assertThat(FilePurpose.parse("AUDIO")).isNull();
        assertThat(FilePurpose.parse(List.of("image"))).isNull();
    }

    @Test
    @DisplayName("mime 只按最后一个扩展名（小写）查用途表")
    void mimeTypes() {
        assertThat(FilePurpose.extension("a.tar.JPEG")).isEqualTo("jpeg");
        assertThat(FilePurpose.extension("png")).isEmpty();
        assertThat(FilePurpose.IMAGE.mimeType("x.GIF")).isEqualTo("image/gif");
        assertThat(FilePurpose.PRIVATE_IMAGE.mimeType("x.gif")).isNull();
        assertThat(FilePurpose.AUDIO.mimeType("x.m4a")).isEqualTo("audio/mp4");
    }

    @Test
    @DisplayName("内容素材无归属用户，其余用途归上传者")
    void owners() {
        assertThat(FilePurpose.CONTENT_IMAGE.owner(7)).isNull();
        assertThat(FilePurpose.AUDIO.owner(7)).isNull();
        assertThat(FilePurpose.IMPORT.owner(7)).isEqualTo(7);
        assertThat(FilePurpose.IMAGE.permission()).isNull();
        assertThat(FilePurpose.EXPORT.permission()).isEqualTo("content:manage");
    }

    @Test
    @DisplayName("大小上限默认值与 nestjs 相同，单文件上限取最大值；非正整数启动失败")
    void limits() {
        UploadLimits defaults = new UploadLimits(new FilesProperties(null, null, null, null, "", null));
        Map<FilePurpose, Long> expected = Map.of(
                FilePurpose.IMAGE, 5L << 20,
                FilePurpose.PRIVATE_IMAGE, 10L << 20,
                FilePurpose.CONTENT_IMAGE, 5L << 20,
                FilePurpose.AUDIO, 20L << 20,
                FilePurpose.IMPORT, 50L << 20,
                FilePurpose.EXPORT, 20L << 20);
        expected.forEach(
                (purpose, size) -> assertThat(defaults.maxSize(purpose)).isEqualTo(size));
        assertThat(defaults.maxFileSize()).isEqualTo(50L << 20);
        assertThatThrownBy(() -> new FilesProperties("0", null, null, null, null, null).maxSizes())
                .hasMessageContaining("FILE_IMAGE_MAX_SIZE");
        assertThatThrownBy(() -> new FilesProperties(null, null, null, "1.5", null, null).maxSizes())
                .hasMessageContaining("FILE_AUDIO_MAX_SIZE");
    }
}
