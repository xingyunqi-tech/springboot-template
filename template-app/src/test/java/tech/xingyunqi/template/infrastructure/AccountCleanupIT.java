package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.mybatis.WriteTransactions;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupCompensationTask;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupException;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupProducer;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupRunner;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;
import tech.xingyunqi.template.infrastructure.scheduler.CleanupTasks;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

/** 不运行 background server：只断言入队、去重与同步执行的清理逻辑。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AccountCleanupIT.Cleaners.class})
class AccountCleanupIT {

    static final List<Integer> PROBED = Collections.synchronizedList(new ArrayList<>());
    static final List<Integer> FAILING = Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration(proxyBeanMethods = false)
    static class Cleaners {

        @Bean
        AccountDataCleaner probeCleaner() {
            return new AccountDataCleaner() {
                @Override
                public String name() {
                    return "probe";
                }

                @Override
                public int order() {
                    return 10;
                }

                @Override
                public void clean(int userId) {
                    if (FAILING.contains(userId)) {
                        throw new IllegalStateException("s3 down");
                    }
                    PROBED.add(userId);
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsersService users;

    @Autowired
    WriteTransactions transactions;

    @Autowired
    StorageProvider storage;

    @Autowired
    AccountCleanupRunner runner;

    @Autowired
    AccountCleanupCompensationTask compensation;

    @Autowired
    CleanupTasks cleanupTasks;

    @MockitoSpyBean
    AccountCleanupProducer producer;

    AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
    }

    int newUser() {
        return jdbc.queryForObject(
                "insert into \"user\" (email, provider, \"roleId\", \"statusId\") values (?, 'email', 2, 1) returning id",
                Integer.class,
                "c-" + UUID.randomUUID() + "@example.com");
    }

    Job job(int userId) {
        return storage.getJobById(AccountCleanupProducer.jobId(userId));
    }

    boolean hasJob(int userId) {
        try {
            job(userId);
            return true;
        } catch (JobNotFoundException e) {
            return false;
        }
    }

    String adminToken() throws Exception {
        return api.loginData(AuthApi.ADMIN).path("token").asString();
    }

    @Test
    @DisplayName("管理员删除用户：删除在事务内执行，提交后按 user-<id> 的 job id 投递 clean-account 任务")
    void removeEnqueuesAfterCommit() throws Exception {
        int userId = newUser();
        List<Boolean> inTransaction = new ArrayList<>();
        doAnswer(invocation -> {
                    inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
                    assertThat(hasJob(userId)).isFalse();
                    return invocation.callRealMethod();
                })
                .when(producer)
                .enqueueAfterCommit(anyInt());

        AuthApi.Response response = api.call("DELETE", "/api/v1/users/" + userId, null, adminToken());

        assertThat(response.status()).isEqualTo(200);
        assertThat(inTransaction).containsExactly(true);
        Job job = job(userId);
        assertThat(job.getState()).isEqualTo(StateName.ENQUEUED);
        assertThat(job.getJobName()).isEqualTo("clean-account");
        assertThat(job.getLabels()).containsExactly("account-cleanup");
    }

    @Test
    @DisplayName("事务以 4xx 结束时已写入的软删除照常提交，提交后副作用被丢弃、不投递任务")
    void clientErrorCommitDoesNotEnqueue() {
        int userId = newUser();
        assertThatThrownBy(() -> transactions.run(() -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    users.remove(userId);
                    throw HttpStatusException.validationError("user", "probe");
                }))
                .isInstanceOf(HttpStatusException.class);

        assertThat(users.isDeleted(userId)).isTrue();
        assertThat(hasJob(userId)).isFalse();
    }

    @Test
    @DisplayName("同一用户的任务排队中时不重复入队；已结束的任务删除后重新入队")
    void deduplicatesPendingJobs() {
        int userId = newUser();
        producer.enqueueCleanup(userId);
        Job first = job(userId);
        producer.enqueueCleanup(userId);
        assertThat(job(userId).getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(job(userId).getVersion()).isEqualTo(first.getVersion());

        Job finished = job(userId);
        finished.delete("finished");
        storage.save(finished);
        producer.enqueueCleanup(userId);

        Job again = job(userId);
        assertThat(again.getState()).isEqualTo(StateName.ENQUEUED);
        assertThat(again.getCreatedAt()).isAfter(first.getCreatedAt());
    }

    @Test
    @DisplayName("并发投递同一用户时只留下一个未结束的任务，且不触发入队失败告警")
    void concurrentEnqueueKeepsOnePendingJob() throws Exception {
        int userId = newUser();
        producer.enqueueCleanup(userId);
        Job finished = job(userId);
        finished.delete("finished");
        storage.save(finished);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    producer.enqueueCleanup(userId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(jdbc.queryForObject(
                        "select count(*) from jobrunr.jobrunr_jobs where id = ?",
                        Integer.class,
                        AccountCleanupProducer.jobId(userId).toString()))
                .isEqualTo(1);
        assertThat(job(userId).getState()).isEqualTo(StateName.ENQUEUED);
        assertThat(jdbc.queryForObject(
                        "select count(*) from admin_alert where \"dedupKey\" = ?",
                        Integer.class,
                        "accountCleanupFailed:" + userId))
                .isZero();
    }

    @Test
    @DisplayName("重投递：未注销 422、不存在 404、无 user:delete 权限 403、非法 id 400、已注销则入队")
    void retrigger() throws Exception {
        String admin = adminToken();
        int active = newUser();
        AuthApi.Response notDeleted = api.call("POST", "/api/v1/admin/users/" + active + "/cleanup", null, admin);
        assertThat(notDeleted.status()).isEqualTo(422);
        assertThat(notDeleted.body().toString())
                .isEqualTo(
                        "{\"code\":422,\"message\":\"Validation failed\",\"data\":{\"user\":\"User has not been deleted\"}}");

        AuthApi.Response missing = api.call("POST", "/api/v1/admin/users/999999/cleanup", null, admin);
        assertThat(missing.body().toString()).isEqualTo("{\"code\":404,\"message\":\"User not found\",\"data\":null}");
        assertThat(api.call("POST", "/api/v1/admin/users/abc/cleanup", null, admin)
                        .status())
                .isEqualTo(400);

        String john = api.loginData(AuthApi.JOHN).path("token").asString();
        assertThat(api.call("POST", "/api/v1/admin/users/1/cleanup", null, john).status())
                .isEqualTo(403);

        int deleted = newUser();
        jdbc.update("update \"user\" set \"deletedAt\" = now() where id = ?", deleted);
        AuthApi.Response ok = api.call("POST", "/api/v1/admin/users/" + deleted + "/cleanup", null, admin);
        assertThat(ok.body().toString()).isEqualTo("{\"code\":200,\"message\":\"success\"}");
        assertThat(job(deleted).getState()).isEqualTo(StateName.ENQUEUED);
    }

    @Test
    @DisplayName("清理器按 order 执行：users 解除头像引用、files 删除文件，全部成功后写 account_cleanup")
    void runnerCleansAndMarksCompleted() {
        int userId = newUser();
        UUID photo = UUID.randomUUID();
        jdbc.update("insert into file (id, path) values (?, '/files/avatar.png')", photo);
        jdbc.update(
                "update \"user\" set \"photoId\" = ?, \"deletedAt\" = now(), \"updatedAt\" = now() - interval '1 day'"
                        + " where id = ?",
                photo,
                userId);

        runner.run(userId);

        assertThat(runner.cleanerNames())
                .containsExactly("probe", "favorites", "feedbacks", "notifications", "addresses", "users", "files");
        assertThat(PROBED).contains(userId);
        assertThat(jdbc.queryForMap(
                        "select \"photoId\" is null as detached, \"updatedAt\" > now() - interval '1 hour' as touched"
                                + " from \"user\" where id = ?",
                        userId))
                .isEqualTo(Map.of("detached", true, "touched", true));
        assertThat(jdbc.queryForObject(
                        "select count(*) from account_cleanup where \"userId\" = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("单个清理器失败不影响其余清理器，汇总抛出且不写 account_cleanup")
    void runnerReportsFailedCleaners() {
        int userId = newUser();
        FAILING.add(userId);
        UUID photo = UUID.randomUUID();
        jdbc.update("insert into file (id, path) values (?, '/files/a.png')", photo);
        jdbc.update("update \"user\" set \"photoId\" = ? where id = ?", photo, userId);

        assertThatThrownBy(() -> runner.run(userId))
                .isInstanceOf(AccountCleanupException.class)
                .hasMessage("Account cleanup for user " + userId + " failed: probe (s3 down)");
        assertThat(jdbc.queryForObject("select \"photoId\" from \"user\" where id = ?", UUID.class, userId))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "select count(*) from account_cleanup where \"userId\" = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    @DisplayName("补偿任务只重新入队注销超过 30 分钟且未完成清理的用户，已排队的不重复入队")
    void compensationEnqueuesUsersPastGrace() {
        int old = newUser();
        int recent = newUser();
        int done = newUser();
        jdbc.update("update \"user\" set \"deletedAt\" = now() - interval '31 minutes' where id in (?, ?)", old, done);
        jdbc.update("update \"user\" set \"deletedAt\" = now() - interval '10 minutes' where id = ?", recent);
        jdbc.update("insert into account_cleanup (\"userId\", \"completedAt\") values (?, now())", done);

        compensation.run();

        assertThat(hasJob(old)).isTrue();
        assertThat(hasJob(recent)).isFalse();
        assertThat(hasJob(done)).isFalse();
        Job first = job(old);
        compensation.run();
        assertThat(job(old).getVersion()).isEqualTo(first.getVersion());
    }

    @Test
    @DisplayName("定时清理：物理删除软删除超过 7 天的会话，软删除已读超过 30 天的通知，重复执行无影响")
    void cleanupConditions() {
        int userId = newUser();
        String tag = UUID.randomUUID().toString();
        jdbc.update(
                "insert into session (hash, \"userId\", \"deletedAt\") values"
                        + " (? || '-old', ?, now() - interval '8 days'), (? || '-recent', ?, now() - interval '6 days'),"
                        + " (? || '-active', ?, null)",
                tag,
                userId,
                tag,
                userId,
                tag,
                userId);
        jdbc.update(
                "insert into notification (\"userId\", type, category, title, content, \"isRead\", \"readAt\") values"
                        + " (?, 'INFO', 'SYSTEM', 'old', 'c', true, now() - interval '31 days'),"
                        + " (?, 'INFO', 'SYSTEM', 'recent', 'c', true, now() - interval '29 days'),"
                        + " (?, 'INFO', 'SYSTEM', 'unread', 'c', false, null)",
                userId,
                userId,
                userId);

        cleanupTasks.cleanupOldSessions();
        cleanupTasks.cleanupReadNotifications();

        assertThat(jdbc.queryForList(
                        "select hash from session where \"userId\" = ? order by hash", String.class, userId))
                .containsExactly(tag + "-active", tag + "-recent");
        assertThat(jdbc.queryForList(
                        "select title from notification where \"userId\" = ? and \"deletedAt\" is not null",
                        String.class,
                        userId))
                .containsExactly("old");
        assertThat(cleanupTasks.cleanupOldSessions()).isZero();
        assertThat(cleanupTasks.cleanupReadNotifications()).isZero();
    }
}
