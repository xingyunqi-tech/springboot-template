package tech.xingyunqi.template.common.oss;

import java.util.Arrays;

/** {@code FILE_DRIVER}。 */
public enum FileDriver {
    LOCAL("local"),
    S3("s3"),
    S3_PRESIGNED("s3-presigned");

    private final String value;

    FileDriver(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isS3() {
        return this != LOCAL;
    }

    public static FileDriver parse(String value) {
        return Arrays.stream(values())
                .filter(driver -> driver.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "FILE_DRIVER must be one of local, s3, s3-presigned; got \"" + value + "\""));
    }
}
