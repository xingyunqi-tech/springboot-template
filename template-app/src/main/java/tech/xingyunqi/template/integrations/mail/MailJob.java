package tech.xingyunqi.template.integrations.mail;

import java.util.Map;
import org.jobrunr.jobs.lambdas.JobRequest;
import org.jspecify.annotations.Nullable;

/** {@code mail} 队列的 {@code send-mail} 任务参数：模板名、收件人、模板数据与请求语言。 */
public record MailJob(
        String template,
        String to,
        Map<String, String> data,
        @Nullable String lang) implements JobRequest {

    @Override
    public Class<MailJobHandler> getJobRequestHandler() {
        return MailJobHandler.class;
    }
}
