package tech.xingyunqi.template.integrations.mail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.mail.MailTransport;
import tech.xingyunqi.template.common.web.i18n.RequestLanguage;

/** 入 {@code mail} 队列，由 worker 渲染并发送；入队失败只记日志，不影响接口响应。 */
@Component
class QueueMailer implements Mailer {

    private static final Logger LOG = LoggerFactory.getLogger(QueueMailer.class);

    private final JobRequestScheduler jobs;

    QueueMailer(JobRequestScheduler jobs) {
        this.jobs = jobs;
    }

    @Override
    public void send(MailMessage message) {
        Map<String, String> data = new LinkedHashMap<>();
        message.data().forEach((key, value) -> data.put(key, String.valueOf(value)));
        MailJob job = new MailJob(message.template(), message.to(), data, RequestLanguage.current());
        String to = MailTransport.mask(message.to());
        try {
            jobs.enqueue(job);
        } catch (RuntimeException e) {
            LOG.error("Failed to enqueue mail to {}", to, e);
            return;
        }
        LOG.info("Mail job added to queue: {}", to);
    }
}
