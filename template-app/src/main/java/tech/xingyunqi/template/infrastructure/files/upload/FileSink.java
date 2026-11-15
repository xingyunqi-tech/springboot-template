package tech.xingyunqi.template.infrastructure.files.upload;

import java.io.IOException;

/** 接收中的单个文件：以随机键命名，接收完成后提交，失败或后续校验不通过时丢弃。 */
public interface FileSink {

    String key();

    void write(byte[] buffer, int offset, int length) throws IOException;

    void commit() throws IOException;

    /** 删除已写入或已提交的内容，失败时只记日志。 */
    void discard();
}
