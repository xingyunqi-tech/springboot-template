# 认证与权限

实现位置：`template-app` 的 `core/{auth,session,permissions,users}`、`integrations/{sms,wechat,alipay}`、`infrastructure/throttler`；`template-common-security` 的 `jwt/`（`Jwt`、`Ms`）、`route/`（`RouteAuthInterceptor`、`AuthContext`）、`password/PasswordHasher`；路由注解在 `template-common-web` 的 `route/`。

## 接口

全部成功响应为 HTTP 200。登录类接口返回 `{refreshToken, token, tokenExpires(ms), user}`，`auth/refresh` 返回 `{token, refreshToken, tokenExpires}`。

|   方法   |       路径（`/api/v1/` 下）        |     operationId      |     鉴权      |    限流    |
|--------|-------------------------------|----------------------|-------------|----------|
| POST   | `auth/email/login`            | loginWithEmail       | 公开          | 5 / 60s  |
| POST   | `auth/email/register`         | registerWithEmail    | 公开          | 默认       |
| POST   | `auth/email/confirm`          | confirmEmail         | 公开          | 默认       |
| POST   | `auth/email/confirm/new`      | confirmNewEmail      | 公开          | 默认       |
| POST   | `auth/forgot/password`        | forgotPassword       | 公开          | 3 / 300s |
| POST   | `auth/reset/password`         | resetPassword        | 公开          | 5 / 300s |
| GET    | `auth/me`                     | getCurrentUser       | JWT         | 默认       |
| PUT    | `auth/me`                     | updateCurrentUser    | JWT         | 10 / 60s |
| DELETE | `auth/me`                     | deleteCurrentUser    | JWT         | 默认       |
| POST   | `auth/refresh`                | refreshToken         | refresh JWT | 默认       |
| POST   | `auth/logout`                 | logout               | JWT         | 默认       |
| GET    | `auth/sessions`               | getMySessions        | JWT         | 默认       |
| DELETE | `auth/sessions/:id`           | revokeMySession      | JWT         | 默认       |
| POST   | `auth/sessions/revoke-others` | revokeOtherSessions  | JWT         | 默认       |
| PUT    | `auth/password`               | changePassword       | JWT         | 3 / 60s  |
| POST   | `auth/phone/send-code`        | sendSmsCode          | 公开          | 3 / 60s  |
| POST   | `auth/phone/login`            | loginWithPhone       | 公开          | 5 / 60s  |
| POST   | `auth/phone/sms-login`        | loginWithSms         | 公开          | 5 / 60s  |
| POST   | `auth/phone/register`         | registerWithPhone    | 公开          | 3 / 60s  |
| POST   | `auth/phone/reset-password`   | resetPasswordByPhone | 公开          | 5 / 300s |
| POST   | `auth/wechat/login`           | loginWithWechat      | 公开          | 10 / 60s |
| POST   | `auth/wechat/phone-login`     | loginWithWechatPhone | 公开          | 10 / 60s |
| POST   | `auth/alipay/login`           | loginWithAlipay      | 公开          | 10 / 60s |

「默认」指 `THROTTLE_LIMIT` 次 / `THROTTLE_TTL` 秒（100 / 60），由 `@Throttle(limit, ttl)` 覆盖；`THROTTLE_ENABLED=false` 时不限流也不输出限流头。

## 鉴权注解

每个 handler 必须且只能有一个鉴权注解，缺少时启动失败（默认拒绝）。`RouteAuthInterceptor` 在 handler 解析后、参数校验前执行，顺序与 nestjs 守卫 → 管道一致；主体放在请求属性中，controller 经 `AuthContext` 读取。

|          注解          |                                                           行为                                                            |
|----------------------|-------------------------------------------------------------------------------------------------------------------------|
| `@PublicEndpoint`    | 不校验，忽略 `Authorization` 头（带过期令牌访问登录接口照常 200）                                                                             |
| `@AuthenticatedOnly` | 只读 `Authorization: Bearer <token>`（按 passport-jwt 的 `/(\S+)\s+(\S+)/` 提取，scheme 不区分大小写），校验见下；失败 401                     |
| `@OptionalAuth`      | 令牌缺失或任何校验失败都按匿名处理                                                                                                       |
| `@RefreshTokenAuth`  | 仅 `auth/refresh`：用 `AUTH_REFRESH_SECRET` 校验，载荷须有 `sessionId`                                                            |
| `@MonitoringToken`   | `/api/v1/health`、`/metrics`：配置 `METRICS_TOKEN` 时要求 `Bearer <METRICS_TOKEN>`；未配置时非生产放行、生产拒绝；拒绝为 403 `Forbidden resource` |

## JWT

|   令牌    |                载荷                 |             密钥              |                  默认有效期                   |
|---------|-----------------------------------|-----------------------------|------------------------------------------|
| access  | `{id, role, sessionId, iat, exp}` | `AUTH_JWT_SECRET`           | `AUTH_JWT_TOKEN_EXPIRES_IN=15m`          |
| refresh | `{sessionId, hash, iat, exp}`     | `AUTH_REFRESH_SECRET`       | `AUTH_REFRESH_TOKEN_EXPIRES_IN=3650d`    |
| 找回密码    | 含密码指纹 `sha256(hash)` 前 16 位，改密后失效 | `AUTH_FORGOT_SECRET`        | `AUTH_FORGOT_TOKEN_EXPIRES_IN=30m`       |
| 邮箱确认    | 用户 id（换邮箱时另含新邮箱）                  | `AUTH_CONFIRM_EMAIL_SECRET` | `AUTH_CONFIRM_EMAIL_TOKEN_EXPIRES_IN=1d` |

- 签发 HS256，校验接受 HS256 / HS384 / HS512；与 jsonwebtoken 互认，nestjs 的短密钥（如 `secret`）同样可用。不使用 Nimbus（要求密钥 ≥256 位）
- access 的 `role` 在登录 / 注册类接口签发时为 `{id, name, __entity: "RoleEntity"}`，`auth/refresh` 签发时为 `{id}`
- `iat` 为秒，`exp = iat + floor(ms(expiresIn) / 1000)`；`*_EXPIRES_IN` 按 npm `ms` 2.x 规则解析（`Ms`），无法解析时启动失败；`tokenExpires` = 当前毫秒时间 + access 有效期
- 四个密钥都必须设置；生产要求均 ≥32 字符且互不相同

### access 校验

1. 验签与过期检查，载荷必须有 `id`、`sessionId`，否则 401（`common.unauthorized` 译文）
2. 查缓存 `jwt:session:{sessionId}`（30s，`{statusId, roleId}`）
3. 未命中时查库：会话不存在、已删除或不属于该用户 → 401 `Session expired`；用户不存在 → 401 `User not found`；用户状态不是 active / inactive → 401 `User is not active`；同时节流更新 `lastActiveAt`（每会话每 5 分钟最多一次，失败只记日志）
4. 以数据库中的角色为准写入缓存

查库与更新在拦截器内用同一个连接完成并在进入 handler 前归还，单个请求任何时刻只占一个连接。会话被删除时（logout、吊销、改密、重置密码、重放吊销），`jwt:session:{id}` 在写库后立即清除一次，事务提交后（`AfterCommit.always`）再清除一次。

### 刷新轮换

- 会话不存在或已无关联用户 → 401
- `session.hash != payload.hash`：若等于 60 秒内刚被换下的旧 hash（`session:rotated:{id}`，只保存最近一次）视为并发刷新的落后请求，只返回 401；否则判为重放，吊销会话并 401。吊销写在请求事务内，按 `@WriteTransaction` 规则随 401 提交
- 会话匹配后重新读用户：用户或角色不存在 → 401；状态不是 active / inactive → 422 `{refresh: 'userNotActive'}`
- 生成新 hash，以 `UPDATE session SET hash = :new WHERE id = :id AND hash = :old` 做 CAS，0 行则 401；成功后把旧 hash 写入 `session:rotated:{id}`
- logout 软删当前会话；改密（`PUT auth/password`、`PUT auth/me` 带 `password`）删除当前会话以外的会话；重置密码（邮件或短信）、管理员改密、注销账号删除全部会话；每天 03:00 UTC 物理删除软删超过 7 天的会话

## 密码

客户端传 MD5 hex（32 位）；验证时同时尝试原值与大写形式。服务端哈希由 `PASSWORD_HASHER` 决定：

|                 值                  |                  用途                   |
|------------------------------------|---------------------------------------|
| `argon2id`（默认，m=19456 KiB、t=2、p=1） | 独立部署                                  |
| `bcrypt`（cost 10）                  | 与 nestjs 共用数据库时（nestjs 无法验证 argon2id） |

按哈希前缀识别两种格式都能验证；登录成功且存储的哈希不是当前算法或 argon2id 参数不同时重哈希写回。重哈希会让此前发出的找回密码链接失效（令牌内嵌旧哈希指纹，422 `invalidHash`）。每个进程同时进行的哈希 / 验证数由 `PASSWORD_HASH_CONCURRENCY`（默认 2）限制：一次 argon2id 占 19MiB，虚拟线程下不加限制会耗尽小容器内存。

## 登录方式

|   方式    |                                                           行为                                                            |
|---------|-------------------------------------------------------------------------------------------------------------------------|
| 邮箱      | 注册后 inactive 并在提交后入队确认邮件；active / inactive 均可登录；失败 422 `{email: 'notFound'}` / `{password: 'incorrectPassword'}`（输出为译文） |
| 手机 + 密码 | 失败 422 `{credentials: 'incorrectPhoneOrPassword'}`                                                                      |
| 短信验证码   | 不自动注册，未知手机号 422 `{phone: 'notFound'}`                                                                                   |
| 手机注册    | 直接 active 并返回令牌                                                                                                         |
| 微信      | `type`：`mini_app`（jscode2session）、`app`（OAuth）；按 openId 自动建号，唯一约束冲突时重新查询                                                |
| 微信手机号   | 绑定 openId 与手机号，不合并已有账号（`phoneBoundToOtherWechat`）；非大陆号拒绝（`phoneRegionNotSupported`）                                     |
| 支付宝     | 按 alipayOpenId 自动建号                                                                                                     |

微信、支付宝、短信经 Spring `RestClient` 直调（`integrations/http/OutboundHttp`），这些外部调用不放在数据库事务内。配置：`WECHAT_MINI_APP_ID` / `WECHAT_MINI_APP_SECRET`（小程序）、`WECHAT_APP_ID` / `WECHAT_APP_SECRET`（App）、`ALIPAY_APP_ID`、`ALIPAY_PRIVATE_KEY`、`ALIPAY_GATEWAY`、`ALIPAY_SIGN_TYPE`、`ALIPAY_CHARSET`、`ALIPAY_SANDBOX`。

### 短信验证码

|  项   |                                                         规则                                                         |
|------|--------------------------------------------------------------------------------------------------------------------|
| 类型   | `register` 要求手机号不存在；`login` / `reset_password` / `verify_identity` 要求存在；`change_phone` 返回 `unsupportedCodeType`    |
| 存储   | `sms:code:{type}:{phone}` = `{code, attempts, createdAt}`，TTL `SMS_CODE_EXPIRES`（300s），`SMS_CODE_LENGTH`（6）位数字     |
| 重发间隔 | `SMS_RESEND_INTERVAL`（60s），锁 `sms:send:{type}:{phone}`；失败 422 `{phone: 'rateLimited' \| 'sendFailed', retryAfter}` |
| 尝试次数 | `SMS_MAX_ATTEMPTS`（5），成功或超限后删除；失败 422 `{code: 'invalidCode'}`                                                      |
| 发送   | 阿里云 Dysms（`SMS_ENDPOINT`、`SMS_SIGN_NAME`、`SMS_TEMPLATE_CODE`）；`SMS_MOCK_MODE=true` 只写日志（`.env.example` 默认开启）       |

## RBAC

|    角色    | id |    状态    | id |
|----------|----|----------|----|
| admin    | 1  | active   | 1  |
| user     | 2  | inactive | 2  |
| editor   | 3  |          |    |
| reviewer | 4  |          |    |

- 权限枚举与默认映射：`core/permissions/Permission.java`（值与 nestjs `PermissionEnum` 一致）
- 角色权限读 `permission`、`role_permission` 表，表为空时回落代码默认值；缓存键 `permissions:v2:role-map`，5 分钟；`REDIS_ENABLED=true` 时只放 Redis，下线、改角色、改权限在所有实例上同时生效
- `@PermissionsGuarded` 配合 `@RequirePermissions(...)`（全部满足）或 `@RequireAnyPermission(...)`（任一满足），标注在 controller 实现方法或类上，方法级覆盖类级；未加 `@PermissionsGuarded` 的 JWT 路由（如 `auth/me`）不检查权限
- 经过权限守卫却未声明权限 → 403 `No permission declared for this route`；无身份 → 403 `User not authenticated`；无角色 → 403 `User role not found`
- 缺权限 → 403 `Missing permissions: a, b` / `Missing any of permissions: a, b`（原文输出，无翻译键）
- 哪些 operation 经过权限守卫、需要哪些权限，记录在 `openapi/scope.yaml` 的 `permissions`（照抄 nestjs controller），契约测试比对两者

## 会话

`session` 表记录 `userAgent`（≤512）、`ip`（按 `TRUST_PROXY` 取）、`lastActiveAt`。`GET auth/sessions` 返回当前用户的设备列表并标记当前会话；`DELETE auth/sessions/:id` 下线指定设备；`POST auth/sessions/revoke-others` 下线其它设备。

## 限流计数主体

`ThrottleGuard` 在鉴权前执行：`@AuthenticatedOnly` 路由带有效 access 令牌（只验签，不查会话）时按 `user:{id}` 计数，其余按 `ip:{normalizeIp(ip)}` 计数（IP 按 `TRUST_PROXY` 从 `X-Forwarded-For` 取）。放行时带 `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`，429 只带 `Retry-After`。
