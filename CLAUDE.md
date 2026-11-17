# springboot-template

> 本文件面向 AI 助手（`AGENTS.md` 与本文件内容相同）。人类开发者请看 [README.md](README.md)。

Spring Boot 后端模板，与 nestjs-template（`release/exam-prep`）**API 契约一致**：vite-vue3-temp、uniapp-template 用 Orval 从 `/docs-json` 生成的前端可以无改动切换到本服务。
Java 25、Spring Boot 4.1.2、Maven 多模块，包名 `tech.xingyunqi.template`。完整方案与阶段划分见 `TEMPLATE_PLAN.md`，使用文档在 `docs/`。

## 命令：一律通过 Makefile

|                                  任务                                   |                    命令                     |
|-----------------------------------------------------------------------|-------------------------------------------|
| 安装 Git 钩子（prek）                                                       | `make install`                            |
| 启动本地 Postgres、Redis、Mailpit                                           | `make infra`                              |
| 迁移 / 种子数据 / 本地启动                                                      | `make migrate` / `make seed` / `make dev` |
| 按 nestjs tag 生成模块骨架（controller 桩、service、repository，并加入 `scope.yaml`） | `make module NAME=<包名> TAG=<tag>`         |
| 格式化                                                                   | `make fmt`                                |
| lint（spotless:check、enforcer、Checkstyle、typos）                        | `make lint`                               |
| 类型门禁（Error Prone + NullAway）                                          | `make type-check`                         |
| 单元测试 + ArchUnit                                                       | `make test`                               |
| 单元 + 集成测试（Testcontainers，需要 Docker）                                   | `make it`                                 |
| 契约测试                                                                  | `make test-contract`                      |
| CI 门禁（覆盖率行 ≥80%、分支 ≥70%，测试数量下限）                                       | `make test-ci`                            |
| E2E（构建镜像，compose 起完整服务栈后黑盒测试）                                         | `make e2e`                                |
| parity（先 `make nestjs-up`，本服务以限流开启运行在 3000）                           | `make test-parity`                        |
| 迁移校验                                                                  | `make migrations`                         |
| 同步共享产物                                                                | `make openapi-sync` / `make openapi-copy` |
| 本地 CI                                                                 | `make ci`                                 |

`make check` = spotless:check + 编译 + Checkstyle + 单元测试 + ArchUnit，是 pre-push 钩子。宣称改动完成之前，`make check`、`make it`、`make test-contract` 必须通过；涉及部署形态的改动再跑 `make e2e` 与 `make docker-build`。

`cp .env.example .env` 后可直接 `make infra`、`make migrate`、`make seed`、`make dev`；`.env.example` 的 `AUTH_*_SECRET` 是开发用占位值，生产要求见 `docs/deploy.md`。

## 契约：spec-first

- `openapi/nestjs-baseline.json` 是 nestjs `/docs-json` 的原始字节，`GET /docs-json` 原样返回。
- `openapi/{nestjs-baseline.json,validation.json,SOURCE,nest_handlers.json}`、`db/nestjs-schema.sql`、`scripts/{openapi-sync.sh,schema_snapshot.py,gen_validation_cases.py,gen_nest_handlers.py,nestjs/}`、`tests/contract/fixtures/validation-cases.json` 是与 fastapi-temp、go-template **逐字节相同的共享产物**，只能通过 `make openapi-sync` / `make openapi-copy` 更新，不得手改。接口变更先在 nestjs 落地。
- `openapi/scope.yaml` 列出本期已实现的 operationId（及 `permissions`、`undocumentedOperations`）；`template-contract` 只为它们生成 `*Api` 接口。新增实现时先把 operationId 加进去，再构建、实现。
- `openapi/codegen-overlay.yaml` 只作用于代码生成输入，不改变对外文档。
- 需要替换响应形状的 schema 由 `template-contract/pom.xml` 的 `schemaMappings` 映射到 `template-views` 的手写 record。
- 成功一律 HTTP 200（仅 `uploadFile` 为 201），body 为 `{code: 200, message: "success", data}`，`void` 接口不输出 `data`；生成接口不得带 `@ResponseStatus`。
- `docs/contract.md` 由 `OpenApiContractIT` 生成（`UPDATE_CONTRACT_DOC=1 make test-contract`），不手改。

## 分层

|                            位置                            |                                         内容                                          |
|----------------------------------------------------------|-------------------------------------------------------------------------------------|
| `template-common/*`                                      | 与业务无关的 starter（core、json、web、redis、mybatis、security、oss、mail、observability），以自动配置注册 |
| `template-app/.../core/{auth,users,permissions,session}` | 认证、用户、RBAC、会话；`domain/`、`persistence/`、端口接口、service、`web/`                          |
| `template-app/.../infrastructure/*`                      | throttler、health、metrics、queue、scheduler、audit、alerting、accountcleanup、files        |
| `template-app/.../integrations/*`                        | mail、sms、wechat、alipay、http                                                         |
| `template-app/.../modules/*`                             | 通用模块（service + repository 两层）                                                       |
| `template-app/.../cli`                                   | `AppRole`（`APP_ROLE`：api / worker / migrate / seed）、`Seeder`                        |

- controller 实现生成的 `*Api` 接口，只做 DTO ↔ domain 转换，不写业务；`..web..` 不访问 `..persistence..`；service 不依赖 `..web..` 与生成的 DTO；`..domain..` 不依赖 MyBatis、Spring Web、Jackson、Servlet（ArchUnit）。
- 每个 handler 恰有一个鉴权注解（`@PublicEndpoint`、`@AuthenticatedOnly`、`@OptionalAuth`、`@RefreshTokenAuth`、`@MonitoringToken`），缺失时启动失败；需要权限时加 `@PermissionsGuarded` + `@RequirePermissions` / `@RequireAnyPermission`，并与 `scope.yaml` 的 `permissions` 一致。
- 请求 DTO 的校验由 `ClassValidatorEngine` 按 `openapi/validation.json` 执行，不用 Bean Validation 注解。
- 配置只通过 `@ConfigurationProperties` record 读取，`application.yml` 只写 `${ENV:default}`，不分 profile 文件。
- Mapper 用注解 SQL（`@Select` 等），表名与 camelCase 列名加双引号（`"user"`、`"firstName"`）。

## 架构决策

- 不用 Spring Security 过滤器链、Sa-Token、JJWT：自写 jsonwebtoken 兼容的 HS256 签发 / HS256-512 校验（nestjs 的短密钥也能互认），鉴权在 Spring MVC `HandlerInterceptor` 中按注解执行；`spring-security-crypto` 只用于 argon2id / bcrypt。
- spec-first 而非 springdoc：`/docs-json` 输出基线原文（含本期未实现的接口，请求返回 404 信封）。
- 事务用 `@WriteTransaction`：成功提交，4xx（`HttpStatusException`）提交后再抛出，其它异常回滚；提交后副作用用 `AfterCommit`。
- 虚拟线程：连接池即数据库并发上限，同一请求持有连接时不得再申请第二个（不用 `REQUIRES_NEW`）；外部 HTTP 调用不放在事务内；密码哈希在事务外并受 `PASSWORD_HASH_CONCURRENCY` 限制。
- 队列与定时任务用 JobRunr（Postgres，`jobrunr` schema）；生产任务只在 `APP_ROLE=worker` 执行。
- 迁移由一次性 `APP_ROLE=migrate` 执行，api / worker 不跑 Flyway。
- Redis 键与 nestjs 逐字相同，不加前缀；限流为 GCRA，键带 `:gcra` 后缀。
- 镜像不写 Dockerfile `HEALTHCHECK`（worker / migrate 共用镜像且无端口），健康检查只配在 compose 的 api 服务（`java -cp /app/probe HealthProbe <url>`）。

## 禁止事项

- 手改共享产物、`docs/contract.md`、生成代码（`template-contract/target`）。
- 新增基线之外的端点；按 spec 的响应码设置 HTTP 状态（基线标 201 的 `createUser` 实际返回 200）。
- 直接使用 `@Transactional`；在 service 内捕获数据访问异常后改抛 `HttpStatusException`。
- 字段注入、`@Value` 注入、`System.getenv`、`System.out` / `System.err`。
- 引入 Hutool、Sa-Token、`org.dromara` 依赖（enforcer 与 ArchUnit 双重禁止）。
- 测试中跳过 Testcontainers（不用 `disabledWithoutDocker`）；用外层事务包裹依赖提交语义的测试。
- 降低 JaCoCo 阈值或 `ci/test-floor.properties` 下限来让 CI 通过。

## 注释规范

- 禁止段落式注释：不复述代码在做什么，不写"为什么不用另一种写法"的论证，不在 JSDoc 里写背景故事。
- 注释只描述**当前**代码，不写变更历史：禁止"原来/之前/现在改/不再/已修复/回归/新增了/移除了"等字眼；变更原因交给 commit message。
- 测试用例名只陈述行为，不写"回归 / 防止 / 修复"。
- 通过 Agent 派发的子任务同样受本规则约束，派发时必须把本规则原文写进提示词，并要求其收尾时统计新增注释行数。

Java 中的对应：

- 「JSDoc」对应 Javadoc。
- 「测试用例名」对应测试方法名与 `@DisplayName`。

Checkstyle（`config/checkstyle/checkstyle.xml`）检查注释中的变更历史字眼与测试名中的「回归 / 防止 / 修复」；prek pre-commit 钩子对 Java 注释做同样的检查。

## 提交与推送

- **只有用户当次明确说「提交 / commit」才能提交**。"开始做吧""继续""按这个改""可以"都只是让你写代码，不是让你提交。
- 一次提交授权只覆盖那一批改动，**不延续到后续任务**。下一轮要提交必须重新拿到许可。
- 改完停在工作区，汇报改了什么 + 验证结果，等用户 review。想提交就问一句，别自己决定。
- push 同理，且比 commit 更严：没听到"推 / push"就不要推。
- 通过 Agent 派发的子任务同样受本规则约束，派发时必须把本规则原文写进提示词。

提交信息为 conventional commits（类型同 nestjs commitlint，标题 ≤72 字符），由 commit-msg 钩子检查。
