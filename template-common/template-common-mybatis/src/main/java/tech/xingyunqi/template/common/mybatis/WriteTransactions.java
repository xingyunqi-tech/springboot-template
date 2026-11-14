package tech.xingyunqi.template.common.mybatis;

import java.util.function.Supplier;

/** 以 {@link WriteTransaction} 语义执行一段代码，用于事务前需要先做耗时准备（如密码哈希）的写操作。 */
public class WriteTransactions {

    @WriteTransaction
    public <T> T run(Supplier<T> work) {
        return work.get();
    }

    @WriteTransaction
    public void run(Runnable work) {
        work.run();
    }
}
