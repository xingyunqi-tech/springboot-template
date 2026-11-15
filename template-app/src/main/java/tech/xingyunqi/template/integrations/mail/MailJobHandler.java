package tech.xingyunqi.template.integrations.mail;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.mail.MailTransport;
import tech.xingyunqi.template.infrastructure.queue.JobQueue;

/** 按任务记录的语言渲染模板并发送；失败由队列重试。 */
@Component
public class MailJobHandler implements JobRequestHandler<MailJob> {

    private static final Logger LOG = LoggerFactory.getLogger(MailJobHandler.class);

    private final MailComposer composer;
    private final MailTransport transport;

    public MailJobHandler(MailComposer composer, MailTransport transport) {
        this.composer = composer;
        this.transport = transport;
    }

    @Override
    @Job(name = "send-mail", labels = JobQueue.Names.MAIL)
    public void run(MailJob job) {
        LOG.info("Processing mail {} to {}", job.template(), MailTransport.mask(job.to()));
        transport.send(composer.compose(job.template(), job.to(), job.data(), job.lang()));
    }
}
