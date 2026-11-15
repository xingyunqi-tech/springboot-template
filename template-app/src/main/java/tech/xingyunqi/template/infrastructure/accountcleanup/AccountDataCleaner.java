package tech.xingyunqi.template.infrastructure.accountcleanup;

/** 注销账号后清理某一类用户数据（nestjs {@code @AccountCleaner}）。实现为 Spring bean 即被登记，按 {@link #order()} 升序执行； {@link #clean} 须幂等。 */
public interface AccountDataCleaner {

    String name();

    int order();

    void clean(int userId) throws Exception;
}
