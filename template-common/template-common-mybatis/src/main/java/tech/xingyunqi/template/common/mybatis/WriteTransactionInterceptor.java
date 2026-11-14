package tech.xingyunqi.template.common.mybatis;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** 在事务内层运行：最外层 {@link WriteTransaction} 方法抛出 {@link HttpStatusException} 时丢弃已登记的提交后副作用。 */
final class WriteTransactionInterceptor implements MethodInterceptor {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            return invocation.proceed();
        } catch (HttpStatusException e) {
            if (depth[0] == 1) {
                AfterCommit.discardPending();
            }
            throw e;
        } finally {
            depth[0]--;
            if (depth[0] == 0) {
                DEPTH.remove();
            }
        }
    }
}
