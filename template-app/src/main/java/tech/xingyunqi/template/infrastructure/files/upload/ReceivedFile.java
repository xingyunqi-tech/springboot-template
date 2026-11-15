package tech.xingyunqi.template.infrastructure.files.upload;

/** multipart 中字段名为 {@code file} 的文件：客户端文件名（已去掉目录部分）、字节数与存储目标。 */
public record ReceivedFile(String originalName, long size, FileSink sink) {}
