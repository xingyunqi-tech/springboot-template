package tech.xingyunqi.template.common.mybatis;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 事务提交成功后执行的回调；无活动事务时立即执行。{@link #run} 登记的副作用（发邮件、投递任务）在 4xx 提交路径上被丢弃，{@link #always} 登记的回调（清缓存）只要提交就执行。 */
public final class AfterCommit {

    private static final Logger LOG = LoggerFactory.getLogger(AfterCommit.class);
    private static final Object KEY = AfterCommit.class;

    private AfterCommit() {}

    public static void run(Runnable callback) {
        Pending pending = pending();
        if (pending == null) {
            invoke(callback);
        } else {
            pending.callbacks.add(callback);
        }
    }

    public static void always(Runnable callback) {
        Pending pending = pending();
        if (pending == null) {
            invoke(callback);
        } else {
            pending.always.add(callback);
        }
    }

    static void discardPending() {
        if (TransactionSynchronizationManager.getResource(KEY) instanceof Pending pending) {
            pending.callbacks.clear();
        }
    }

    private static @Nullable Pending pending() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(KEY);
        if (pending == null) {
            pending = new Pending();
            TransactionSynchronizationManager.bindResource(KEY, pending);
            TransactionSynchronizationManager.registerSynchronization(pending);
        }
        return pending;
    }

    private static void invoke(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOG.error("after-commit callback failed", e);
        }
    }

    private static final class Pending implements TransactionSynchronization {

        private final List<Runnable> callbacks = new ArrayList<>();
        private final List<Runnable> always = new ArrayList<>();

        @Override
        public void afterCommit() {
            List<Runnable> snapshot = new ArrayList<>(always);
            snapshot.addAll(callbacks);
            always.clear();
            callbacks.clear();
            snapshot.forEach(AfterCommit::invoke);
        }

        @Override
        public void afterCompletion(int status) {
            if (TransactionSynchronizationManager.hasResource(KEY)) {
                TransactionSynchronizationManager.unbindResource(KEY);
            }
        }
    }
}
