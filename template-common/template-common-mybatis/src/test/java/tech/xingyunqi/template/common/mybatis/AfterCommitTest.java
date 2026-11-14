package tech.xingyunqi.template.common.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

class AfterCommitTest {

    /** 只记录提交 / 回滚的事务管理器。 */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final List<String> outcomes = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            outcomes.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            outcomes.add("rollback");
        }
    }

    final RecordingTransactionManager manager = new RecordingTransactionManager();
    final List<String> events = new ArrayList<>();

    WriteTransactions proxy() {
        ProxyFactory factory = new ProxyFactory(new WriteTransactions());
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        factory.addAdvice(new WriteTransactionInterceptor());
        return (WriteTransactions) factory.getProxy();
    }

    @Test
    @DisplayName("成功时提交，并在提交后执行全部回调")
    void runsCallbacksAfterCommit() {
        proxy().run(() -> {
            AfterCommit.run(() -> events.add("mail"));
            AfterCommit.always(() -> events.add("cache"));
            events.add("work");
        });
        assertThat(manager.outcomes).containsExactly("commit");
        assertThat(events).containsExactly("work", "cache", "mail");
    }

    @Test
    @DisplayName("4xx 时提交，只执行 always 登记的回调")
    void discardsSideEffectsOn4xx() {
        assertThatThrownBy(() -> proxy().run(() -> {
                    AfterCommit.run(() -> events.add("mail"));
                    AfterCommit.always(() -> events.add("cache"));
                    throw HttpStatusException.unauthorized(null);
                }))
                .isInstanceOf(HttpStatusException.class);
        assertThat(manager.outcomes).containsExactly("commit");
        assertThat(events).containsExactly("cache");
    }

    @Test
    @DisplayName("其它异常回滚，不执行回调")
    void rollsBackOnOtherErrors() {
        assertThatThrownBy(() -> proxy().run(() -> {
                    AfterCommit.always(() -> events.add("cache"));
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.outcomes).containsExactly("rollback");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("没有活动事务时立即执行")
    void runsImmediatelyWithoutTransaction() {
        AfterCommit.run(() -> events.add("now"));
        assertThat(events).containsExactly("now");
    }
}
