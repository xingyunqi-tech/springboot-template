package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.JobState;
import org.jobrunr.jobs.states.ScheduledState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.ConcurrentJobModificationException;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.common.mail.MailTransport;
import tech.xingyunqi.template.common.mail.OutgoingMail;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.common.redis.LockService;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupProducer;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;
import tech.xingyunqi.template.infrastructure.queue.QueueJobFilter;
import tech.xingyunqi.template.integrations.mail.MailMessage;
import tech.xingyunqi.template.integrations.mail.Mailer;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

/** api 进程内运行 JobRunr background server（轮询 5 秒），邮件发到进程内 GreenMail。 */
@SpringBootTest(
        properties = {
            "jobrunr.background-job-server.enabled=true",
            "app.name=Tom's <App> & Co",
            "app.frontend-domain=https://app.example.com",
            "app.mail.host=127.0.0.1",
            "app.mail.default-email=noreply@example.com",
            "app.mail.default-name=Api",
            "app.mail.ignore-tls=true"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, BackgroundJobsIT.Cleaners.class})
class BackgroundJobsIT {

    static final Duration TIMEOUT = Duration.ofSeconds(40);
    static final GreenMail SMTP = startSmtp();
    static final List<Integer> PROBED = Collections.synchronizedList(new ArrayList<>());
    static final List<Integer> FAILING = Collections.synchronizedList(new ArrayList<>());
    static final List<Integer> LOCK_HELD = Collections.synchronizedList(new ArrayList<>());
    static final List<Integer> SLOW = Collections.synchronizedList(new ArrayList<>());
    static final Duration SLOW_CLEANUP = Duration.ofSeconds(2);

    static GreenMail startSmtp() {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP).dynamicPort());
        smtp.start();
        return smtp;
    }

    @DynamicPropertySource
    static void smtpPort(DynamicPropertyRegistry registry) {
        registry.add("app.mail.port", () -> SMTP.getSmtp().getPort());
    }

    @AfterAll
    static void stopSmtp() {
        SMTP.stop();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Cleaners {

        @Bean
        AccountDataCleaner probeCleaner(LockService locks) {
            return new AccountDataCleaner() {
                @Override
                public String name() {
                    return "files";
                }

                @Override
                public int order() {
                    return 1000;
                }

                @Override
                public void clean(int userId) {
                    if (SLOW.contains(userId)) {
                        sleep(SLOW_CLEANUP);
                    }
                    if (locks.tryAcquire("account-cleanup:user-" + userId, 1000) == null) {
                        LOCK_HELD.add(userId);
                    }
                    if (FAILING.contains(userId)) {
                        throw new IllegalStateException("s3 down");
                    }
                    PROBED.add(userId);
                }
            };
        }
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StorageProvider storage;

    @Autowired
    AccountCleanupProducer producer;

    @Autowired
    Mailer mailer;

    @Autowired
    AppMetrics metrics;

    @Autowired
    LockService locks;

    @MockitoSpyBean
    MailTransport transport;

    static void await(BooleanSupplier condition) throws InterruptedException {
        await(condition, TIMEOUT);
    }

    static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime())
                    .as("condition not met within %s", timeout)
                    .isLessThan(deadline);
            Thread.sleep(200);
        }
    }

    static List<MimeMessage> received(String to) {
        return Arrays.stream(SMTP.getReceivedMessages())
                .filter(message -> {
                    try {
                        Address[] recipients = message.getAllRecipients();
                        return recipients != null && recipients[0].toString().equals(to);
                    } catch (jakarta.mail.MessagingException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    int newUser() {
        return jdbc.queryForObject(
                "insert into \"user\" (email, provider, \"roleId\", \"statusId\") values (?, 'email', 2, 1) returning id",
                Integer.class,
                "b-" + UUID.randomUUID() + "@example.com");
    }

    Job job(UUID id) {
        return storage.getJobById(id);
    }

    StateName state(UUID id) {
        try {
            return job(id).getState();
        } catch (JobNotFoundException e) {
            return null;
        }
    }

    /** 失败后安排的每次重试相对失败时刻的等待时间。 */
    static List<Duration> retryDelays(Job job) {
        List<Duration> delays = new ArrayList<>();
        List<JobState> states = job.getJobStates();
        for (int i = 1; i < states.size(); i++) {
            if (states.get(i - 1) instanceof FailedState failed && states.get(i) instanceof ScheduledState scheduled) {
                delays.add(Duration.between(failed.getCreatedAt(), scheduled.getScheduledAt()));
            }
        }
        return delays;
    }

    @Test
    @DisplayName("注册后提交入 mail 队列，worker 按请求语言渲染并经 SMTP 发送")
    void registrationMailIsDelivered() throws Exception {
        String email = "m-" + UUID.randomUUID() + "@example.com";
        double completed = metrics.value("queue_jobs_total", "mail", "completed");
        int status = mvc.perform(post("/api/v1/auth/email/register")
                        .header("Accept-Language", "zh")
                        .contentType("application/json")
                        .content(AuthApi.JSON.writeValueAsString(Map.of(
                                "email", email, "password", AuthApi.PASSWORD, "firstName", "A", "lastName", "B"))))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).isEqualTo(200);

        await(() -> received(email).size() == 1);
        MimeMessage message = received(email).getFirst();
        assertThat(message.getSubject()).isEqualTo("确认电子邮件");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getPersonal()).isEqualTo("Api");
        assertThat(from.getAddress()).isEqualTo("noreply@example.com");
        MimeMultipart content = (MimeMultipart) message.getContent();
        assertThat((String) content.getBodyPart(0).getContent())
                .startsWith("https://app.example.com/confirm-email?hash=")
                .endsWith(" 确认电子邮件");
        assertThat((String) content.getBodyPart(1).getContent())
                .contains("Tom&#x27;s &lt;App&gt; &amp; Co")
                .contains("嗨！");
        await(() -> metrics.value("queue_jobs_total", "mail", "completed") >= completed + 1);
    }

    @Test
    @DisplayName("SMTP 失败时按 1s、2s 退避重试，第 3 次成功后计 completed")
    void mailRetriesWithBackoff() throws Exception {
        String to = "retry-" + UUID.randomUUID() + "@example.com";
        AtomicInteger failures = new AtomicInteger(2);
        doAnswer(invocation -> {
                    OutgoingMail mail = invocation.getArgument(0);
                    if (mail.to().equals(to) && failures.getAndDecrement() > 0) {
                        throw new IllegalStateException("smtp down");
                    }
                    return invocation.callRealMethod();
                })
                .when(transport)
                .send(any());

        mailer.send(new MailMessage("activation", to, Map.of("hash", "h")));

        await(() -> received(to).size() == 1);
        UUID id = UUID.fromString(jdbc.queryForObject(
                "select id from jobrunr.jobrunr_jobs where jobasjson like ?", String.class, "%" + to + "%"));
        await(() -> state(id) == StateName.SUCCEEDED);
        Job job = job(id);
        assertThat(job.getJobName()).isEqualTo("send-mail");
        assertThat(job.getJobStatesOfType(FailedState.class).count()).isEqualTo(2);
        assertThat(retryDelays(job)).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("账号清理任务持用户锁执行全部清理器并写 account_cleanup")
    void accountCleanupRuns() throws Exception {
        int userId = newUser();
        jdbc.update("update \"user\" set \"deletedAt\" = now() where id = ?", userId);
        double completed = metrics.value("queue_jobs_total", "account-cleanup", "completed");

        producer.enqueueCleanup(userId);

        await(() -> state(AccountCleanupProducer.jobId(userId)) == StateName.SUCCEEDED);
        assertThat(PROBED).contains(userId);
        assertThat(LOCK_HELD).contains(userId);
        assertThat(jdbc.queryForObject(
                        "select count(*) from account_cleanup where \"userId\" = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(locks.tryAcquire("account-cleanup:user-" + userId, 1000)).isNotNull();
        assertThat(metrics.value("queue_jobs_total", "account-cleanup", "completed"))
                .isGreaterThanOrEqualTo(completed + 1);
    }

    @Test
    @DisplayName("其它 worker 持有用户锁时跳过本次清理")
    void accountCleanupSkipsLockedUser() throws Exception {
        int userId = newUser();
        String token = locks.tryAcquire("account-cleanup:user-" + userId, 60_000);
        assertThat(token).isNotNull();
        try {
            producer.enqueueCleanup(userId);
            await(() -> state(AccountCleanupProducer.jobId(userId)) == StateName.SUCCEEDED);
        } finally {
            locks.release("account-cleanup:user-" + userId, token);
        }
        assertThat(PROBED).doesNotContain(userId);
        assertThat(jdbc.queryForObject(
                        "select count(*) from account_cleanup where \"userId\" = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    @DisplayName("清理任务积压且执行缓慢时让出 worker：mail 任务在积压清完前送达，让出的清理任务最终全部成功且不计失败次数")
    void cleanupBacklogDoesNotStarveMail() throws Exception {
        List<Integer> userIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int userId = newUser();
            jdbc.update("update \"user\" set \"deletedAt\" = now() where id = ?", userId);
            userIds.add(userId);
        }
        SLOW.addAll(userIds);
        userIds.forEach(producer::enqueueCleanup);
        String to = "backlog-" + UUID.randomUUID() + "@example.com";

        mailer.send(new MailMessage("activation", to, Map.of("hash", "h")));

        await(() -> received(to).size() == 1, Duration.ofSeconds(8));
        assertThat(userIds.stream().filter(PROBED::contains).count())
                .as("cleanups finished before the mail")
                .isLessThan(userIds.size() / 2);
        List<UUID> ids = userIds.stream().map(AccountCleanupProducer::jobId).toList();
        await(() -> ids.stream().allMatch(id -> state(id) == StateName.SUCCEEDED), Duration.ofSeconds(120));
        assertThat(PROBED).containsAll(userIds);
        List<Job> jobs = ids.stream().map(this::job).toList();
        assertThat(jobs)
                .as("yielded at least once")
                .anyMatch(job ->
                        job.getJobStatesOfType(FailedState.class).findAny().isPresent());
        for (Job job : jobs) {
            assertThat(QueueJobFilter.failures(job)).isZero();
            assertThat(job.getJobStatesOfType(ScheduledState.class))
                    .allMatch(scheduled -> scheduled.getReason().equals("Yielded"));
        }
    }

    @Test
    @DisplayName("账号清理共 5 次尝试，按 10s、20s、40s、80s 退避，最后一次失败时告警并计 failed")
    void accountCleanupFinalFailureRaisesAlert() throws Exception {
        int userId = newUser();
        FAILING.add(userId);
        UUID id = AccountCleanupProducer.jobId(userId);
        double failed = metrics.value("queue_jobs_total", "account-cleanup", "failed");

        producer.enqueueCleanup(userId);
        await(() -> {
            StateName state = state(id);
            if (state == StateName.SCHEDULED) {
                Job scheduled = job(id);
                scheduled.enqueue();
                try {
                    storage.save(scheduled);
                } catch (ConcurrentJobModificationException ignored) {
                    return false;
                }
            }
            return state == StateName.FAILED;
        });

        Job job = job(id);
        assertThat(job.getJobStatesOfType(FailedState.class).count()).isEqualTo(5);
        assertThat(retryDelays(job))
                .containsExactly(
                        Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40), Duration.ofSeconds(80));
        Map<String, Object> alert = jdbc.queryForMap(
                "select type, level, message, payload::text as payload from admin_alert where \"dedupKey\" = ?",
                "accountCleanupFailed:" + userId);
        String reason = "Account cleanup for user " + userId + " failed: files (s3 down)";
        assertThat(alert)
                .containsEntry("type", "accountCleanupFailed")
                .containsEntry("level", "WARN")
                .containsEntry("message", "用户 " + userId + " 的注销数据清理失败：" + reason)
                .containsEntry(
                        "payload",
                        "{\"reason\": \"" + reason + "\", \"userId\": " + userId
                                + ", \"failedCleaners\": [\"files\"]}");
        assertThat(jdbc.queryForObject(
                        "select count(*) from account_cleanup where \"userId\" = ?", Integer.class, userId))
                .isZero();
        await(() -> metrics.value("queue_jobs_total", "account-cleanup", "failed") >= failed + 1);
    }

    @Test
    @DisplayName("运行 background server 的进程登记三个 recurring job（cron 与时区）")
    void recurringJobsAreRegistered() throws Exception {
        await(() -> storage.getRecurringJobs().size() == 3);
        Map<String, String> schedules = storage.getRecurringJobs().stream()
                .collect(Collectors.toMap(
                        RecurringJob::getId, job -> job.getScheduleExpression() + " " + job.getZoneId()));
        assertThat(schedules)
                .isEqualTo(Map.of(
                        "cleanup-old-sessions",
                        "0 3 * * * UTC",
                        "cleanup-read-notifications",
                        "0 4 * * * UTC",
                        "account-cleanup-compensation",
                        "25 * * * * Asia/Shanghai"));
    }
}
