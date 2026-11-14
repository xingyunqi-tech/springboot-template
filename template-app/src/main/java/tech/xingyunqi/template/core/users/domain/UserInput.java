package tech.xingyunqi.template.core.users.domain;

/** 创建 / 更新用户的输入；关联字段（photo、role、status）为请求中的 {@code id}，其值保持 JSON 原值（数字或字符串）。 */
public record UserInput(
        Patch<String> email,
        Patch<String> password,
        Patch<String> provider,
        Patch<String> firstName,
        Patch<String> lastName,
        Patch<String> phone,
        Patch<String> nickname,
        Patch<Object> gender,
        Patch<String> birthday,
        Patch<Ref> photo,
        Patch<Ref> role,
        Patch<Ref> status,
        Patch<String> wechatOpenId,
        Patch<String> wechatUnionId,
        Patch<String> alipayOpenId) {

    /** 关联引用 {@code {id}}；{@code id} 缺失时为 null。 */
    public record Ref(@org.jspecify.annotations.Nullable Object id) {}

    public static UserInput empty() {
        return new UserInput(
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent(),
                Patch.absent());
    }

    public UserInput withEmail(Patch<String> value) {
        return new UserInput(
                value,
                password,
                provider,
                firstName,
                lastName,
                phone,
                nickname,
                gender,
                birthday,
                photo,
                role,
                status,
                wechatOpenId,
                wechatUnionId,
                alipayOpenId);
    }

    public UserInput withPassword(Patch<String> value) {
        return new UserInput(
                email,
                value,
                provider,
                firstName,
                lastName,
                phone,
                nickname,
                gender,
                birthday,
                photo,
                role,
                status,
                wechatOpenId,
                wechatUnionId,
                alipayOpenId);
    }

    public UserInput withRole(Patch<Ref> value) {
        return new UserInput(
                email,
                password,
                provider,
                firstName,
                lastName,
                phone,
                nickname,
                gender,
                birthday,
                photo,
                value,
                status,
                wechatOpenId,
                wechatUnionId,
                alipayOpenId);
    }

    public UserInput withStatus(Patch<Ref> value) {
        return new UserInput(
                email,
                password,
                provider,
                firstName,
                lastName,
                phone,
                nickname,
                gender,
                birthday,
                photo,
                role,
                value,
                wechatOpenId,
                wechatUnionId,
                alipayOpenId);
    }
}
