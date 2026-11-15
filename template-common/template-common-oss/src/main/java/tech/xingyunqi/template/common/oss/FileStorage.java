package tech.xingyunqi.template.common.oss;

import java.io.IOException;
import java.nio.file.Path;

/** 按 {@code FILE_DRIVER} 存取文件；{@code path} 为 file 表中的值（local 为 API 路径，S3 为对象键）。 */
public interface FileStorage {

    long SIGNED_URL_EXPIRES_IN_SECONDS = 3600;

    FileDriver driver();

    /** local 驱动的存储目录。 */
    Path localRoot();

    /** local 驱动中 {@code path} 对应的磁盘文件（只取最后一段）。 */
    Path localFile(String path);

    /** 把临时文件上传为对象。 */
    void put(String key, Path source, String contentType) throws IOException;

    /** local 驱动文件不存在时忽略；S3 删除失败时抛出。 */
    void delete(String path) throws IOException;

    /** 3600 秒的预签名 GET URL。 */
    String presignedGetUrl(String key);

    /** 3600 秒的预签名 PUT URL，签入 {@code Content-Length}。 */
    String presignedPutUrl(String key, long contentLength);
}
