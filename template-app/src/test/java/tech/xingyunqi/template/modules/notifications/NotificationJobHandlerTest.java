package tech.xingyunqi.template.modules.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jobrunr.JobRunrException;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.filters.DefaultJobFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.xingyunqi.template.common.mail.MailTransport;
import tech.xingyunqi.template.common.mail.OutgoingMail;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.modules.notifications.domain.WechatSubscribe;

class NotificationJobHandlerTest {

    static final WechatSubscribe.Payload PAYLOAD =
            new WechatSubscribe.Payload("orderPaid", Map.of("orderNo", "N1"), "pages/order");
    static final WechatSubscribe.Template TEMPLATE =
            new WechatSubscribe.Template("tpl", Map.of("orderNo", "character_string1"));

    UsersService users;
    MailTransport mail;
    NotificationSender sender;
    NotificationsService notifications;
    NotificationJobHandler handler;

    @BeforeEach
    void setUp() {
        users = mock(UsersService.class);
        mail = mock(MailTransport.class);
        sender = mock(NotificationSender.class);
        notifications = mock(NotificationsService.class);
        handler = new NotificationJobHandler(users, mail, sender, notifications);
    }

    static User user(String email, String phone, String openId) {
        Instant now = Instant.now();
        return new User(
                7, email, null, "email", null, null, phone, null, null, null, null, null, null, null, null, now, now,
                null, openId, null, null);
    }

    @Test
    void sendsEmailAndRecordsSuccess() throws Exception {
        when(users.findById(7)).thenReturn(Optional.of(user("a@example.com", null, null)));
        handler.run(NotificationJob.email(1, 7, "Title", "Body"));
        ArgumentCaptor<OutgoingMail> sent = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new OutgoingMail("a@example.com", "Title", "Body", "<p>Body</p>"));
        verify(notifications).recordChannelResult(1, "EMAIL", true, null);
    }

    @Test
    void emailWithoutAddressRecordsFailureAndRethrows() throws Exception {
        when(users.findById(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> handler.run(NotificationJob.email(1, 7, "T", "B")))
                .hasMessage("User 7 not found or has no email");
        verify(notifications).recordChannelResult(1, "EMAIL", false, "User 7 not found or has no email");
    }

    @Test
    void smsFailureResultIsRecordedAndRetried() throws Exception {
        when(users.findById(7)).thenReturn(Optional.of(user(null, "13800138000", null)));
        when(sender.sendSms("13800138000", "B")).thenReturn(new NotificationSender.SmsResult(false, null));
        assertThatThrownBy(() -> handler.run(NotificationJob.sms(2, 7, "B"))).hasMessage("SMS send failed");
        verify(notifications).recordChannelResult(2, "SMS", false, "SMS send failed");
    }

    @Test
    void smsSuccessIsRecorded() throws Exception {
        when(users.findById(7)).thenReturn(Optional.of(user(null, "13800138000", null)));
        when(sender.sendSms("13800138000", "B")).thenReturn(new NotificationSender.SmsResult(true, "ok"));
        handler.run(NotificationJob.sms(2, 7, "B"));
        verify(notifications).recordChannelResult(2, "SMS", true, null);
    }

    @Test
    void smsWithoutPhoneFails() throws Exception {
        when(users.findById(7)).thenReturn(Optional.of(user(null, null, null)));
        assertThatThrownBy(() -> handler.run(NotificationJob.sms(2, 7, "B")))
                .hasMessage("User 7 not found or has no phone");
    }

    @Test
    void subscribeWithoutTemplateIsSkipped() throws Exception {
        handler.run(NotificationJob.wechatSubscribe(3, 7, PAYLOAD));
        verify(users, never()).findById(anyInt());
        verify(notifications, never()).recordChannelResult(anyInt(), anyString(), anyBoolean(), any());
    }

    @Test
    void subscribeWithoutOpenIdRecordsFailure() throws Exception {
        when(sender.subscribeTemplate("orderPaid")).thenReturn(TEMPLATE);
        when(users.findById(7)).thenReturn(Optional.of(user(null, null, null)));
        handler.run(NotificationJob.wechatSubscribe(3, 7, PAYLOAD));
        verify(notifications).recordChannelResult(3, "PUSH", false, "User has no WeChat openId");
    }

    @Test
    void subscribeSendsBuiltMessage() throws Exception {
        when(sender.subscribeTemplate("orderPaid")).thenReturn(TEMPLATE);
        when(users.findById(7)).thenReturn(Optional.of(user(null, null, "open")));
        handler.run(NotificationJob.wechatSubscribe(3, 7, PAYLOAD));
        verify(sender)
                .sendSubscribeMessage(
                        "open", new WechatSubscribe.Message("tpl", "pages/order", Map.of("character_string1", "N1")));
        verify(notifications).recordChannelResult(3, "PUSH", true, null);
    }

    @Test
    void unrecoverableSubscribeErrorIsNotRetried() throws Exception {
        when(sender.subscribeTemplate("orderPaid")).thenReturn(TEMPLATE);
        when(users.findById(7)).thenReturn(Optional.of(user(null, null, "open")));
        doThrow(NotificationSender.SubscribeFailure.ofErrcode(43101, "user refused"))
                .when(sender)
                .sendSubscribeMessage(eq("open"), any());
        assertThatThrownBy(() -> handler.run(NotificationJob.wechatSubscribe(3, 7, PAYLOAD)))
                .isInstanceOf(JobRunrException.class)
                .satisfies(e -> assertThat(((JobRunrException) e).isProblematicAndDoNotRetry())
                        .isTrue());
        verify(notifications).recordChannelResult(3, "PUSH", false, "user refused");
    }

    @Test
    void recoverableSubscribeErrorIsRethrown() throws Exception {
        when(sender.subscribeTemplate("orderPaid")).thenReturn(TEMPLATE);
        when(users.findById(7)).thenReturn(Optional.of(user(null, null, "open")));
        doThrow(NotificationSender.SubscribeFailure.ofErrcode(-1, "busy"))
                .when(sender)
                .sendSubscribeMessage(eq("open"), any());
        assertThatThrownBy(() -> handler.run(NotificationJob.wechatSubscribe(3, 7, PAYLOAD)))
                .isInstanceOf(NotificationSender.SubscribeFailure.class);
    }

    @Test
    void recordFailureIsSwallowed() throws Exception {
        when(users.findById(7)).thenReturn(Optional.of(user("a@example.com", null, null)));
        doThrow(new IllegalStateException("db down"))
                .when(notifications)
                .recordChannelResult(anyInt(), anyString(), anyBoolean(), isNull());
        handler.run(NotificationJob.email(1, 7, "T", "B"));
        verify(mail).send(any());
    }

    @Test
    void unknownJobAndMissingPayloadAreIgnored() throws Exception {
        handler.run(new NotificationJob("other", 1, 7, null, null, null));
        handler.run(new NotificationJob(NotificationJob.SEND_WECHAT_SUBSCRIBE, 1, 7, null, null, null));
        verify(users, never()).findById(anyInt());
    }

    @Test
    @DisplayName("JobRunr 任务名为具体的 nestjs 任务名，标签为 notification 队列")
    void jobNameIsTaskName() {
        for (NotificationJob request : new NotificationJob[] {
            NotificationJob.email(1, 7, "T", "B"),
            NotificationJob.sms(2, 7, "B"),
            NotificationJob.wechatSubscribe(3, 7, PAYLOAD)
        }) {
            Job job = new Job(new JobDetails(request));
            new DefaultJobFilter().onCreating(job);
            assertThat(job.getJobName()).isEqualTo(request.name());
            assertThat(job.getLabels()).containsExactly("notification");
        }
    }
}
