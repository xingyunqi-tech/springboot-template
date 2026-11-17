package tech.xingyunqi.template.modules.notifications.domain;

/** 通知设置的四个渠道开关。 */
public record SettingFlags(boolean enableInApp, boolean enableEmail, boolean enableSms, boolean enablePush) {

    public boolean sameAs(NotificationSetting setting) {
        return enableInApp == setting.enableInApp()
                && enableEmail == setting.enableEmail()
                && enableSms == setting.enableSms()
                && enablePush == setting.enablePush();
    }
}
