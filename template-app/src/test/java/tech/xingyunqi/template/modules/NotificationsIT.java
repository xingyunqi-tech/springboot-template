package tech.xingyunqi.template.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;
import tech.xingyunqi.template.modules.notifications.NotificationsService;
import tech.xingyunqi.template.modules.notifications.domain.CreateNotification;
import tech.xingyunqi.template.modules.notifications.domain.Notification;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationsIT {

    static final String URL = "/api/v1/notifications";
    static final Map<String, Object> ALL_ON = Map.of(
            "category", "ORDER", "enableInApp", true, "enableEmail", true, "enableSms", true, "enablePush", true);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationsService notifications;

    @Autowired
    List<AccountDataCleaner> cleaners;

    AuthApi api;
    TestUsers.TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApi(mvc);
        user = TestUsers.register(api, "notify");
    }

    AuthApi.Response call(String method, String path, Object body) throws Exception {
        return api.call(method, path, body, user.token());
    }

    Notification create(String category, List<String> channels, Map<String, Object> metadata) {
        return notifications.create(new CreateNotification(user.id(), "INFO", category, "t", "c", metadata, channels));
    }

    long jobs(String name) {
        Long count = jdbc.queryForObject(
                "select count(*) from jobrunr.jobrunr_jobs where jobasjson like ?", Long.class, "%" + name + "%");
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("未登录 401")
    void requiresAuthentication() throws Exception {
        assertThat(api.call("GET", URL + "/unread-count", null, null).status()).isEqualTo(401);
        assertThat(api.call("PUT", URL + "/settings", ALL_ON, null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("注册时初始化 6 个分类的默认设置；未变化的更新不改 updatedAt，变化时更新")
    void settings() throws Exception {
        AuthApi.Response listed = call("GET", URL + "/settings", null);
        List<String> categories = new ArrayList<>();
        listed.data().forEach(setting -> categories.add(setting.path("category").asString()));
        assertThat(categories)
                .containsExactlyInAnyOrder("ORDER", "PAYMENT", "SYSTEM", "PROMOTION", "ACTIVITY", "STUDY");
        assertThat(new ArrayList<>(listed.data().get(0).propertyNames()))
                .containsExactly(
                        "id",
                        "userId",
                        "category",
                        "enableInApp",
                        "enableEmail",
                        "enableSms",
                        "enablePush",
                        "createdAt",
                        "updatedAt");

        JsonNode unchanged = call("PUT", URL + "/settings", ALL_ON).data();
        assertThat(unchanged.path("updatedAt")).isEqualTo(unchanged.path("createdAt"));
        Thread.sleep(5);
        Map<String, Object> smsOff = new java.util.LinkedHashMap<>(ALL_ON);
        smsOff.put("enableSms", false);
        JsonNode changed = call("PUT", URL + "/settings", smsOff).data();
        assertThat(changed.path("enableSms").asBoolean()).isFalse();
        assertThat(changed.path("updatedAt")).isNotEqualTo(changed.path("createdAt"));

        jdbc.update("delete from notification_setting where \"userId\" = ?", user.id());
        JsonNode recreated = call("PUT", URL + "/settings", smsOff).data();
        assertThat(recreated.path("category").asString()).isEqualTo("ORDER");

        assertThat(call("PUT", URL + "/settings", Map.of("category", "NOPE", "enableInApp", "yes"))
                        .status())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("列表、未读数、详情、已读、全部已读与删除；只含站内信渠道")
    void inbox() throws Exception {
        Notification first = create("ORDER", List.of("IN_APP"), Map.of("orderId", 1, "ratio", 1.0));
        Notification second = create("SYSTEM", List.of("IN_APP", "EMAIL"), null);
        create("SYSTEM", List.of("EMAIL"), null);

        AuthApi.Response list = call("GET", URL, null);
        assertThat(list.data().path("total").asInt()).isEqualTo(2);
        JsonNode newest = list.data().path("list").get(0);
        assertThat(newest.has("metadata")).isFalse();
        assertThat(newest.has("readAt")).isFalse();
        assertThat(newest.path("deletedAt").isNull()).isTrue();
        assertThat(new ArrayList<>(newest.propertyNames()))
                .containsExactly(
                        "id",
                        "userId",
                        "type",
                        "category",
                        "title",
                        "content",
                        "channels",
                        "sentChannels",
                        "isRead",
                        "createdAt",
                        "updatedAt",
                        "deletedAt");
        JsonNode detail = call("GET", URL + "/" + first.id(), null).data();
        assertThat(detail.path("metadata").toString()).isEqualTo("{\"ratio\":1,\"orderId\":1}");

        assertThat(call("GET", URL + "?category=ORDER&isRead=false", null)
                        .data()
                        .path("total")
                        .asInt())
                .isEqualTo(1);
        assertThat(call("GET", URL + "?limit=500", null).data().path("limit").asInt())
                .isEqualTo(50);
        assertThat(call("GET", URL + "?page=-1&limit=abc&category=NOPE&isRead=x", null)
                        .status())
                .isEqualTo(422);
        assertThat(call("GET", URL + "/unread-count", null).data().asInt()).isEqualTo(2);

        JsonNode read = call("POST", URL + "/" + first.id() + "/read", null).data();
        assertThat(read.path("isRead").asBoolean()).isTrue();
        assertThat(read.has("readAt")).isTrue();
        assertThat(call("GET", URL + "/unread-count", null).data().asInt()).isEqualTo(1);
        assertThat(call("POST", URL + "/read-all", null).status()).isEqualTo(200);
        assertThat(call("GET", URL + "/unread-count", null).data().asInt()).isZero();

        assertThat(call("DELETE", URL + "/" + second.id(), null).status()).isEqualTo(200);
        AuthApi.Response gone = call("GET", URL + "/" + second.id(), null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().path("message").asString()).isEqualTo("Notification not found");
        assertThat(call("DELETE", URL + "/" + second.id(), null).status()).isEqualTo(404);
        assertThat(call("POST", URL + "/999999/read", null).status()).isEqualTo(404);
        assertThat(call("POST", URL + "/x/read", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("创建通知按设置过滤渠道，提交后为站外渠道入队；无 wechatSubscribe 的 PUSH 不入队")
    void createQueuesExternalChannels() throws Exception {
        Map<String, Object> smsOff = new java.util.LinkedHashMap<>(ALL_ON);
        smsOff.put("enableSms", false);
        call("PUT", URL + "/settings", smsOff);
        long emails = jobs("send-email");
        long sms = jobs("send-sms");
        long pushes = jobs("send-wechat-subscribe");

        Notification filtered = create("ORDER", List.of("IN_APP", "EMAIL", "SMS", "PUSH"), null);
        assertThat(filtered.channels()).containsExactly("IN_APP", "EMAIL", "PUSH");
        assertThat(jobs("send-email")).isEqualTo(emails + 1);
        assertThat(jobs("send-sms")).isEqualTo(sms);
        assertThat(jobs("send-wechat-subscribe")).isEqualTo(pushes);

        jdbc.update("delete from notification_setting where \"userId\" = ?", user.id());
        Notification unfiltered = create(
                "PAYMENT",
                List.of("SMS", "PUSH"),
                Map.of("wechatSubscribe", Map.of("scene", "orderPaid", "values", Map.of("orderNo", "1"), "page", "p")));
        assertThat(unfiltered.channels()).containsExactly("SMS", "PUSH");
        assertThat(jobs("send-sms")).isEqualTo(sms + 1);
        assertThat(jobs("send-wechat-subscribe")).isEqualTo(pushes + 1);

        notifications.recordChannelResult(unfiltered.id(), "SMS", false, "boom");
        String sent = jdbc.queryForObject(
                "select \"sentChannels\"::text from notification where id = ?", String.class, unfiltered.id());
        assertThat(sent).contains("\"SMS\"").contains("\"error\": \"boom\"").contains("\"sent\": false");
    }

    @Test
    @DisplayName("账号清理删除通知与通知设置")
    void accountCleaner() throws Exception {
        create("ORDER", List.of("IN_APP"), null);
        AccountDataCleaner cleaner = cleaners.stream()
                .filter(c -> c.name().equals("notifications"))
                .findFirst()
                .orElseThrow();
        assertThat(cleaner.order()).isEqualTo(100);
        cleaner.clean(user.id());
        assertThat(jdbc.queryForObject(
                        "select (select count(*) from notification where \"userId\" = ?)"
                                + " + (select count(*) from notification_setting where \"userId\" = ?)",
                        Integer.class,
                        user.id(),
                        user.id()))
                .isZero();
    }
}
