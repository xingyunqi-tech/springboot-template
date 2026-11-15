package tech.xingyunqi.template.infrastructure.queue;

/** 任务让出 worker：{@link QueueJobFilter} 在 {@link QueueJobFilter#YIELD_DELAY} 后重跑，不计入失败次数。 */
public final class JobYieldException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobYieldException(String message) {
        super(message, null, false, false);
    }
}
