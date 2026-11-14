package tech.xingyunqi.template.views;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** 登录类接口的 {@code data}，键顺序同 nestjs {@code createSessionAndTokens} 的字面量。 */
@JsonPropertyOrder({"refreshToken", "token", "tokenExpires", "user"})
public record LoginResponseView(String token, String refreshToken, long tokenExpires, UserView user) {}
