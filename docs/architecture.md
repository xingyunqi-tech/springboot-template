# 架构

## 模块

Maven 多模块，根 `pom.xml` 的父 pom 为 `spring-boot-starter-parent` 4.1.2，版本号统一为 `${revision}`（flatten-maven-plugin）。包名前缀 `tech.xingyunqi.template`。

```
template-common/
├── template-common-core/            # BusinessException、HttpStatusException、业务错误键表、唯一约束表、AlertReporter
├── template-common-json/            # Jackson 3 配置，与 JS 一致的数字 / 字符串处理
├── template-common-web/             # 过滤器链、Express 路由语义、信封、错误翻译、i18n、分页、/docs-json、
│                                    #   路由注解（route/）、ClassValidatorEngine（validation/，含 validator.js 移植）
├── template-common-redis/           # Cache（Redis / 进程内）、LockService（lock:<key>）、GCRA 限流（throttle/）
├── template-common-mybatis/         # MyBatis-Plus 配置、@WriteTransaction、AfterCommit
├── template-common-security/        # jsonwebtoken 兼容 JWT（Jwt、Ms）、RouteAuthInterceptor、PasswordHasher
├── template-common-oss/             # FileStorage：local / s3 / s3-presigned
├── template-common-mail/            # MailTemplates（Handlebars）、SmtpMailTransport / LoggingMailTransport
└── template-common-observability/   # 请求日志、AppMetrics（与 nestjs 同名指标）、/metrics、OTel、Sentry
template-contract/                   # openapi-generator 生成的 *Api 接口与 DTO（target/generated-sources，不入库）
template-views/                      # 手写响应视图（UserView、LoginResponseView 等），经 schemaMappings 替换生成类
template-app/                        # 应用，见下
coverage-report/                     # JaCoCo report-aggregate 与 -Pci 门禁（ci/CiGate.java）
tools/openapi-tools/                 # CodegenInput：按 scope.yaml 与 codegen-overlay.yaml 生成 codegen 输入
tools/generator/                     # 模块骨架生成器（make module）
tests/e2e、tests/parity              # 独立 Maven 模块，只依赖 HTTP；-Pe2e / -Pparity 激活
```

`template-app/src/main/java/tech/xingyunqi/template/`：

|                    包                    |                                                   内容                                                    |
|-----------------------------------------|---------------------------------------------------------------------------------------------------------|
| `core/{auth,users,permissions,session}` | 认证、用户、RBAC、会话                                                                                           |
| `infrastructure/`                       | `throttler`、`health`、`metrics`、`queue`（JobRunr）、`scheduler`、`audit`、`alerting`、`accountcleanup`、`files` |
| `integrations/`                         | `mail`（入队与渲染）、`sms`（阿里云 Dysms）、`wechat`、`alipay`、`http`（出站请求）                                           |
| `modules/`                              | `home`、`config`、`regions`、`notifications`、`addresses`、`favorites`、`feedbacks`、`consents`、`appprofile`   |
| `cli/`                                  | `AppRole`（按 `APP_ROLE` 切换进程角色）、`Seeder`                                                                 |

资源：`application.yml`（只用 `${ENV:default}` 读环境变量，不分 profile 文件）、`db/migration/`（Flyway）、`i18n/{en,zh}/`（nestjs 翻译文件）、`mail-templates/`（nestjs `.hbs`）、`seed/`（`seed.json`、`region-data.json`）。

## 分层

模块内分层（`core/users` 为例）：

```
core/users/
├── domain/              # 领域对象（record），不引用 MyBatis / Spring Web / Jackson / Servlet
├── persistence/         # 实体、Mapper、XxxRepositoryImpl
├── UserRepository.java  # 端口接口
├── UsersService.java    # 业务逻辑，只依赖端口
└── web/                 # UsersController implements 生成的 UsersApi；DTO ↔ domain 映射
```

通用模块用 service + repository 两层，结构相同。规则由 `template-app/src/test/.../architecture/ArchitectureTest.java`（ArchUnit，随 `make test` 运行）强制：

|  规则  |                                                            内容                                                            |
|------|--------------------------------------------------------------------------------------------------------------------------|
| 领域纯净 | `..domain..` 不依赖 MyBatis、Spring Web、Jackson、Jakarta Servlet                                                              |
| 分层   | `..web..` 不访问 `..persistence..`；service 不依赖 `..web..` 与生成的 DTO                                                           |
| 生成代码 | `template-contract` 的 `*Api` / DTO 只被 `..web..` 引用；DTO 不带 `jakarta.validation` 注解                                        |
| 无环   | `core.*`、`modules.*` 按包切片无循环依赖                                                                                           |
| 事务   | 不得直接使用 `@Transactional`，只用 `@WriteTransaction`                                                                           |
| 编码约定 | 禁止字段注入、`@Value` 注入、访问 `System.out` / `System.err`、`System.getenv`；禁用 `org.dromara`、`cn.hutool`、`cn.dev33`（enforcer 同样禁止） |

## 接口声明

`template-contract` 在构建时由 `tools/openapi-tools` 的 `CodegenInput` 把 `openapi/nestjs-baseline.json` 按 `openapi/scope.yaml` 裁剪、叠加 `openapi/codegen-overlay.yaml`（响应字段可空性等），再交给 openapi-generator（`interfaceOnly`、`skipDefaultInterface`、`useSpringBoot4`）生成 `*Api` 接口与 DTO。生成接口不含 `@ResponseStatus`（覆盖的 `api.mustache`），controller 漏实现方法时编译失败。

nestjs 用装饰器表达、OpenAPI 中没有的行为，以注解写在 controller 实现方法（或类）上：

|                                                  注解                                                   |                          说明                           |
|-------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| `@PublicEndpoint` / `@AuthenticatedOnly` / `@OptionalAuth` / `@RefreshTokenAuth` / `@MonitoringToken` | 鉴权方式，每个 handler 必须且只能有一个，缺失时启动失败，见 [auth.md](auth.md) |
| `@PermissionsGuarded` + `@RequirePermissions` / `@RequireAnyPermission`                               | RBAC，与 `scope.yaml` 的 `permissions` 一致（契约测试比对）        |
| `@Throttle(limit, ttl)` / `@SkipThrottle`                                                             | 路由级限流（ttl 毫秒），对应 nestjs `@Throttle` / `@SkipThrottle` |
| `@WriteTransaction`                                                                                   | service 写方法的事务，见 [database.md](database.md#事务)        |
| `@Audit`                                                                                              | 成功后写 `audit_logs`                                     |
| `@RawResponse`                                                                                        | 响应不包信封（`/metrics`、文件读取）                               |

需要替换响应形状的 schema（`User`、`LoginResponseDto`、`Notification`、`Feedback` 等）由 `template-contract/pom.xml` 的 `schemaMappings` 映射到 `template-views` 的手写 record，清单见 [contract.md](contract.md)。

## 请求链路

Servlet 过滤器由外到内（顺序常量在 `WebAutoConfiguration`）：

| 顺序 |             过滤器             |                                      作用                                      |
|----|-----------------------------|------------------------------------------------------------------------------|
| 0  | `ExpressContentType.Filter` | 与 Express 一致的 `Content-Type`                                                 |
| 1  | `RequestContextFilter`      | Request ID（入站 `X-Request-Id` 恰有一个且格式合法时沿用，否则生成 UUID，回写响应头）、`Accept-Language` |
| 2  | `RequestLoggingFilter`      | pino-http 形状的请求日志，敏感字段替换为 `[REDACTED]`                                       |
| 3  | `CatchAllFilter`            | 兜底异常 → 500 信封                                                                |
| 10 | `CorsFilter`                | 生产按 `FRONTEND_DOMAIN` 白名单，其它环境回显来源；任何 `OPTIONS` 返回 204                       |
| 20 | `HttpMetricsFilter`         | HTTP 指标（`path` 为 Express 写法的路由模板，未匹配为 `<unmatched>`，客户端断开记 499）              |
| 30 | `SecurityHeadersFilter`     | 与 nestjs helmet 相同的头集合与取值                                                    |
| 40 | `JsonBodyFilter`            | 路由前读取 JSON 请求体：超过 256kb → 413，非法 JSON → 400                                  |
| 60 | `ExpressRoutingFilter`      | 按原始路径匹配：静态段不区分大小写、允许一个末尾斜杠；未匹配与方法不符都是 404 `Cannot <METHOD> <originalUrl>`    |

进入 `DispatcherServlet` 后：`ThrottleGuard`（GCRA 限流）→ `RouteAuthInterceptor`（鉴权 → 权限）→ 参数解析（`IdPathVariableResolver` 校验数字 `:id`，失败 400 `{id: "Invalid id"}`；`ValidatedBodyArgumentResolver` / `ValidatedQuery` 经 `ClassValidatorEngine` 按 `validation.json` 校验）→ controller → service。返回值由 `ApiResponseAdvice` / `VoidEnvelopeReturnValueHandler` 包成信封，异常由 `GlobalExceptionHandler` 翻译。Tomcat 层的错误（如请求行非法）由 `JsonErrorReportValve` 输出 JSON。

## 响应与错误

|   情况    |                                                                输出                                                                 |
|---------|-----------------------------------------------------------------------------------------------------------------------------------|
| 成功      | `{"code": 200, "message": "success", "data": ...}`，HTTP 200（`uploadFile` 为 201）；`void` 接口不输出 `data`                               |
| HTTP 异常 | `{"code": <状态>, "message": <译文>, "data": <对象或 null>}`，翻译规则同 nestjs AllExceptionsFilter（`ErrorTranslator`）                         |
| 业务错误    | `BusinessException(errorKey, details)`：`message` 为 `common.<errorKey>` 译文，`data` 为 `{...details, errorKey}`，状态码查 `BusinessErrors` |
| 校验失败    | 422，`message` 为 `common.unprocessableEntity` 译文，`data: {字段: "约束1, 约束2"}`                                                          |
| 唯一约束冲突  | 按约束名查 `UniqueConstraints`（复制自 nestjs `UNIQUE_CONSTRAINT_RESPONSES`）                                                               |
| 未知错误    | 500 `common.internalServerError`；设置 `SENTRY_DSN` 时上报                                                                              |

## 进程模型

同一 jar、同一镜像，按 `APP_ROLE` 运行不同角色（`cli/AppRole`，见 [cli.md](cli.md)）：

|    角色     |                                                    说明                                                    |
|-----------|----------------------------------------------------------------------------------------------------------|
| `api`（默认） | HTTP 服务；连接池上限 `DATABASE_MAX_CONNECTIONS`；`JOBS_IN_PROCESS` 为真时进程内运行 JobRunr（未设置时非生产为真，生产显式为 `true` 启动失败） |
| `worker`  | 无 Web 端口，运行 JobRunr background server 与定时任务；连接池上限 `WORKER_DATABASE_MAX_CONNECTIONS`                      |
| `migrate` | 一次性：Flyway 迁移 + JobRunr 建表后退出                                                                            |
| `seed`    | 一次性：写入种子数据后退出                                                                                            |

`server.shutdown=graceful`，`spring.lifecycle.timeout-per-shutdown-phase=30s`；worker 停机时等待在途任务至多 20s。

## 基础设施

|    能力     |                              位置                              |                                                                                要点                                                                                |
|-----------|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 缓存        | `common-redis` `RedisCache` / `MemoryCache`                  | 键与值格式同 nestjs Keyv；`REDIS_ENABLED` 不为 `true` 时为进程内实现（仅单实例开发 / 测试）                                                                                                |
| 锁         | `common-redis` `LockService`                                 | `lock:<key>`，`SET NX PX` + Lua 比较 token 释放，单条命令超时 1s；Redis 未启用或出错时回落进程内锁                                                                                         |
| 限流        | `common-redis` `throttle/`、`infrastructure/throttler`        | GCRA，键 `throttle:<sha256>:default:gcra`（哈希输入同 nestjs，handler 名取自 `openapi/nest_handlers.json`）；Redis 出错时回落进程内并计 `throttler_storage_fallback_total`               |
| 队列        | `infrastructure/queue`                                       | JobRunr，表在 `jobrunr` schema；队列 `mail`、`notification`、`account-cleanup`；默认 3 次尝试，退避 `1s × 2^(n-1)`（`QueueJobFilter`）；`notification` 由 `JobLimiter` 限制并发 5、每秒 10 个 |
| 定时任务      | `infrastructure/scheduler`                                   | 03:00 UTC 物理删除软删超过 7 天的会话、04:00 UTC 软删除 30 天前已读的通知、每小时第 25 分（Asia/Shanghai）账号清理补偿                                                                                |
| 日志        | `common-observability`                                       | Boot 结构化日志；生产 JSON，开发单行；MDC 带 `request_id`、`user_id`、`traceId` / `spanId`；`LOG_LEVEL`、`LOG_PRETTY_PRINT`                                                         |
| 指标        | `common-observability` `AppMetrics`、`infrastructure/metrics` | `/metrics`（`METRICS_PATH`，不带前缀、不包信封）：JVM 默认指标 + 与 nestjs 同名同标签同桶的十个自定义指标，样本带 `app=<APP_NAME>`；数据库耗时由 MyBatis 拦截器记录                                               |
| 健康检查      | `infrastructure/health`                                      | `/api/v1/health`（监控令牌）、`/health/live`、`/health/ready`、`/api/health`，形状同 @nestjs/terminus；`memory_heap` 阈值 `HEALTH_MEMORY_HEAP_THRESHOLD_MB`                      |
| 链路 / 错误上报 | `common-observability`                                       | `OTEL_ENABLED`（OTLP/HTTP，`OTEL_EXPORTER_OTLP_ENDPOINT`）；`SENTRY_DSN` 设置时启用                                                                                       |
| 审计        | `infrastructure/audit`                                       | `@Audit` 的接口成功后写 `audit_logs`，失败不影响响应                                                                                                                            |
| 告警        | `infrastructure/alerting`                                    | 写 `admin_alert`，按 dedupKey 去重，给 admin 角色用户发站内通知                                                                                                                  |
| 账号清理      | `infrastructure/accountcleanup`                              | 注销后提交时入队 `clean-account`，`AccountCleanupRunner` 按 order 执行全部 `AccountDataCleaner`；最终失败告警；每小时补偿                                                                   |
| 邮件        | `integrations/mail`、`common-mail`                            | 提交后入 `mail` 队列，worker 按请求语言渲染 nestjs 模板并发送；未设置 `MAIL_HOST` 时只写日志                                                                                                 |
| 文件        | `infrastructure/files`、`common-oss`                          | `FILE_DRIVER`：`local`（工作目录下 `files/`）、`s3`、`s3-presigned`；multipart 流式解析（Spring multipart 关闭）                                                                    |

## 配置

配置按分组拆成 `@ConfigurationProperties` record，`application.yml` 只用 `${ENV_NAME:default}` 读取环境变量，业务代码不直接读环境（ArchUnit）。变量名与 nestjs `env-example-relational` 一致，全部变量见 `.env.example`；`make dev` 经 Makefile 加载工作目录的 `.env`。

生产环境（`APP_ENV=production`）启动时额外要求：四个 JWT 密钥均 ≥32 字符且互不相同、`REDIS_ENABLED=true`、`MAIL_HOST` / `MAIL_DEFAULT_EMAIL` / `MAIL_DEFAULT_NAME` 必填、`JOBS_IN_PROCESS` 不为 `true`。`METRICS_TOKEN` 设置时至少 16 个字符。不满足时启动失败。

与 nestjs 的行为差异见 [contract.md](contract.md)。
