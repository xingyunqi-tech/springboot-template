# springboot-template 改造方案

> 目标：在 springboot-template（全新仓库）建立 Java 后端模板；dromara/RuoYi-Vue-Plus 6.1.0（`github.com/dromara/RuoYi-Vue-Plus`（分支 6.X，提交 `835246e`））只作参考（§2.2）：
>
> 1. 技术栈取当前最新稳定版（JDK 取最新 LTS）；
> 2. 与 nestjs-template **API 契约一致**，vite-vue3-temp、uniapp-template 通过 Orval 生成的前端可无改动切换；在不破坏契约的前提下，采纳比 nestjs-template 更好的方案（§5）；
> 3. 工程配置采用业界当前最佳实践（§10），测试分五层（§15）；
> 4. CI 结构参照 nestjs-template（§11）。
>
> 编写日期：2026-11-10，2026-11-11 按评审意见修订。版本数据来自 Maven Central / endoflife.date / GitHub API / Docker Hub 查询结果（见附）。契约基准：nestjs-template `release/exam-prep` @ `02fdb4d`。2026-11-11 第二轮修订（C3–C11、响应可空性）。
> 章节编号与 fastapi-temp/TEMPLATE_PLAN.md 对齐（§4 / §6 / §7 / §9 / §11 为多后端共用契约），测试策略追加为 §15。
> 2026-11-11 第三轮修订：按 fastapi-temp M0–M2 实现（`db14f72`、`fc385b7`、`303bf57`）中确认的契约细节，修订事务规则（§5 第 8 项）、§4.1 路由 / 请求体 / 安全头 / CORS、§6 令牌与错误文本、§4.3「共享产物」与校验执行器、`db/nestjs-schema.sql` 生成方式、测试隔离与本机 Docker（§15）、阶段流程（§12）。go-template/TEMPLATE_PLAN.md 同步修订。
> 2026-11-11 第四轮修订：按 fastapi-temp M3（基础设施，`303bf57` 之后的未提交工作区，仍在编辑中）与 nestjs `02fdb4d` 源码逐项核对，修订限流细则（§4.1）、files / health / metrics 契约（§4.6）、§5 第 4 项、§8 基础设施（含锁改为与 nestjs 同格式的 `SET NX`）、§9 变量、§12 M3 验收、§14 风险、§15 Redis / 文件 / 邮件测试隔离。go-template/TEMPLATE_PLAN.md 同步修订。

---

## 0. 现状

| 项 | 状态 |
|---|---|
| 本仓库 | 2026-11-11 新建的空仓库（`git init -b main`），只有本文档，无提交、无远程 |
| 参考仓库 | `dromara/RuoYi-Vue-Plus`（GitHub 镜像，主仓在 Gitee），分支 6.X，参考提交 `835246e`「发布 6.1.0 正式版」（2026-11-04）；本地不保留克隆，需要时按该提交在线查阅 |
| RuoYi 栈 | Java 21、Spring Boot 4.1.2、Jackson 3、MyBatis-Plus 3.5.18 + MP-Join + dynamic-datasource、Sa-Token 1.46.0（含 sa-token-jwt）、Redisson 4.8.0 + lock4j、SnailJob 2.0.2、warm-flow 2.1.1、Hutool 5.8.47、MapStruct-Plus、springdoc 3.1.1 + therapi-javadoc |
| RuoYi 模块 | `ruoyi-admin`、`ruoyi-api`（37 类）、`ruoyi-common/*` 24 个 starter + bom、`ruoyi-modules/*`（system 148 类、workflow 120 类、gen、job、demo、ai）、`ruoyi-extend/*`（Spring Boot Admin、SnailJob Server、SnailAI Server） |
| RuoYi 领域 | `sys_user / sys_role / sys_menu / sys_dept / sys_post / sys_dict_* / sys_config / sys_oss* / sys_client / sys_social / sys_oper_log / ...` snake_case 表；四种数据库的 SQL；6.x 已去掉多租户 |
| RuoYi 契约形态 | `R<T> {code, msg, data}`，错误也走 HTTP 200；分页 `PageResult {total, rows}`；校验错误拼成一条字符串；Sa-Token 不透明 token；客户端密码 RSA 加密传输 |
| 与本模板的差距 | 契约相关部分（响应、异常、认证、用户、会话、权限、分页、OpenAPI）全部不可用；看似可复用的 starter 也深度耦合：core / json / redis / mybatis / oss / mail / web 七个模块 170 个主代码类中 56 个直接 import Hutool（mail 模块 3 个类全部依赖 Hutool）；`InjectionMetaObjectHandler` 依赖 Sa-Token `LoginHelper` 并填充 `createBy / createDept`；对应测试依赖 Hutool、MP-Join、`pageNum` 分页 |

结论：不在 RuoYi 代码树上改造，**从 Spring Initializr 新起**（§2.2）。参考上游仓库 `github.com/dromara/RuoYi-Vue-Plus`（分支 6.X，提交 `835246e`），最多借鉴 `RedisConfig` 的 Jackson 3 编解码写法与 S3 预签名封装思路（按 MIT 署名）。

---

## 1. 范围

### 1.1 本期做（对齐 nestjs-template `src/core` + `src/infrastructure` + 通用模块）

- 统一响应 / 错误 / 校验错误格式、i18n、Request ID、CORS、安全头、限流
- 认证：邮箱、手机号密码、短信验证码、微信（小程序 / App / 手机号）、支付宝；JWT 双令牌 + 刷新轮换 + 会话
- 用户管理、RBAC 权限、角色 / 状态
- 文件上传（local / s3 / s3-presigned）、私有文件访问
- 健康检查、Prometheus 指标、结构化日志、Sentry、OpenTelemetry
- Redis 缓存 + 分布式锁、任务队列、定时任务、审计日志、告警、账号清理
- 邮件（注册确认、找回密码；复用 nestjs 的 Handlebars 模板，中英双语）
- Seed 数据：角色、状态、权限、管理员与示例用户，账号与 nestjs seed 一致，前端模板开箱可登录
- 通用业务模块：config、regions、notifications、addresses、favorites、feedbacks、consents、app-profile
- 模块代码生成器（对应 nestjs 的 `generate:resource`）
- Docker / Compose 分环境、deploy/manage.sh、GitHub Actions

### 1.2 本期不做

nestjs-template `src/modules` 里的考试刷题业务：exams、questions、practice、papers、imports、errata、wrong-book、study-stats、question-notes、ai、game-auth、monitor。orders、payments、shares 见 §13 待决策。这些接口仍出现在 `/docs-json`（§4.3），本服务对其返回 404 信封，并在 `docs/contract.md` 列出。

RuoYi 自带的部门 / 岗位 / 菜单 / 字典后台、数据权限、工作流、SnailJob、Spring Boot Admin、AI / MCP、Elasticsearch、MQTT、LiteFlow、Excel、接口加解密、脱敏、字典翻译、推送、JustAuth、验证码均不引入。

---

## 2. 技术栈（当前最新稳定版）

| 类别 | 选型 | 版本 | 说明 |
|---|---|---|---|
| 语言 | Java | **25 LTS**（Temurin 25.0.5+8） | Boot 4.1 官方支持 Java 17–27；25 为最新 LTS（Temurin 支持到 2031-09）；27 为非 LTS。开启虚拟线程，见 §5 第 13 项 |
| 构建 | Maven + Maven Wrapper | 3.10.0 / 3.3.4 | Maven 4 仍是 4.0.0-rc-7 |
| 框架 | Spring Boot | 4.1.2（2026-10-22） | 4.2.0-RC2 已出，GA 后由 Renovate 升级；4.1 支持到 2027-07 |
| | Spring Framework | 7.0.10（Boot 管理） | |
| Web | Spring MVC + 内嵌 Tomcat | Tomcat 11.0.27（Boot 管理） | `spring.threads.virtual.enabled=true` |
| JSON | Jackson 3 | 3.1.7（Boot 管理） | 包名 `tools.jackson` |
| 校验 | 请求：自写 class-validator 执行器（`validation.json` 驱动，含 validator.js 移植）；配置：Jakarta Validation（Hibernate Validator） | Boot 管理 | 请求 DTO 不用 Bean Validation 注解，见 §4.3 |
| 契约 | openapi-generator-maven-plugin（`spring` 生成器） | 7.27.0（2026-11-02） | spec-first，见 §4.3 |
| 认证 | 自写 jsonwebtoken 兼容 JWT（HMAC）+ Spring MVC 拦截器；`spring-security-crypto` 只用于密码哈希 | 7.1.2 | 见 §6.0 |
| 密码哈希 | Spring Security `Argon2PasswordEncoder` + `BCryptPasswordEncoder` | 7.1.2；BouncyCastle 1.86 | 见 §5 第 1 项 |
| ORM | **MyBatis**（经 `mybatis-plus-spring-boot4-starter` 接入） | 3.5.18.1（2026-10-27） | Mapper 用注解 SQL（`@Select` 等）+ record，不用 MP 实体、`BaseMapper`、`@TableName` / `@TableLogic`；MP 只提供 `SqlSessionFactory` 与时间戳自动填充；见 §2.1、§7 |
| 迁移 | **Flyway** | 12.4.0（Boot 管理；最新 13.12.1） | 跟随 Boot BOM，不单独覆盖 |
| 驱动 / 连接池 | PostgreSQL JDBC + HikariCP | 42.7.14 / Boot 管理 | |
| 数据库 | PostgreSQL | 18（18.7） | 独立部署用 18；与 nestjs 共库、parity 对照时用 17（nestjs CI 为 17.11）；镜像标签以 Docker Hub 实际可用为准，测试与 go-template / fastapi-temp 相同用 `postgres:18.6-alpine` / `17.11-alpine` |
| 缓存 / 锁 / 限流 | Redis + Spring Data Redis（Lettuce，Boot 管理）；不引入 Redisson：锁与 GCRA 脚本都用 Spring Data Redis 的 `RedisScript` 执行，各用独立 Lettuce 连接（单条命令超时：锁 1s、限流 500ms） | 8.10（8.10.3） | M2 的缓存（`common-redis` 的 `RedisCache`，键与值格式同 nestjs Keyv）用 `StringRedisTemplate`；`REDIS_ENABLED` 不为 `true` 时换成进程内 `MemoryCache`（最多 1 万项，仅开发 / 测试），见 §8 |
| 队列 + 定时 | **JobRunr**（`jobrunr-spring-boot-4-starter`） | 9.0.3（2026-10-29；9.0.4 已于 2026-11-12 发布，由 Renovate 升级） | PostgreSQL 存储，重试 + 指数退避 + 集群内单实例 recurring job |
| HTTP 客户端 | Spring `RestClient` | Framework 7.0.10 | 微信、支付宝、阿里云短信 |
| 支付宝 | alipay-sdk-java | 4.40.1067.ALL | RSA2 签名 |
| 对象存储 | AWS SDK for Java v2（S3） | 2.57.13 | 阿里云 OSS 走 endpoint |
| 邮件 | spring-boot-starter-mail + Handlebars.java | Boot 管理 / 4.5.8（排除 `nashorn-core`，不用 JS helper） | 直接复用 nestjs `.hbs` 模板 |
| 日志 | Logback + Boot 结构化日志（logstash JSON） | Logback 1.5.38 | Boot 内置 |
| 指标 | Micrometer + Prometheus registry | 1.17.2（Boot 管理） | 指标名与 nestjs 一致，见 §8 |
| 链路 | `spring-boot-starter-opentelemetry` + micrometer-tracing-bridge-otel | 4.1.2 / 1.7.2 | OTLP 导出，`OTEL_ENABLED` 开关 |
| 错误上报 | sentry-spring-boot-4-starter | 8.63.0 | |
| 对象映射 | 不引入 | — | DTO ↔ domain、行 record ↔ domain 转换手写 |
| 空安全 | JSpecify | 1.0.1 | Spring 7 / Boot 4 已全面使用 JSpecify 注解 |
| 测试 | JUnit Jupiter / Mockito / AssertJ | 6.0.3 / 5.23.0 / 3.27.7（Boot 管理） | 最新分别为 6.1.3 / 5.24.0，跟随 Boot |
| 测试容器 | Testcontainers + `@ServiceConnection` | 2.0.5 | 见 §15 |
| 架构测试 | ArchUnit | 1.5.1 | |
| 覆盖率 | JaCoCo | 0.8.16 | `report-aggregate` |
| 格式化 | Spotless + palantir-java-format | 3.11.0 / 2.103.0 | |
| 静态检查 | Error Prone + NullAway；Checkstyle（只做注释 / 测试名规则） | 2.52.0 + 0.14.3；14.4.0 | |
| 依赖漏洞 | Trivy（`fs` 扫描源码依赖 + `image` 扫描镜像）+ CycloneDX SBOM | 0.76.2（与 nestjs 同一镜像）/ 2.9.3 | |
| 镜像 | 多阶段 Dockerfile，Boot layered jar，`eclipse-temurin:25.0.5_8-jre-noble` | — | |
| Git 钩子 | prek | 0.5.10 | 与 fastapi-temp 统一 |
| 依赖更新 | Renovate | — | 一份配置覆盖 Maven、Maven Wrapper、Docker 镜像、GitHub Actions |

### 2.1 不选的方案

| 方案 | 原因 |
|---|---|
| Sa-Token | 令牌模型（不透明 token + Redis 会话）与 nestjs 的 access / refresh JWT + 数据库会话 + hash CAS 轮换不一致，sa-token-jwt 也无法产出 `{id, role:{id}, sessionId}` 载荷，用它只剩注解；Spring Security 在 Boot BOM 内，版本与安全公告随 Boot 走 |
| JJWT | 0.13.0 后（2025-08）无发布；HMAC 签发 / 校验用 JDK `Mac` 自写即可（§6.0） |
| springdoc（code-first） | schema 名、paths 顺序、OpenAPI 3.0 写法与 nestjs 逐项对齐成本高，且无法输出本服务未实现的考试接口，见 §4.3 |
| Spring Data JPA / Hibernate | 共库时表结构归 nestjs，JPA 的实体状态管理、脏检查在「刷新令牌 CAS」「部分唯一索引」场景下反而绕；团队熟悉 MyBatis-Plus |
| jOOQ | 类型安全最好，但代码生成要连库或解析 DDL，模板使用者学习成本高；列入 §13 待决策 |
| Liquibase | XML / YAML changelog 比纯 SQL 繁琐；Flyway 有 Boot 一等支持 |
| SnailJob | 需要单独部署 SnailJob Server 和其库表 |
| Spring `@Scheduled` + ShedLock + Redis Stream 自写队列 | 重试、退避、死信、看板都要自写；JobRunr 一并提供 |
| lock4j、Redisson（`RLock` / `RRateLimiter` / `RScript`） | 锁的 Redis 键与值须与 nestjs 相同（`lock:<key>` 字符串 + `SET NX PX`，§8），`RLock` 的哈希结构不兼容；限流算法用 GCRA（§5 第 4 项），且响应头需与 nestjs 一致；锁与 GCRA 的 Lua 脚本用 Spring Data Redis `RedisScript` 即可执行，不需要 Redisson |
| sms4j | 3.3.5 后（2025-05）无发布；nestjs 只用阿里云 Dysms，`RestClient` + 签名即可 |
| JustAuth / WxJava | nestjs 只用到 jscode2session、App OAuth、手机号解密三处，`RestClient` 直调；WxJava Central 上近期均为时间戳式版本号 |
| Hutool、MapStruct-Plus、Fory | 依赖面大；JDK 25 + commons-lang3 + MapStruct 足够 |
| Jib | 无 Docker 也能构建是优点，但 deploy.yml 需要 buildx 多架构 + SBOM / provenance attestation，与 nestjs 流程一致更重要；开发机已有 Docker，本地用同一 Dockerfile 构建 |
| GraalVM native image | MyBatis 动态代理 / XML 映射、Redisson、JobRunr 都依赖反射，需要大量 reachability metadata；构建需要本机 Docker 或 GraalVM，CI 时间翻倍；长驻 API 不需要毫秒级启动。启动优化改用 JDK 25 的 AOT cache，M5 评估 |
| Gradle | 团队都用 Maven，无强理由切换 |
| Maven 4 | 仍是 RC |
| OWASP dependency-check | 依赖 NVD 全量库下载，国内网络下常超时，且需要 NVD API key |
| OSV-Scanner | 无 CVSS 阈值参数，做 high 及以上门禁要自己解析 JSON；Trivy `fs` 有 `--severity`，且镜像已在 CI 固定 |
| lefthook | 能力相当，选 prek 与 fastapi-temp 统一 |
| Dependabot | 不支持跨生态分组和自动合并策略，Renovate 更灵活 |

### 2.2 起点：Spring Initializr 新起，RuoYi 只作参考

| 维度 | 在 RuoYi 上裁剪改造 | Spring Initializr 新起（建议） |
|---|---|---|
| 可复用代码 | 名义上 core / json / redis / mybatis / oss / mail / web 约 170 个类，实际 56 个依赖 Hutool（模板禁用），mybatis 自动填充依赖 Sa-Token 并写 `createBy / createDept`，分页是 `pageNum`；去耦后剩下的主要是 `RedisConfig` 的 Jackson 3 编解码和 S3 预签名封装 | 新写；上述两处按 MIT 署名借鉴 |
| 需要删除 | 约 750 个类、四套 SQL、三个 extend 服务、RuoYi 专用 Agent / 工具配置 | 无 |
| 契约相关代码 | 全部重写 | 全部新写 |
| 测试 | 67 个测试类中可留的同样依赖 Hutool / MP-Join / `pageNum`，且 Javadoc 含「防止…」等违反注释规范的写法，需逐个改 | 按 §15 新写 |
| 残留风险 | XSS 过滤、Long 转字符串、`createBy` 填充等隐性行为容易漏删 | 无 |
| Git 历史 | 5430 个与模板无关的上游提交 | 干净 |

做法：本仓库为全新仓库（§13 第 2 项已定），用 Initializr 生成 Boot 4.1.2 / Java 25 / Maven 骨架，按 §3 建模块。RuoYi 只作为上游仓库 `github.com/dromara/RuoYi-Vue-Plus`（分支 6.X，提交 `835246e`）查阅，不合并、不拷贝成片代码。

---

## 3. 目录结构

```
springboot-template/
├── pom.xml                              # parent：spring-boot-starter-parent、插件管理、质量门禁、profiles（ci / e2e / parity）
├── .mvn/{wrapper/maven-wrapper.properties, jvm.config, maven.config}
├── template-common/
│   ├── template-common-core/            # BusinessException、错误键表、通用工具
│   ├── template-common-json/            # Jackson 3 配置、UtcInstant 序列化、Md5 / LowerCase 反序列化器
│   ├── template-common-web/             # 信封、返回值处理器、全局异常、422 映射、i18n、Request ID、CORS、安全头、分页
│   ├── template-common-redis/           # 缓存、SET NX 锁、GCRA 限流存储
│   ├── template-common-mybatis/         # MyBatis 配置、时间戳自动填充、`@WriteTransaction` / `AfterCommit`
│   ├── template-common-security/        # JWT、路由鉴权拦截器与注解、权限注解、密码哈希（不用 Spring Security 过滤器链）
│   ├── template-common-oss/             # FILE_DRIVER=local|s3|s3-presigned
│   ├── template-common-mail/            # 邮件发送 + Handlebars 模板
│   └── template-common-observability/   # 日志脱敏、指标、OTel、Sentry
├── template-contract/                   # openapi-generator 产物：*Api 接口 + DTO（target/generated-sources，不入库）
│   └── src/main/templates/              # 覆盖的 api.mustache（去掉 @ResponseStatus，见 §4.1）
├── template-views/                      # 手写响应视图 record（User 等带 @Expose 分组的 schema），经 schemaMappings 替换生成类
├── template-app/
│   ├── src/main/java/<pkg>/
│   │   ├── TemplateApplication.java
│   │   ├── cli/                         # AppRole、Seeder、CliCommands（`APP_ROLE=create-superuser --email=<email>`，密码读 stdin 第一行；`APP_ROLE=permissions-clear-cache`；退出码 0 成功 / 1 失败 / 2 用法错误，语义同 go-template）
│   │   ├── core/{auth,users,permissions,session}/
│   │   ├── infrastructure/{database,cache,lock,queue,scheduler,throttler,health,metrics,audit,alerting,accountcleanup,files}/
│   │   ├── integrations/{mail,sms,wechat,alipay}/
│   │   └── modules/                     # config、regions、notifications、addresses、favorites、feedbacks、consents、appprofile
│   └── src/main/resources/
│       ├── application.yml              # 只读环境变量，无 profile 分文件
│       ├── db/migration/                # Flyway V*.sql
│       ├── i18n/{en,zh}/*.json          # 复用 nestjs 翻译文件
│       └── mail-templates/              # 复用 nestjs .hbs
├── tests/
│   ├── fixtures/nestjs/                 # 从 nestjs 录制的响应样本（按视图），用于键存在性 / null 断言
│   ├── contract/fixtures/validation-cases.json  # 共享产物：nestjs oracle 校验用例（§4.3）
│   ├── e2e/                             # 黑盒 E2E（-Pe2e）
│   └── parity/                          # 双后端对照（-Pparity），用例与 fastapi-temp tests/parity 一一对应
├── tools/
│   ├── openapi-tools/                   # codegen 输入叠加（可空性、schemaMappings）、契约比对（构建期 Java 程序）
│   └── generator/                       # 模块代码生成器 + FreeMarker 模板
├── openapi/
│   ├── nestjs-baseline.json             # 共享产物：nestjs /docs-json 原样快照，也是本服务 /docs-json 的输出
│   ├── validation.json                  # 共享产物：nestjs 侧脚本导出的 class-validator / class-transformer 元数据
│   ├── SOURCE                           # 共享产物：repository / branch / commit / validation_script / environment
│   ├── scope.yaml                       # 本期实现的 operationId 白名单（只用于代码生成与契约测试）
│   └── codegen-overlay.yaml             # 只作用于 codegen 输入：响应字段可空 / 非必填修正、schemaMappings 登记
├── db/nestjs-schema.sql                 # 共享产物：nestjs 迁移后的规范化结构快照（每个对象一行，§7）
├── docker/                              # compose 分环境文件
├── deploy/
├── scripts/                             # 含共享的 openapi-sync.sh、schema_snapshot.py、gen_validation_cases.py、gen_nest_handlers.py、nestjs/{validation-cases.ts,nestjs.compose.yaml}
├── .github/{workflows, renovate.json5, zizmor.yml, ISSUE_TEMPLATE}
├── Dockerfile  .dockerignore  .env.example  .editorconfig  .gitattributes
├── .vscode/{settings,extensions}.json
├── Makefile                             # 统一入口：make dev / migrate / seed / check / test / it / test-contract / e2e / test-parity / ci / openapi-sync / ...
├── README.md  CLAUDE.md  AGENTS.md  LICENSE（MIT）  NOTICE（借鉴 RuoYi-Vue-Plus 的文件及其 MIT 署名）
└── docs/
```

`<pkg>`、groupId 见 §13 第 7 项。

### 3.1 模块内分层（对应 nestjs 六边形架构）

```
core/users/
├── domain/              # 纯领域对象（record），不引用 MyBatis / Spring Web
├── persistence/         # UserMapper（注解 SQL，行 record）、UserRepositoryImpl（行 ↔ domain 手写转换）
├── UserRepository.java  # 端口接口
├── UserService.java     # 只依赖端口
└── web/                 # UsersController implements 生成的 UsersApi；DTO ↔ domain 映射
```

规则同 nestjs CLAUDE.md：domain 不引用 ORM；controller 不写业务；配置只从 `@ConfigurationProperties` 读取。ArchUnit 强制（§15.6）。

### 3.2 RuoYi 参考价值

| RuoYi 位置 | 处理 |
|---|---|
| `ruoyi-common-redis` `RedisConfig` | 不使用：本服务不引入 Redisson，缓存值为 nestjs Keyv 格式的 JSON 字符串，用 Spring Data Redis 的 `StringRedisTemplate` 读写 |
| `ruoyi-common-oss` 客户端 / 预签名 | 参考 AWS SDK v2 预签名封装思路，新写，NOTICE 署名 |
| `ruoyi-common-mybatis` `MybatisPlusConfig` | 仅参考插件注册顺序（分页、乐观锁）；自动填充新写为 `createdAt / updatedAt` |
| `ruoyi-admin/Dockerfile` | ZGC 参数可参考；镜像与启动方式按 §10.3 新写 |
| 其余模块、SQL、脚本、`.claude/agents`、`.codex/skills`、67 个测试类 | 不使用 |

---

## 4. API 契约（与 nestjs-template 对齐）

### 4.1 全局

| 项 | 约定 |
|---|---|
| 前缀 | `/api/v1/...`（`API_PREFIX`，默认 `api`）；`GET /`、`GET /api/health`、`GET /metrics`（`METRICS_PATH`）不带版本 |
| 端口 | 默认 `APP_PORT=3000`，与 nestjs 及两个前端的 Orval target / dev 环境（`localhost:3000`）一致 |
| 成功响应 | `{"code": 200, "message": "success", "data": <payload>}`；**所有成功请求 HTTP 200（含 POST），唯一例外是 `POST files/upload` 返回 HTTP 201**（nestjs 该路由未加 `@HttpCode`，e2e 断言 201），体内 `code` 仍为 200。基线里用 `@ApiCreatedResponse` 标注 201 的 operation（如 `createUser`）在 nestjs 实际都带 `@HttpCode(200)`，返回 200，**不得按 spec 响应码设置状态**；`void` 接口不输出 `data` 键 |
| 已是信封的返回 | 原样透传 |
| 跳过包装 | 方法级 `@RawResponse`：`/metrics`、SSE、支付回调 |
| 错误响应 | `{"code": <HTTP 状态>, "message": <已翻译文本>, "data": <对象或 null>}`，无独立业务码；`message` / `data` 的翻译规则见下方「错误翻译规则」 |
| 默认消息 | 400/401/403/404/409/410/422/429/500 → i18n `common.<key>`（`badRequest`、`unauthorized`、`forbidden`、`notFound`、`conflict`、`gone`、`unprocessableEntity`、`tooManyRequests`、`internalServerError`）；表外状态码（如 413、503）输出 `HttpStatus` 枚举名且不翻译（`PAYLOAD_TOO_LARGE`、`SERVICE_UNAVAILABLE`）；429 只带 `Retry-After`、不带 `X-RateLimit-*`，`THROTTLE_ENABLED=false` 时不发任何限流头（§4.1「限流细则」） |
| 业务错误 | `throw new BusinessException(errorKey, details)` → `message` 为 `common.<errorKey>` 的翻译，`data: {...details, "errorKey": key}`（不翻译），状态码查表（键表从 nestjs `business-error-keys.ts` 复制） |
| 校验错误 | 422，`message` 为 `common.unprocessableEntity` 的翻译（en `Validation failed` / zh `数据验证失败`），`data: {字段: "约束消息, 逗号拼接"}`：同字段所有失败约束都输出，按 class-validator 装饰器顺序（在 `@Transform` 之后执行）以 `", "` 拼接，约束与消息来源见 §4.3 的 `validation.json`；嵌套对象为嵌套 map；顶层字符串值同样按翻译规则尝试 `common.<value>`；未知字段剔除不报错 |
| 非法 `:id` | 400，`message` 为 `common.badRequest` 译文，`data: {"id": <common.invalidId 译文>}`（en `Invalid id` / zh `ID 格式不正确`），对应 nestjs `ParseIdPipe`（`^\d+$`、1 ≤ id ≤ 2147483647）；路径参数类型转换失败一律走这里，绝不返回 422 |
| 路由匹配 | 按 Express 语义：`API_PREFIX` 下及排除路由（`/`、`/metrics`）的未匹配路由（含路径存在但方法不符）→ **404**（不是 405，不带 `Allow` 头），`message` 为 Nest 原文 `Cannot <METHOD> <originalUrl>`（含查询串，无翻译键，原文输出），`data: null`；尾斜杠宽容（`/users/` 等同 `/users`，不重定向）；路径匹配不区分大小写（Express 5 默认关闭 `case sensitive routing`）；按原始（百分号编码）路径匹配，匹配前不解码（`/api/%68ealth` 不命中 `/api/health`，返回 404），路径参数在匹配后再解码；其它路径（如 `/nope`、`POST /docs-json`）nestjs 返回 Express 默认 HTML 404，不属于契约，本服务统一返回同样的 JSON 信封，parity 不比对 |
| 唯一约束冲突 | 按 nestjs `src/common/utils/exceptions.util.ts` 的 `UNIQUE_CONSTRAINT_RESPONSES` 原样映射（见下表），未列出的约束 → 409；`data` 同样按翻译规则处理 |
| 语言 | 见 §4.2 |
| Request ID | 入站 `X-Request-Id` **恰有一个**且匹配 `^[\w.:-]{1,128}$` 则沿用，否则（缺失、多个同名头、格式不符）生成 UUID；写日志、审计，并回写响应头。Node 把多个同名头合并为 `a, b`，必然不匹配正则，因此多个头等同于非法 |
| CORS | 生产按 `FRONTEND_DOMAIN` 白名单，`credentials: true`，允许方法 `GET/POST/PUT/PATCH/DELETE/OPTIONS`、允许头 `Content-Type`、`Authorization`、`Accept-Language`；非生产反射来源与请求头，允许方法为 `cors` 包默认值 `GET,HEAD,PUT,PATCH,POST,DELETE`，预检响应 `Vary: Origin, Access-Control-Request-Headers`；两种环境 `exposedHeaders` 均为`X-Request-Id`、`Retry-After`、`X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset`；CORS 在路由之前处理，**任何 `OPTIONS` 请求（含未知路径、无 `Origin`）都返回 204 空体**，不进入路由与 404 |
| 安全头 | 与 nestjs helmet 配置输出相同的头集合与取值（`Content-Security-Policy`、`Cross-Origin-Resource-Policy: cross-origin`、`Origin-Agent-Cluster`、`Referrer-Policy`、`Strict-Transport-Security`、`X-Content-Type-Options`、`X-DNS-Prefetch-Control`、`X-Download-Options`、`X-Frame-Options`、`X-Permitted-Cross-Domain-Policies`、`X-XSS-Protection`），**HSTS 在所有环境都发送**；取值从 nestjs 实际响应复制，parity 逐头比对；Spring Security 默认头（`Cache-Control`、`Pragma`、`Expires`、`X-Frame-Options: DENY` 等）全部关闭，由自写过滤器输出上述集合 |
| 请求体 | JSON 上限 256kb，超限 413 `{code: 413, message: "request entity too large", data: null}`（body-parser 原文，不翻译）；JSON 非法或顶层为原始值（`null`、字符串、数字等）→ 400、`data: null`，`message` 为解析器错误文本，由各实现决定，parity 只比对 `code` 与 `data`；**请求体解析在路由与鉴权之前**（与 Express 全局 body-parser 一致）：未知路径带非法 JSON 返回 400 而不是 404，鉴权路由不带令牌、请求体非法返回 400 而不是 401；契约只约束 `application/json`，其它 content-type（nestjs 另接受 urlencoded 表单）不属于契约，本服务按空对象校验 |
| 限流 | 规则同 nestjs（`THROTTLE_ENABLED` 开关，默认 `THROTTLE_LIMIT=100` 次 / `THROTTLE_TTL=60`s，鉴权路由按用户、否则按 IP，IPv6 按子网，客户端 IP 按 `TRUST_PROXY` 跳数解析），算法见 §5 第 4 项；放行时带 `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`，429 只带 `Retry-After`；契约约定头名、出现条件与整数格式，具体数值随算法不同可不一致；细则见下方「限流细则」 |
| 字段命名 | camelCase（生成的 DTO 字段名即 OpenAPI 属性名） |

**错误翻译规则**（照抄 nestjs `src/common/filters/all-exceptions.filter.ts`，翻译文件为 `src/i18n/{en,zh}/common.json`；「尝试翻译」指 `common.<key>` 存在则用译文，否则保留原值）：

| 异常 | `message` | `data` |
|---|---|---|
| 429 限流 | `common.tooManyRequests` | `null` |
| `BusinessException` | `common.<errorKey>` | `{...details, errorKey}`，不翻译 |
| 其它 HTTP 异常，带自定义 `message`（如 `ForbiddenException('Missing permissions: …')`、未匹配路由的 `Cannot <METHOD> <url>`） | 尝试翻译 `message`，无对应键时原文输出 | `errors` 字段，无则 `null` |
| 其它 HTTP 异常，无自定义 `message`，`error` 不是默认描述（如 `throwNotFoundError('sessionNotFound')`） | 尝试翻译 `error`（→ `Session not found` / `会话不存在`） | 同上 |
| 其它 HTTP 异常，无 `error` / `message` 或为默认描述 | 状态码默认消息 | 同上 |
| 唯一约束冲突 | 映射表有 `error` 时尝试翻译它，否则状态码默认消息 | 映射表的 `errors` |
| 未知异常 | `common.internalServerError` | `null` |

非 `BusinessException` 的 HTTP 异常与唯一约束冲突，`data` 中**顶层字符串值**尝试翻译（`{email: 'notFound'}` 输出为 `{email: 'Not found'}` / `{email: '资源不存在'}`），数字等非字符串值与嵌套 map 原样输出。下文 §6 中写作 `{field: 'key'}` 的错误均指翻译前的键。

**唯一约束映射**（照抄 nestjs `UNIQUE_CONSTRAINT_RESPONSES`）

| 约束名 | HTTP | 输出 |
|---|---|---|
| `UQ_e12875dfb3b1d92d7d7c5377e22`（user.email） | 422 | `data: {email: emailAlreadyExists}` |
| `user_phone_key` | 422 | `data: {phone: phoneAlreadyExists}` |
| `user_wechatOpenId_key` | 422 | `data: {wechatOpenId: wechatOpenIdAlreadyExists}` |
| `user_alipayOpenId_key` | 422 | `data: {alipayOpenId: alipayOpenIdAlreadyExists}` |
| `REL_75e2be4ce11d447ef43be0e374`（user.photo） | 422 | `data: {photo: alreadyExists}` |
| `UQ_favorite_user_target` | 409 | `message` = `common.alreadyFavorited` 的翻译 |
| `UQ_question_subjectId_contentHash` | 409 | `message` = `common.duplicateQuestion` 的翻译（本期无该表，保留映射） |
| `UQ_order_orderNo`、`UQ_share_shareCode` | 500 | 默认 500 消息 |
| 其它 | 409 | 默认 409 消息 |

Java 侧捕获 `DuplicateKeyException`，从 `PSQLException.getServerErrorMessage().getConstraint()` 取约束名查表。

**限流细则**（nestjs `src/infrastructure/throttler/`、@nestjs/throttler 6.7.1 `ThrottlerGuard`）

| 项 | 规则 |
|---|---|
| 作用范围 | 全局守卫：所有已匹配路由都计数，包括 `/api/v1/health*`、`/api/health`、`/`、`METRICS_PATH`、`GET files/:path`（nestjs 无 `@SkipThrottle`）；未匹配路由（404）与 `OPTIONS` 预检不计数；在 JWT 鉴权之前执行，因此实现为排在首位的 `HandlerInterceptor`（`ThrottleGuard`，handler 已确定、`RouteAuthInterceptor` 与参数解析之前）；Swagger `/docs`、`/docs-json` 在 nestjs 中不经守卫，不计数（`@SkipThrottle`） |
| 规则 | 默认 `THROTTLE_LIMIT` 次 / `THROTTLE_TTL` 秒；路由级覆盖见 §4.6 auth 表「限流」列（`@Throttle({default: {limit, ttl}})`）；`THROTTLE_ENABLED=false` 时完全跳过，不输出任何限流头 |
| 计数主体 | 路由声明 `@AuthenticatedOnly`（对应 nestjs `AuthGuard('jwt')`）且 `Authorization` 为 `Bearer <token>`、令牌用 `AUTH_JWT_SECRET` 验签且未过期时为 `user:{id}`（只验签，不查会话）；其余情况（含可选鉴权、refresh、监控令牌路由，令牌无效或过期）为 `ip:{normalizeIp(ip)}` |
| `normalizeIp` | IPv4 原样；去掉 IPv6 zone（`%eth0`）；`::1` 原样；IPv4 映射地址（`::ffff:a.b.c.d`）转为 IPv4；其余 IPv6 按 /64 取网络地址，输出小写 RFC 5952 压缩形式加前缀（`2001:db8:abcd:12:1:2:3:4` → `2001:db8:abcd:12::/64`；`Inet6Address.getHostAddress()` 不压缩零段，需自写格式化）；非法字符串原样。用例照搬 fastapi-temp `tests/unit/test_observability.py::TestNormalizeIp` |
| 响应头 | 放行时写入 `X-RateLimit-Limit`（本条规则的 limit）、`X-RateLimit-Remaining`、`X-RateLimit-Reset`（秒，整数），之后鉴权 / 校验失败的 401 / 403 / 422 响应同样带这三个头；超限 429 只带 `Retry-After`（秒，整数），不带 `X-RateLimit-*`；body `{code: 429, message: common.tooManyRequests 译文, data: null}` |
| Redis 键 | `throttle:` + `sha256hex("<Controller>-<handler>-default-<tracker>")` + `:default`（nestjs 另有 `…:default:block` 封禁键）；本服务的 GCRA 状态写在该键加后缀的 `throttle:<sha>:default:gcra`，不写 nestjs 的 `INCR` 计数键（否则 nestjs 对它 `INCR` 报错而降级或误封）；共用 Redis 时 Python、Go、Java 三个 GCRA 后端共用 `:gcra` 键、共同计数，nestjs 用固定窗口键、单独计数。`<Controller>-<handler>` 是 nestjs 的类名与方法名，按 operationId 查表：读取共享产物 `openapi/nest_handlers.json`（如 `loginWithEmail` → `AuthController-login`、`healthCheck` → `HealthController-check`、指标端点 → `MetricsController-index`、`GET files/:path` → `FilesLocalController-download`），表外路由回落为 operationId |
| Redis 故障 | 单条命令超时 500ms；出错时本次改用进程内计数，每次回落 `throttler_storage_fallback_total` +1；首次降级记 error 日志并告警（type `throttler`、level CRITICAL、dedupKey `throttler-storage-fallback`），恢复时记 info 日志；`REDIS_ENABLED=false` 时直接用进程内计数 |
| GCRA 计算 | 全程用整数微秒：Lua 中 `now = TIME[1] × 1000000 + TIME[2]`（约 1.8e15，小于 2^53，在 Lua 5.1 的 double 中仍是精确整数），进程内回落实现用 `long` 微秒；`period = ttl × 1000000`、`interval = period / limit` 向下取整到整数微秒（误差每次不足 1µs，`limit` 次请求占用不超过 `period`，不会多拒），`period` 与 `interval` 由 Java 算好后作为整数参数传入脚本。Redis 中 TAT 的存储单位与 fastapi-temp（`src/app/infrastructure/throttler/storage.py`）一致，三个后端共用同一个键，单位不同会让对方把 TAT 读成约 1000 倍：写入为「毫秒、最多 3 位小数」的十进制字符串（微秒 / 1000，如 `268382706.344`；Lua 中 `string.format('%.3f', tat / 1000)`）；读取时把十进制毫秒字符串四舍五入到整数微秒（fastapi 写入的值可能超过 3 位小数，如 `268382706.34399077`；Lua 中 `math.floor(tonumber(v) * 1000 + 0.5)`）；过期时间统一为 `PX ceil(TAT − now)`（毫秒，向上取整，与 fastapi-temp / go-template 相同）。判定 `wait = (max(tat, now) + interval) − period − now`，`wait > 0` 才拒绝，`Retry-After = ceil(wait / 1000000)` 秒；放行时 `X-RateLimit-Reset` 同样向上取整到秒。不能写成 `allowAt = (tat + interval) − period` 再比较 `allowAt > now`：用浮点毫秒时 `(now + period) − period` 可能因舍入略大于 `now`，`limit=1` 时全新键的第一个请求被误判为 429（fastapi-temp be36777 修复的问题，反例 `now = 268382706.34399077` ms、`period = 60000`、`limit = 1`）。不引入 Bucket4j 等库；若 M3 改用现成库，须确认键仍为 `throttle:<sha>:default:gcra`、响应头符合上方规则。单元测试：`limit=1` 的全新键第一个请求放行；上述反例 `now` 作为固定用例（换算为微秒后，内存实现与 Lua 脚本各跑一遍）；读取 fastapi 格式的 TAT 字符串（如 `268382706.34399077`）后判定正确，写回的值为 3 位小数毫秒串 |

**Java 侧实现**

| 项 | 做法 |
|---|---|
| 信封（有返回值） | `ApiResponseAdvice implements ResponseBodyAdvice<Object>` 写 `{code: 200, message, data}`；已是 `ApiResponse` 透传；Jackson 转换器排在 `StringHttpMessageConverter` 之前，避免 `String` 返回值被包装后类型不符 |
| 信封（`void`） | 生成器在 `useResponseEntity=false` 时给方法加 `@ResponseStatus`，而 `ServletInvocableHandlerMethod` 遇到「返回 null + 有 `@ResponseStatus`」会直接结束请求、输出空体，`ResponseBodyAdvice` 不会执行。做法：`template-contract` 用 `templateDirectory` 覆盖 `api.mustache` 去掉 `@ResponseStatus`；`template-common-web` 注册排在首位的 `VoidEnvelopeReturnValueHandler`，对 `@RestController` 中返回 `void` 的方法写 `{"code":200,"message":"success"}` |
| HTTP 状态 | 覆盖模板去掉所有生成的 `@ResponseStatus`，spec 中的 201 / 204 文档响应码不会进入代码，默认 200；`POST files/upload` 的实现方法单独标 `@ResponseStatus(HttpStatus.CREATED)`（有返回值，正常写体）；集成测试遍历 scope 内全部 operation 断言状态码（只有 upload 为 201） |
| 异常 | `@RestControllerAdvice GlobalExceptionHandler` 处理 MVC 内异常；Security 的 `AuthenticationEntryPoint` / `AccessDeniedHandler`、请求体超限都调用同一个 `ErrorResponseWriter`（拦截器中的鉴权与限流异常经 `GlobalExceptionHandler` 输出），保证过滤器层与控制器层错误形状、翻译规则一致 |
| 422 | 由 `ClassValidatorEngine`（§4.3「校验」）在绑定生成 record 之前对已解析的 JSON 树执行，抛出的校验异常交给 `ErrorResponseWriter`：嵌套对象为嵌套 map、数组下标作 key，与 class-validator 一致；同字段所有失败约束按装饰器顺序（`@Transform` 之后）以 `, ` 拼接，消息为 `validation.json` 中的自定义 `message` 或 `defaultMessage` 模板；类型错误（如 `must be a string`）与其它约束消息一起输出，不由 Jackson 短路；422 的 `data` 是校验消息，按 nestjs 规则同样尝试 `common.<value>` 翻译。请求 DTO 不使用 Bean Validation（`useBeanValidation=false`），`MethodArgumentNotValidException` 等不会出现在请求路径上 |
| 未知字段 | `FAIL_ON_UNKNOWN_PROPERTIES=false`（Boot 默认），等价 whitelist 剔除 |
| 非法 `:id` | 路径参数 `id` 的 `MethodArgumentTypeMismatchException`、`MissingPathVariableException`、方法参数校验失败（`HandlerMethodValidationException` 中来源为 `@PathVariable` 的项）→ 400 `{id: invalidId}`（翻译前的键，输出为译文）；只有请求体 / 查询参数的错误进入 422 |
| 方法不匹配 | `HttpRequestMethodNotSupportedException` → 404 信封（`message` 为 `Cannot <METHOD> <originalUrl>` 原文，`data: null`），不输出 `Allow` 头 |
| 尾斜杠 | 注册 `UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest()`（Spring Framework 6.2+），去掉尾斜杠后再匹配 |
| 原始路径匹配 | Spring MVC `PathPattern` 按解码后的段（`PathContainer.PathSegment.valueToMatch()`）比较，`/api/%68ealth` 会命中 `/api/health`；在 DispatcherServlet 之前的过滤器中，若请求的原始路径（`getRequestURI()`）与解码后路径在静态段上不同，按原始路径重新判断是否命中某个 handler 模板，未命中则直接输出 404 信封；大小写不敏感用 `PathPatternParser.setCaseSensitive(false)`，尾斜杠处理同样不得解码路径。M1 按 Spring Framework 7.0.10 源码核对，并以 parity 用例 `/api/%68ealth`、`/API/HEALTH`、`/api/health/` 验证 |
| 请求体前置解析 | 排在 DispatcherServlet 之前的 `JsonBodyFilter`：`application/json` 请求读取并缓存原始体（超过 256kb → 413，非法 JSON / 顶层原始值 → 400），解析结果放入请求属性供执行器与绑定复用；Tomcat 的 `maxPostSize` 与 Boot 的 multipart 上限另行配置，不影响 JSON |
| OPTIONS | 最高优先级的 CORS 过滤器对所有 `OPTIONS` 请求直接写 204（按 §4.1 CORS 规则输出头），不交给 DispatcherServlet |
| 未实现的基线接口 | 无 handler → 与 nestjs 未知路由相同的 404 信封（`Cannot <METHOD> <originalUrl>`）；完整清单由契约测试生成并写入 `docs/contract.md`，与实际不符时测试失败 |

### 4.2 语言头：统一使用 `Accept-Language`

| | Accept-Language | x-custom-lang |
|---|---|---|
| 性质 | HTTP 标准头（RFC 9110），支持 q 值多语言优先级 | nestjs-boilerplate 自定义头 |
| 浏览器 | 自动携带用户系统语言，首次访问即可用 | 必须前端手动设置 |
| 缓存 / CDN | 可用标准 `Vary: Accept-Language`（本契约不发送，见下） | 需自定义 Vary |
| CORS | 属于安全列表头，不触发额外预检 | 自定义头，跨域时触发预检 |

**所有模板统一使用 `Accept-Language`，不使用 `x-custom-lang`。** 后端按 q 值匹配已支持语言（`zh-CN` 等区域标签宽松匹配到 `zh`），匹配不到回落 `APP_FALLBACK_LANGUAGE`。OpenAPI 全局参数声明 `Accept-Language`（基线已含）。nestjs 响应不带 `Content-Language`，也不带 `Vary: Accept-Language`（只有 CORS 中间件加的 `Vary: Origin`），本服务同样不发送这两项；`Content-Language` 不属于契约，parity 比对时排除；`Vary` 只按 §4.1 CORS 规则在 CORS 用例中比较。

Java 侧：自定义 `LocaleResolver`（`Locale.LanguageRange.parse` + `Locale.lookup`）；`MessageSource` 自定义实现直接读取复用的 nestjs `i18n/{en,zh}/*.json`，键形如 `common.notFound`，不转成 `.properties`；「键不存在」与「译文为空」都视为无翻译，与 nestjs `translateMessage` 一致。

配套仓库已同步（nestjs-template、uniapp-template、vite-vue3-temp），本仓库无需额外改动。

### 4.3 OpenAPI（Orval 依赖）：spec-first

**决策：spec-first。** nestjs 的 `/docs-json` 是唯一事实来源，本服务不在运行时生成文档。

| 对比项 | spec-first（openapi-generator） | code-first（springdoc + 契约测试） |
|---|---|---|
| paths 顺序、operationId、schema 名 | 直接输出基线文件，天然一致 | 需逐项注解 + 自定义排序，契约测试兜底 |
| 本期未实现的考试接口 | 基线里仍有，前端照常生成 `CreatePaperDto`、`AdminQuestionDto`、`generatePaper`、`getPracticeSessions` 等 | 文档里没有，前端重新生成后按名字引用的类型消失、编译失败 |
| OpenAPI 3.0 写法（`nullable: true`） | 原样 | springdoc 3.x 默认输出 3.1 |
| 校验约束 | nestjs 未启用 swagger 插件，基线不含 class-validator 约束，由 nestjs 侧导出 `validation.json` 补齐，执行器按它校验（见下） | 写在手写 DTO 上 |
| 响应可空性 | nestjs DTO 的可空字段（如 `User.email: string \| null`）在基线中既无 `nullable: true` 又列为 required，带 `@Expose({groups})` 的字段在部分视图中不存在；生成 record 直接用于响应会出错，需要叠加修正或手写视图（见下） | 手写 |

openapi-generator 7.27.0 的 `spring` 生成器提供 `useSpringBoot4`、`useJackson3`、`useRecords`、`interfaceOnly`、`skipDefaultInterface`、`x-field-extra-annotation`、`x-jackson-json-include-policy`、`openapiNormalizer FILTER`、`templateDirectory`，未发现阻碍项，与 Go 模板一致采用 spec-first。

**流水线**（`tools/openapi-tools` 为普通 Java 程序，构建期由 exec-maven-plugin 调用，无网络依赖）：

1. **`make openapi-sync`**：执行共享脚本 `scripts/openapi-sync.sh`，在 nestjs 固定提交上重新导出下方「共享产物」全部文件，一起提交；`make openapi-copy` 从同级 fastapi-temp 复制同一批文件（不重跑）
2. **服务**：`GET /docs-json` 返回 classpath 上的 `nestjs-baseline.json` **完整原文，逐字节一致**（`application/json`，不经过 Jackson 重新序列化）；`/docs` 用 Scalar 静态页指向 `/docs-json`；`SWAGGER_ENABLED` 控制，生产默认关闭
3. `openapi/scope.yaml`：本期实现的 operationId 白名单，**只用于代码生成与契约测试**，不影响 `/docs-json`
4. **叠加** → `target/openapi/codegen.json`（只用于代码生成，不对外输出）：应用 `codegen-overlay.yaml`：对响应 DTO 中实际可空的字段补 `nullable: true`、对不总是输出的字段移出 `required`；`@JsonInclude` 用原生 `x-jackson-json-include-policy`。请求校验不经过生成注解（见下「校验」）
5. **生成**（template-contract 模块）：`generatorName=spring`，`interfaceOnly=true`、`skipDefaultInterface=true`、`useSpringBoot4=true`、`useJackson3=true`、`useRecords=true`、`useBeanValidation=false`、`useResponseEntity=false`、`openApiNullable=false`、`documentationProvider=none`、`annotationLibrary=none`、`useTags=true`、`templateDirectory=src/main/templates`、`openapiNormalizer=FILTER=operationId:<scope.yaml 中的列表>`；产物放 `target/generated-sources`，不入库

**其余约束**

- 响应 schema 为**未包装的 DTO**（前端 mutator 自己剥 `.data`）；信封在运行时加，不出现在 spec
- 安全方案 `bearer`；全局 header 参数 `Accept-Language`
- **schema 名与 nestjs DTO 类名一致**：生成器直接用 spec 中的 schema 名作类名
- 查询参数生成为方法上的独立 `@RequestParam`，与 Orval 的 `XxxParams` 对应；`page` / `limit` 空串视为缺省（Spring `defaultValue` 对空值同样生效）
- 文件上传：基线以 local 驱动导出，`POST files/upload` 为 `multipart/form-data`、字段名 `file`；与 nestjs `FilesModule` 相同，按 `FILE_DRIVER` 只注册一个上传 controller（`@ConditionalOnProperty`）。local / s3 实现生成接口 `FilesApi`，`codegen-overlay.yaml` 对 `uploadFile` 设 `requestBody: null`，生成的方法不含 `MultipartFile` 参数（Spring multipart 关闭时该参数无法解析），请求体在 handler 内流式读取；`FILE_DRIVER=s3-presigned` 时该路径改收 `application/json` `{fileName, fileSize}`，由手写 controller 在同一路径实现（不加 `consumes`，非 JSON 请求体与 nestjs 一样按 `FileUploadDto` 校验失败），列入契约反向检查白名单并在 `docs/contract.md` 说明
- **paths 顺序与 nestjs 一致**：`/docs-json` 输出的就是基线原文
- **响应视图**：带 `@Expose({groups: ['me', 'admin']})` 的 schema（`User` 等）不用生成 record，经 `schemaMappings`（`template-contract/pom.xml`）映射到 `template-views` 中的手写类。`User` 新建时关联只含 `{id}` 且未传时键缺省、已加载时带 `__entity`，`@JsonView` 无法表达，因此 `UserView` 为按序构造的键值对（`@JsonValue`），由 `core/users/web/UserViews` 按 me / admin / public 分组生成；键顺序与基线不同的 `LoginResponseDto` 用带 `@JsonPropertyOrder` 的 record。其它可空问题用 `codegen-overlay.yaml` 修正。所有映射在 `docs/contract.md` 登记，并有 §15.3 的键存在性测试
- 编译期保障范围：`skipDefaultInterface=true` 只能发现「已有 controller 漏实现某个方法」；整个 `*Api` 接口没有实现类时由 §15.4 的 handler 映射检查发现
- 契约测试见 §15.4

**共享产物**（Python / Go / Java 三个仓库同名、同路径、逐字节相同，Make 目标同名）

| 产物 | 内容 | 生成方 |
|---|---|---|
| `openapi/nestjs-baseline.json` | nestjs `/docs-json` 原文（`openapi: 3.0.0`，`NODE_ENV=development`、`FILE_DRIVER=local`） | `openapi-sync` 在 nestjs 固定提交上启动服务后下载 |
| `openapi/validation.json` | nestjs `scripts/export-validation.ts` 输出，结构见下「校验」 | nestjs 脚本 |
| `openapi/SOURCE` | 五行 `key=value`：`repository=xingyunqi-tech/nestjs-template`、`branch=release/exam-prep`、`commit=<契约提交，当前 02fdb4d…>`、`validation_script=<export-validation.ts 所在提交，当前 8b5f296…>`、`environment=NODE_ENV=development FILE_DRIVER=local` | `openapi-sync` |
| `db/nestjs-schema.sql` | nestjs 迁移后、§7 所列 18 张表的规范化结构快照：每个列 / 约束 / 索引 / 注释一行（`column t.c <type> NULL\|NOT NULL [DEFAULT …] [COMMENT …]`、`constraint t.name <def>`、`index t.name <def>`、`comment t '…'`），排序后输出 | fastapi-temp `scripts/schema_snapshot.py`（查询 `pg_catalog`，不用 `pg_dump`） |
| `tests/contract/fixtures/validation-cases.json` | 约 4516 条校验用例（`{id, dto, payload, expected}`，`expected` 为 `{ok: true, value}` 或 `{ok: false, status, errors}`），期望结果由真实 class-validator / class-transformer 跑出（nestjs oracle） | fastapi-temp `scripts/gen_validation_cases.py` 生成输入，`scripts/nestjs/validation-cases.ts` 在 nestjs builder 镜像中执行 |
| `scripts/nestjs/nestjs.compose.yaml` | nestjs 参照服务（compose project 默认 `nestjs-openapi-sync-<仓库目录名>`，可用 `NESTJS_SYNC_PROJECT` 覆盖；postgres 17.11 / redis 7.4.12 / mailpit，api 映射宿主 `NESTJS_PORT`，默认 3001，端口被占用时 `openapi-sync.sh` 报错退出；迁移 → `ENV_MODE=dev` seed → 启动） | fastapi-temp |
| `openapi/nest_handlers.json` | operationId → nestjs `<Controller>-<handler>` 映射（221 条；`uploadFile` 不在表内，运行时按 `FILE_DRIVER` 对应 `FilesLocalController` / `FilesS3Controller` / `FilesS3PresignedController`），用于限流键（§4.1「限流细则」） | `scripts/gen_nest_handlers.py`，由 `openapi-sync` 调用 |
| `tests/parity` 用例 | 用例清单（请求、比对规则）与 fastapi-temp `tests/parity`（`test_global_contract.py`、`test_auth_parity.py`）一一对应，用例 id 相同；目标地址 nestjs 默认 3001、本服务默认 3000 | fastapi-temp 为基准，各仓库逐条移植，不共用数据文件（§13 第 18 项） |

归属：契约内容的唯一来源是 nestjs（`02fdb4d` 契约提交 + `export-validation.ts`）；生成工具（`scripts/openapi-sync.sh`、`scripts/schema_snapshot.py`、`scripts/gen_validation_cases.py`、`scripts/gen_nest_handlers.py`、`scripts/nestjs/validation-cases.ts`、`scripts/nestjs/nestjs.compose.yaml`）由 fastapi-temp 维护，本仓库原样复制，不做改动；Python 脚本使用 PEP 723 内联依赖、以 `uv run --script` 执行，在任何仓库只需安装 uv 即可原样运行，路径不依赖 fastapi-temp 的 `src/app` 布局。`make openapi-sync` 与 fastapi-temp 同名同义，执行复制来的 `scripts/openapi-sync.sh`（依赖 Docker 与 uv）；`make openapi-copy` 从同级 fastapi-temp 复制共享产物与共享脚本（`FASTAPI_DIR`）；两种方式的产物都必须与 fastapi-temp 中的逐字节相同。`export-validation.ts` 目前只在 nestjs 本地提交 `8b5f296`（`release/exam-prep` 领先 origin 1 个提交，未推送）：本地 `openapi-sync` 从 `NESTJS_DIR` 读取即可，CI 中 checkout 并固定 `validation_script` 提交要求该提交已推送到 origin，推送前 CI 只能使用入库产物。

**校验：`openapi/validation.json` + nestjs oracle 用例**

class-validator 的行为是：先执行 `@Transform`，再按装饰器顺序检查**全部**约束，失败消息用 `", "` 拼接；字符串规则来自 validator.js。`validation.json` 实际结构（136 个 DTO）：

```
{generator, classValidator: "0.15.1", classTransformer: "0.5.1",
 pipe: {whitelist, forbidNonWhitelisted, forbidUnknownValues, transform, enableImplicitConversion, errorHttpStatusCode},
 classes: {<Dto>: {source, extends, initialValues,
   properties: {<field>: {designType, transforms: [{function, source, options}], transformsToPlain,
     type, expose, exclude,
     validations: [{type, key, name, constraints, message, messageFunction, defaultMessage,
                    each, groups, always, context, validateIf, validationTypeOptions}]}}}}}
```

- `template-common-web` 的 `ClassValidatorEngine` 按该文件复刻整个 nestjs `ValidationPipe`（class-transformer `plainToInstance` + class-validator）：输入为 `JsonBodyFilter` 解析出的 JSON 树（`JsonNode`）与查询参数，输出转换后的 JSON 树，再由 Jackson 绑定到生成的 record；包括 JS 值语义（`undefined` 与 `null` 区分、字符串长度按 UTF-16 码元计、`@Transform` 中的 JS 运算）、`extends` 合并父类字段、`initialValues` 默认值；默认消息按 `defaultMessage` 模板（`$property`、`$constraint1` …）生成
- **不用 Bean Validation 注解近似**：Hibernate Validator 的 `@Email`、`@NotBlank`、`@Size`、`@Pattern` 等与 validator.js 的 `isEmail`、`isNotEmpty`（只拒绝 `''`、`null`、`undefined`，空白串通过）、`isLength`、`matches` 语义不同；Bean Validation 不保证约束顺序、类型错误在 Jackson 绑定时就会短路。文件中出现的约束（`isEmail`、`isUrl`、`isUuid`、`isIso8601`、`isDateString`、`isLength`、`isNumber`、`isInt`、`isEnum`、`matches`、`nestedValidation`、`conditionalValidation`、`whitelistValidation` 等 32 种）按 validator.js / class-validator 源码逐个移植（fastapi-temp 为此完整移植了 validator.js 13.15.35，见其 `src/app/common/validation/validator_js.py`）；文件中出现但未实现的约束，启动时失败
- **门禁**：`tests/contract/fixtures/validation-cases.json` 的约 4516 条用例全部通过（逐条比对 `ok`、转换后的 `value`、422 的 `errors`），是 M1 验收项；执行器是 M1 中工作量最大的一项，按此预留时间

### 4.4 序列化细节

nestjs 用 class-transformer + `JSON.stringify` 输出，Jackson 默认行为在以下几处不同，需显式对齐：

| 项 | nestjs 输出 | Jackson 默认 | 对齐方式 |
|---|---|---|---|
| 时间 | `2026-11-10T12:00:00.000Z`（`toISOString`，毫秒、UTC、`Z`） | `Instant` 输出纳秒精度，位数不固定 | 自定义 `UtcInstantSerializer`：截到毫秒、固定 3 位、以 `Z` 结尾；DTO 时间字段统一 `Instant` |
| 日期 | `birthday` 为 `YYYY-MM-DD` | `LocalDate` 相同 | 无需处理 |
| 数据库时间列 | `timestamp`（无时区，TypeORM `CreateDateColumn` 默认，默认值 `now()`） | — | 表结构与 nestjs 相同（`db/nestjs-schema.sql`，不改为 `timestamptz`）；所有数据库连接固定会话时区 UTC：JDBC URL 加 `options=-c%20TimeZone=UTC`（pgjdbc 默认按 JVM 时区设置会话时区，测试 JVM 不一定是 UTC），镜像另设 `-Duser.timezone=UTC`；`LocalDateTime` ↔ `Instant` 在 mapper 层按 UTC 转换 |
| `undefined` 字段 | 不输出该键 | 输出 `null` | 按 nestjs 实际输出逐字段确认：可空字段输出 `null`；未设置的可选字段在 `codegen-overlay.yaml` 中设 `x-jackson-json-include-policy: NON_NULL` |
| 基线可空性 | `string \| null` 字段在基线中无 `nullable`、且列为 required | 生成 record 按非空必填处理 | `codegen-overlay.yaml` 补 `nullable` / 去 required；分组 schema 用手写视图 |
| 分组字段 | `@Expose({ groups: ['me', 'admin'] })` 控制可见性，不在分组内时该键不存在 | 无分组概念 | 手写视图 record + `@JsonView`（见 §4.3），不靠 service 置 null |
| 头像 | `photo.path` 为完整 URL | — | 映射时按 `FILE_DRIVER` 拼接 `BACKEND_DOMAIN` 或 S3 地址 |
| 金额 | int（分） | — | 一律 `int` / `long`，不用 `BigDecimal` / `double` |
| 大整数 | number | — | id 保持 number，不做 Long 转字符串 |

对照方法：同一份 seed 数据下，用 §11.1 的 parity job 对比两个后端的响应体。

### 4.5 分页

- 请求：`page`（≥1，默认 1）、`limit`（≥1，默认 10，路由上限 50 / 100），空串视为缺省；部分列表支持 JSON 字符串 `filters`、`sort`
- 标准响应：`{list, total, page, limit, totalPages}`
- 无限滚动（仅 `GET /users`）：`{data, hasNextPage}`
- 排序：按请求的 `sort` 字段依次排序，未包含主键时最后追加主键升序（`id ASC`），保证分页结果稳定、与 nestjs 相同数据下顺序一致；`sort` 中出现不存在的字段时与 nestjs 相同按未知异常处理（TypeORM `EntityPropertyNotFoundError` 不是 HTTP 异常，经过滤器输出 500）

Java 侧：`PageQuery(page, limit)` → MP `Page`；`PageResponse.of(IPage)` 计算 `totalPages`。

### 4.6 本期端点

**auth**（全部 200；登录类接口响应 `data` 键顺序为 `{refreshToken, token, tokenExpires(ms), user}`（nestjs `createSessionAndTokens` 的字面量顺序），对应 record 用 `@JsonPropertyOrder` 固定；`refreshToken` 接口为 `{token, refreshToken, tokenExpires}`）

| 方法 | 路径 | operationId | 鉴权 | 限流 / 说明 |
|---|---|---|---|---|
| POST | auth/email/login | loginWithEmail | – | 5/60s |
| POST | auth/email/register | registerWithEmail | – | 默认 |
| POST | auth/email/confirm | confirmEmail | – | |
| POST | auth/email/confirm/new | confirmNewEmail | – | |
| POST | auth/forgot/password | forgotPassword | – | 3/300s |
| POST | auth/reset/password | resetPassword | – | 5/300s |
| GET | auth/me | getCurrentUser | JWT | |
| PUT | auth/me | updateCurrentUser | JWT | 10/60s |
| DELETE | auth/me | deleteCurrentUser | JWT | 请求体 `AuthDeleteAccountDto` |
| POST | auth/refresh | refreshToken | refresh JWT（Bearer，无 body） | 返回 `RefreshResponseDto {token, refreshToken, tokenExpires}` |
| POST | auth/logout | logout | JWT | `void`，响应体 `{"code":200,"message":"success"}` |
| POST | auth/phone/send-code | sendSmsCode | – | 3/60s |
| POST | auth/phone/login | loginWithPhone | – | 5/60s |
| POST | auth/phone/sms-login | loginWithSms | – | 5/60s |
| POST | auth/phone/register | registerWithPhone | – | 3/60s |
| POST | auth/phone/reset-password | resetPasswordByPhone | – | 5/300s |
| PUT | auth/password | changePassword | JWT | 3/60s |
| POST | auth/wechat/login | loginWithWechat | – | 10/60s |
| POST | auth/wechat/phone-login | loginWithWechatPhone | – | 10/60s |
| POST | auth/alipay/login | loginWithAlipay | – | 10/60s |
| GET | auth/sessions | getMySessions | JWT | 返回 `[{id, userAgent, ip, createdAt, lastActiveAt, current}]` |
| DELETE | auth/sessions/:id | revokeMySession | JWT | 非本人会话 404 `sessionNotFound` |
| POST | auth/sessions/revoke-others | revokeOtherSessions | JWT | |

**users**（JWT + 权限，admin 视图）

| 方法 | 路径 | operationId | 权限 |
|---|---|---|---|
| POST | users | createUser | user:create |
| GET | users | getUsers（无限滚动） | user:list |
| GET | users/:id | getUser | user:read |
| PUT | users/:id | updateUser | user:update |
| DELETE | users/:id | deleteUser | user:delete |

**files**：`POST files/upload?purpose=...`（**HTTP 201**；local / s3 返回 `{file}`；s3-presigned body `{fileName, fileSize}`，返回 `{file, uploadSignedUrl}`，见 §4.3）、`GET files/private/:id`（uuid，非法值按 nestjs 内置 `ParseUUIDPipe` 返回 400，`message` 为原文 `Validation failed (uuid is expected)`（无翻译键），`data: null`）、local 驱动 `GET files/:path`。细则（nestjs `src/modules/files/`）：

| 用途 `purpose` | 允许扩展名 → `mimeType` | 可见性 | 所需权限 | 文件归属 | 大小上限（默认） |
|---|---|---|---|---|---|
| `image`（缺省或空串时按它处理） | jpg / jpeg → `image/jpeg`，png、gif、webp | PUBLIC | – | 上传者 | `FILE_IMAGE_MAX_SIZE`（5MB） |
| `private-image` | jpg / jpeg、png、webp | PRIVATE | – | 上传者 | `FILE_PRIVATE_IMAGE_MAX_SIZE`（10MB） |
| `content-image` | 同 image | PUBLIC | `content:manage` | 无（`ownerUserId` null） | `FILE_CONTENT_IMAGE_MAX_SIZE`（5MB） |
| `audio` | mp3 → `audio/mpeg`，m4a → `audio/mp4` | PUBLIC | `content:manage` | 无 | `FILE_AUDIO_MAX_SIZE`（20MB） |
| `import` | zip、xlsx、json | PRIVATE | `content:manage` | 上传者 | `FILE_IMPORT_MAX_SIZE`（50MB） |
| `export` | pdf | PRIVATE | `content:manage` | 上传者 | `FILE_EXPORT_MAX_SIZE`（20MB） |

- `mimeType` 只按扩展名（最后一个 `.` 之后，转小写）查上表，不看客户端 `Content-Type`；存储键为 21 位小写十六进制随机串 + `.` + 扩展名（nestjs `randomStringGenerator` = `uid(21)`），文件名中的目录部分丢弃
- 处理顺序（local / s3，与 nestjs 守卫 → 拦截器 → 管道一致）：限流 → JWT（无令牌 401，先于一切用途检查）→ 用途守卫（`purpose` 非法或重复 → 422 `{purpose: 'invalidFilePurpose'}`；缺权限 → 403 `Missing permissions: content:manage`；`Content-Length` > 用途上限 + 16KiB → 413）→ 流式接收 multipart（只接受字段名 `file` 的单个文件，见下）→ 查询参数校验（`purpose=""` 时用途守卫按 image 放行，此处才按 `UploadFileQueryDto` 返回 422 `{purpose: 'purpose must be one of the following values: image, private-image, content-image, audio, import, export'}`，已写入的文件删除）→ 无文件 → 422 `{file: 'selectFile'}`；文件大小 > 用途上限 → 413，删除已写入的文件
- multipart 语义照 multer 2.5.0 + busboy：单文件上限为全部用途上限的最大值，超出时中止读取并 413；扩展名不在用途表内 → 422 `{file: 'cantUploadFileType'}`（读到该文件头时即拒绝）；多个 `file` 或其它字段名的文件 → 400 `Unexpected file field - <name>`；空文件名的文件部分、非 multipart 请求体（JSON、空体）都视为没有文件；`multipart/form-data` 缺 boundary → 400 `Multipart: Boundary not found`；畸形头 / 截断 → 400 `Multipart: Malformed part header` / `Multipart: Unexpected end of form`；允许首个分隔符前的 preamble；非 `form-data` 的 multipart → 415。错误文本以 fastapi-temp `src/app/modules/files/uploads.py` 与 `tests/parity/test_files_parity.py::test_upload` 为准（移植到本仓库 `FilesParityIT` 后已对 nestjs 跑通）。Java 侧关闭 Spring 的 multipart 解析（`spring.servlet.multipart.enabled=false`，否则 `DispatcherServlet.checkMultipart` 会在用途守卫之前把整个请求体落盘，错误也变成 `MaxUploadSizeExceededException` 等），在 handler 内用 commons-fileupload2-core 2.0.0-M5 的 `MultipartInput` 流式查找分隔符、读取分段体，分段头与 `Content-Disposition` 按 busboy 规则逐字节解析并映射错误（`getItemIterator` 会静默跳过畸形头与缺 `name` 的分段，无法得到 busboy 的错误）；请求体前补一个 CRLF，与 busboy 相同只在开头或 CRLF 之后识别首个分隔符；`JsonBodyFilter` 对 multipart 请求不读体
- 错误形状：413 为 `{code: 413, message: "PAYLOAD_TOO_LARGE", data: {file: <fileTooLarge 译文>}}`（nestjs `STATUS_TO_I18N_KEY` 没有 413，`message` 取 `HttpStatus[413]` 枚举名，不翻译）；422 的 `message` 为 `common.unprocessableEntity` 译文；`data` 中的键按错误翻译规则翻译（`invalidFilePurpose` → `Unsupported upload purpose`、`cantUploadFileType` → `Unsupported file type`、`selectFile` → `Please select a file`、`fileTooLarge` → `File is too large` / `文件过大`）
- 响应 `{file: {id, path, visibility, mimeType, size}}`（`ownerUserId`、`sha256` 不输出）；`path` 输出规则：local 驱动 PUBLIC 为 `BACKEND_DOMAIN + /{API_PREFIX}/v1/files/<key>`（库中 `path` 列存 `/{API_PREFIX}/v1/files/<key>`），PRIVATE 为 `BACKEND_DOMAIN + /{API_PREFIX}/v1/files/private/<id>`；s3 / s3-presigned 驱动库中 `path` 存对象键，输出为 3600 秒预签名 GET URL（PUBLIC 与 PRIVATE 相同）；`AWS_S3_ENDPOINT` 设置时按 `AWS_S3_FORCE_PATH_STYLE`（默认 true）决定路径式 / 虚拟主机式签名。用户 `photo.path` 走同一函数
- `GET files/private/:id`：JWT；不存在 404 `{error: 'fileNotFound'}`（`File not found`，`data: null`）；PRIVATE 且非本人 403 `{error: 'fileNotAccessible'}`（`You cannot access this file`）；PUBLIC 文件任何登录用户可读；local 驱动按 Express `res.sendFile`（send 1.x）输出（弱 ETag `W/"<size hex>-<mtime ms hex>"`、`Last-Modified`、`Cache-Control: public, max-age=0`、`Accept-Ranges: bytes`；`If-Match` / `If-Unmodified-Since` 不满足 412 `Precondition Failed`、条件请求命中 304、range-parser 合并后单个范围 206、不可满足 416 `Range Not Satisfiable`（带 `Content-Range: bytes */<size>`）、磁盘文件不存在 404 `Not Found`，三者 `message` 为原文、`data: null`），s3 驱动 302 重定向到预签名 URL（`Found. Redirecting to <url>`，按 `Accept` 选 text / html）
- `GET files/:path`（仅 local 驱动，公开）：只按库中 PUBLIC 记录查找，PRIVATE、不存在、`GET files/upload` 都返回 404 `fileNotFound`；输出头同上
- s3-presigned：body 按 `FileUploadDto`（`fileName` 字符串、`fileSize` 数字）校验；扩展名不在用途表内 → 422 `cantUploadFileType`，`fileSize` > 用途上限 → 413；签发 3600 秒 `PutObject` 预签名 URL（带 `ContentLength`），并立即写入 file 记录（`size` = `fileSize`）
- parity 只覆盖 local 驱动（nestjs 参照服务 `FILE_DRIVER=local`）；s3 / s3-presigned 由集成测试对 MinIO 容器覆盖

**health / metrics**

| 方法 | 路径 | operationId | 鉴权 |
|---|---|---|---|
| GET | /api/v1/health | healthCheck | 监控令牌 |
| GET | /api/v1/health/live | livenessProbe | – |
| GET | /api/v1/health/ready | readinessProbe | – |
| GET | /api/health | simpleHealthCheck | – |
| GET | / | getAppInfo | – |
| GET | /metrics（`METRICS_PATH`） | 不进文档 | 监控令牌 |

- 监控令牌（nestjs `MonitoringTokenGuard`）：配置了 `METRICS_TOKEN` 时要求 `Authorization: Bearer <METRICS_TOKEN>`（scheme 不区分大小写，`MessageDigest.isEqual` 常量时间比较）；未配置时非生产放行、生产拒绝；拒绝为 403 `{code: 403, message: "Forbidden resource", data: null}`（原文，不翻译）
- `/api/v1/health*` 为 @nestjs/terminus 12.1.0 形状，成功 200 且带 `Cache-Control: no-cache, no-store, must-revalidate`，`data` 为 `{status: "ok", info: {...}, error: {}, details: {...}}`，每项为 `{<key>: {...附加字段, status: "up"|"down"}}`，`details` = `info` + `error`；任一项 down 时 503，**body 只有 `{code: 503, message: "SERVICE_UNAVAILABLE", data: null}`**（terminus 结果被异常过滤器丢弃，`message` 为 `HttpStatus[503]` 枚举名，不翻译），不带 `Cache-Control`
- 检查项与顺序：`healthCheck` 为 `memory_heap`（GC 后堆占用 > `HEALTH_MEMORY_HEAP_THRESHOLD_MB`（默认 300，与 nestjs 相同；部署取值见 §10.3）为 down，响应形状与键名不变，`{message: "Used heap exceeded the set threshold"}`）、`disk`（`/` 使用率 > 90%，`Used disk storage exceeded the set threshold`）、`database`（`SELECT 1`，1000ms 超时，`{responseTime: <ms>}`，超时 `timeout of 1000ms exceeded`）、`database_pool`（`pg_stat_database.numbackends` / `max_connections`，`{activeConnections, maxConnections, utilizationPercent}`，> 90% 为 down）、`redis`（仅 `REDIS_ENABLED=true` 时加入，PING）、`wechat_api`（`WECHAT_APP_ID` / `WECHAT_APP_SECRET` 缺一则 up `{message: "WeChat not configured, skipped"}`，否则 GET `https://api.weixin.qq.com/cgi-bin/getcallbackip`，5s，2xx 为 up `{responseTime: "OK"}`，否则 down `{statusCode}`）、`alipay_api`（`ALIPAY_APP_ID` 未设置则 up `{message: "Alipay not configured, skipped"}`，否则 HEAD `https://openapi.alipay.com/gateway.do`，2xx 或 405 为 up）；`livenessProbe` 只有 `memory_heap`（成功时为 `{status: "up"}`，无 `responseTime`）；`readinessProbe` 只有 `database`。堆指标 nestjs 为 V8 heapUsed、fastapi-temp 为进程 RSS，Java 取最近一次 GC 结束时的整堆占用（`com.sun.management.GarbageCollectorMXBean.getLastGcInfo()` 中结束时间最晚、堆占用非 0 的一次，对全部堆内存池的 `memoryUsageAfterGc` 求和；young GC 后同样包含老年代；尚无 GC 记录时取当前已用量），理由见 §10.3，与阈值比较；阈值默认相同，各语言无法逐值一致，数值不属于契约。不使用 Actuator `HealthIndicator`（`Health` 结构与此不同），检查项由 `HealthIndicators` 实现并发执行，controller 按上述形状组装；down 时与 terminus 相同抛出不含 `message` 的 503 异常，由全局异常处理输出 `SERVICE_UNAVAILABLE`
- `GET /api/health`（无 `Cache-Control`）：`{status: "ok", timestamp: <toISOString>, uptime: <进程启动以来整秒>, memory: {heapUsed, heapTotal, rss}（MB，四舍五入）, node: <运行时版本>}`；Java 取当前值：`heapUsed` = 堆已用、`heapTotal` = 堆已提交（`MemoryMXBean.getHeapMemoryUsage()` 的 `used` / `committed`）、`rss` = 进程常驻内存（`com.sun.management.OperatingSystemMXBean` 不提供 RSS，Linux 读 `/proc/self/status` 的 `VmRSS`，其它平台取堆与非堆已提交之和），`node` = `Runtime.version()`；键名与类型属于契约，值不属于
- `GET /`：`{name: APP_NAME}`（默认 `app`）
- `/metrics`：不带 `API_PREFIX`、不包信封，`Content-Type: text/plain; version=0.0.4; charset=utf-8`；`METRICS_ENABLED=false` 只关闭 HTTP 指标采集与 JVM 默认指标，路由仍存在（nestjs 行为）。nestjs `setGlobalPrefix` 的排除项写死为 `metrics`，非默认 `METRICS_PATH` 在 nestjs 上会落到 `/api/<path>`；本服务一律不带前缀，部署时保持默认值

**通用模块**：路由、operationId、权限照抄 nestjs 对应 controller（纳入 `scope.yaml` 即生成接口）。空值时不输出键的响应（`Notification`、`Feedback`、`CheckFavoriteResponseDto`、`toggleFavorite` 响应）由 `template-views` 中的视图类经 `schemaMappings` 替换生成的 record。反馈列表 `total` 按 TypeORM `findAndCount`：本页未满时不执行 COUNT，`total = skip + 本页行数`（`page` / `limit` 可为小数，按 PostgreSQL 四舍五入作 OFFSET / LIMIT）；地址 / 收藏 / 通知列表单独 COUNT。config 写入按 TypeORM 规则比较取值，未变化时不写库、`updatedAt` 不变。微信 / 支付宝登录与 `sendSmsCode` 不包请求级事务，建号 / 绑定 / 写验证码各自短事务；短信、微信、支付宝外呼用 JDK `HttpClient`，不接入链路追踪，传输错误消息去掉 URL query。

`make module NAME=<包名> TAG=<OpenAPI 标签>`：先把该标签缺少的 operationId 追加到 `scope.yaml` 并重新生成契约，再按生成的 `<Tag>Api` 写出 controller 桩（方法返回 501，鉴权按基线 `security` 生成 `@AuthenticatedOnly` / `@PublicEndpoint`）、Service、Repository 端口、Mapper、RepositoryImpl；标签已全部在 scope 中或已有实现、NAME 为 Java 关键字、文件已存在时拒绝。权限需手工补到 `scope.yaml` 与 controller。

Spring Boot Actuator 只作内部实现（`PrometheusMeterRegistry` 提供 JVM 默认指标），其 `/actuator/**` 端点不对外暴露；上表路径由 controller 实现（指标端点按 `METRICS_PATH` 在启动时注册到 `RequestMappingHandlerMapping`）。

---

## 5. 相对 nestjs-template 的改进（均不破坏现有契约）

| # | 项 | nestjs 现状 | 本模板 |
|---|---|---|---|
| 1 | 密码哈希 | bcrypt(md5)，cost 10 | **argon2id(md5)**（OWASP 首选，Spring Security `Argon2PasswordEncoder` + BouncyCastle）；参数取 OWASP 推荐值 m=19456 KiB、t=2、p=1（与 fastapi-temp 1eb15d5 相同）；按哈希前缀识别 `$2a/$2b`（bcrypt）与 `$argon2id`，已有哈希照常验证，登录成功后若格式不是当前 `PASSWORD_HASHER` 或 argon2id 参数（`m` / `t` / `p`）与当前配置不同就重哈希（`Argon2PasswordEncoder.upgradeEncoding` 只在参数更低时返回 true，参数比较自写）。哈希与验证都经过进程内 `Semaphore(PASSWORD_HASH_CONCURRENCY)`：一次 argon2id 占 19MiB，虚拟线程下并发几乎不受限，不限流时 1GB 容器会 OOM。客户端仍传 MD5，前端无感。登录时重哈希会改写 `password` 列，而找回密码令牌内嵌旧哈希的指纹（§6.1）：用户发起找回密码后又用原密码登录并触发重哈希，邮件中的重置链接随即失效（422 `invalidHash`），需重新发起；nestjs 登录不改写密码列，没有这个现象 |
| 2 | 会话 | 已同步到 nestjs | 会话表可空列 `userAgent`（≤512）、`ip`、`lastActiveAt`（每会话最多 5 分钟写一次）；「我的设备」列表、下线指定设备、下线其他设备三个接口，见 §4.6 |
| 3 | 数据库迁移 | 每个应用容器启动时跑 `migration:run` | 独立一次性 `migrate` 容器（同一镜像，`APP_ROLE=migrate` 只跑 Flyway 后退出），应用 `depends_on: service_completed_successfully`；api / worker 设 `spring.flyway.enabled=false` |
| 4 | 限流算法 | 固定窗口（`INCR` + `PEXPIRE`，超限后按 TTL 封禁） | Redis Lua **GCRA**（Spring Data Redis `RedisScript`，平滑、无窗口边界突刺；脚本内用 Redis `TIME` 取时间，键值为理论到达时间 TAT（毫秒十进制字符串，与 fastapi-temp 相同），`SET … PX`；内部判定用整数微秒，见 §4.1「限流细则」GCRA 计算），进程内回落实现同算法且限制键数量；响应头名与出现条件与 nestjs 的 @nestjs/throttler 一致（§4.1「限流细则」）。键为 nestjs 计数键加 `:gcra` 后缀（`throttle:<sha>:default:gcra`），不与 nestjs 的 `INCR` 计数冲突；共用 Redis 时 Python、Go、Java 三个 GCRA 后端共用 `:gcra` 键、共同计数，nestjs 用固定窗口键、单独计数，见 §14 |
| 5 | Request ID | 已同步到 nestjs（校验 + 回写） | 同 nestjs；日志另带 `traceId` / `spanId`（Micrometer Tracing 写入 MDC 的键名），日志与链路可互相跳转 |
| 6 | OpenAPI | 运行时生成，无版本管理 | spec-first：`/docs-json` 输出入库的基线原文；DTO 与接口由基线生成，契约测试保证本期 operation 全部有实现 |
| 7 | 新表主键 | uuid v4 | 同 nestjs：uuid 主键由数据库默认值 `uuid_generate_v4()` 生成，表结构与 `db/nestjs-schema.sql` 一致（go-template、fastapi-temp 相同）；不引入 UUIDv7 |
| 8 | 事务 | 服务内手动管理 | service 写方法统一用组合注解 `@WriteTransaction`（= `@Transactional(noRollbackFor = HttpStatusException.class)`，`HttpStatusException` 为 `BusinessException` 与 401 / 403 / 404 / 422 等 HTTP 异常的共同父类），ArchUnit 禁止直接使用 `@Transactional`。结束规则（与 fastapi-temp `src/app/infrastructure/database/session.py` 的 `Database.transaction` 相同，语言无关）：**成功 → 提交；4xx 业务 / HTTP 异常 → 提交已写入的内容后再抛出；其它异常 → 回滚**。Spring 默认「RuntimeException 即回滚」会把 §6.3 刷新重放时的会话吊销随 401 一起撤销，因此不能用默认规则；吊销写在请求事务内，由该规则在 401 时提交（不用 `REQUIRES_NEW`，见第 13 项）。例外：数据库语句失败（如唯一约束冲突）后事务已处于失败状态，**不得按 4xx 提交**：`DataIntegrityViolationException` 等数据访问异常在事务边界之外（`GlobalExceptionHandler`）才翻译成 422 / 409，service 内不得捕获后改抛 `HttpStatusException`；内层参与事务的方法同样使用 `@WriteTransaction`，避免被标记为 rollback-only 后提交时抛 `UnexpectedRollbackException`。发邮件、投递任务等副作用通过 `TransactionSynchronization.afterCommit` 在提交成功后执行；4xx 提交路径与 fastapi-temp 一致丢弃已登记的提交后副作用（副作用先登记到事务绑定的列表，`@WriteTransaction` 切面在 4xx 时清空列表再提交）。测试要求见 §6.3 |
| 9 | 优雅停机 | 依赖 Nest 默认 | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`；JobRunr 停机等待在途任务；容器 `stop_grace_period` 与之匹配 |
| 10 | 配置校验 | 运行时按模块校验 | 全部 `@ConfigurationProperties` record + `@Validated`（跨字段规则在 record 构造器中校验），绑定或校验失败时启动即退出；不做跨 record 的汇总报错 |
| 11 | 语言头 | 已同步到 nestjs（`AcceptLanguageResolver`） | 标准头 `Accept-Language`，见 §4.2 |
| 12 | 测试隔离 | e2e 连真实服务、串行执行 | 集成测试用 Testcontainers 起 Postgres / Redis；容器启动后在模板库上执行 Flyway 迁移 + seed，每个 Spring 测试上下文 `CREATE DATABASE … TEMPLATE` 克隆一个独立库（不用 TRUNCATE、不用测试事务回滚），测试按真实提交运行；可并行，见 §15.3 |
| 13 | 并发模型 | 单线程事件循环 | **虚拟线程**（`spring.threads.virtual.enabled=true`）；JDK 24 起 `synchronized` 不再钉住载体线程（JEP 491），这是选 25 而非 21 的主要原因；连接池大小仍是数据库并发上限，也是虚拟线程模式下真正的瓶颈：同一请求在持有一个连接时不得再申请第二个连接（不用 `REQUIRES_NEW`、不另开独立连接），否则并发数达到池大小时所有请求互相等待，满 30s 后失败 |

第 1 项与「两个后端共用同一个库」有冲突（nestjs 无法验证 argon2id）：共用期间设 `PASSWORD_HASHER=bcrypt`，独立部署时用默认 `argon2id`。第 2、5、11 项与限流响应头已在 nestjs 实现，属于共同契约。

---

## 6. 认证与权限

### 6.0 选型：自写 JWT + MVC 拦截器（不用 Sa-Token，未采用 Spring Security 过滤器链）

| 需求 | Spring Security 7 | Sa-Token 1.46.0 |
|---|---|---|
| access JWT `{id, role:{id}, sessionId}` HS256 | `NimbusJwtEncoder` / `NimbusJwtDecoder.withSecretKey`，载荷完全自定义 | 默认不透明 token；sa-token-jwt 的载荷结构固定为其 loginId 模型 |
| refresh JWT 独立密钥 + hash CAS 轮换 | 独立过滤器链 + 第二个 `JwtDecoder` + 自写 service | 无对应能力，仍需自写 |
| 会话存数据库 `session` 表 + 30s 缓存 | 自定义 `Converter<Jwt, AbstractAuthenticationToken>` 查缓存 / 库 | 会话存 Redis，与表结构冲突 |
| 401 / 403 统一信封 | `AuthenticationEntryPoint` / `AccessDeniedHandler` | 异常处理器 |
| 权限注解 | `@RequirePermissions` / `@RequireAnyPermission` + 自定义 `AuthorizationManager`（方法安全） | `@SaCheckPermission` |
| 版本与安全公告 | Boot BOM 管理 | 单独跟踪 |

结论：Sa-Token 能提供的只剩注解，其余都要重写，不选。M2 实现时 Spring Security 的过滤器链也未采用：Nimbus 的 HMAC 签名 / 校验要求密钥至少 256 位，nestjs 开发与 parity 环境的密钥是 `secret` 这类短字符串，用它无法与 nestjs 互认令牌。实际做法：自写与 jsonwebtoken 兼容的 HS256 签发与 HS256/384/512 校验（`common-security` 的 `Jwt`、`Ms`），鉴权与权限守卫在 Spring MVC `HandlerInterceptor` 中按 handler 注解执行（已解析出 handler、尚未做参数校验，顺序与 nestjs 守卫 → 管道一致）；只引入 `spring-security-crypto` 做 argon2id / bcrypt。

### 6.1 JWT

- access：`{id, role, sessionId, iat, exp}`，HS256，`AUTH_JWT_SECRET`，默认 15m。`role` 的形状随签发来源不同（照抄 nestjs `token.service.ts` 的调用方）：登录 / 注册类接口签发时为完整角色实体 `{id, name, __entity: "RoleEntity"}`（键顺序如此），`auth/refresh` 签发时为 `{id}`
- refresh：`{sessionId, hash, iat, exp}`，`AUTH_REFRESH_SECRET`，默认 3650d
- `iat` 为秒；`exp = iat + floor(ms(expiresIn) / 1000)`（与 jsonwebtoken 相同）；`tokenExpires = 当前毫秒时间 + ms(AUTH_JWT_TOKEN_EXPIRES_IN)`。`ms` 按 npm `ms` 2.x 规则解析：纯数字（含小数、负数）视为毫秒；单位大小写不敏感，支持 `ms/msec(s)/millisecond(s)`、`s/sec(s)/second(s)`、`m/min(s)/minute(s)`、`h/hr(s)/hour(s)`、`d/day(s)`、`w/week(s)`、`y/yr(s)/year(s)`（年按 365.25 天）；数字与单位间可有空格；超过 100 字符或无法解析视为配置错误（启动失败），不使用 `java.time.Duration.parse` 或 Spring `DurationStyle`
- forgot / confirm-email 令牌独立密钥（30m / 1d）；找回密码令牌内嵌密码指纹 `sha256(hash)[:16]`，改密后失效
- 生产环境四个密钥必须 ≥32 字符且互不相同（启动校验）

### 6.2 access 校验

只认 Bearer 头 → 必须有 `id`、`sessionId` → 查缓存 `jwt:session:{sessionId}`（30s，存 `{statusId, roleId}`），未命中查库：会话存在且属于该用户、用户状态 active / inactive → 以数据库角色覆盖令牌角色；同时节流更新 `lastActiveAt`（每会话每 5 分钟最多一次）。查库与更新在鉴权过滤器内用同一个连接完成并在进入 handler 前归还，handler 的事务之后再取连接，单个请求任何时刻只占一个连接；更新失败只记日志，不影响鉴权结果（与 fastapi-temp 1eb15d5 在请求事务内用 SAVEPOINT 更新的效果相同：都不在持有连接时申请第二个）。失败 401，`message` 为 nestjs `jwt.strategy.ts` 原文（无翻译键，原文输出）：

| 情形 | message |
|---|---|
| 无令牌、签名 / 过期无效、载荷缺 `id` / `sessionId` | `common.unauthorized` 译文 |
| 会话不存在、已删除或不属于该用户 | `Session expired` |
| 用户不存在 | `User not found` |
| 用户状态不是 active / inactive（缓存命中或查库） | `User is not active` |

会话被删除（logout、吊销、改密删除其它会话、重放吊销）时，`jwt:session:{id}` 缓存**在写库后立即清除一次，并在事务提交后（`afterCommit`）再清除一次**：只在提交前清除时，并发请求可能在提交前从库中读到旧会话并回填缓存，令已删除会话在 30s 内仍可用。

**Java 侧实现**：`RouteAuthInterceptor`（`HandlerInterceptor`）按 handler 上的鉴权注解处理：公开路由忽略 `Authorization` 头；`@AuthenticatedOnly` 校验 access 令牌并调用会话校验（上述规则）；`@OptionalAuth` 令牌缺失或任何校验失败都按匿名处理（nestjs OptionalJwtAuthGuard）；`@RefreshTokenAuth` 用 refresh 密钥校验并要求载荷有 `sessionId`。Bearer 头按 passport-jwt 的 `/(\S+)\s+(\S+)/` 提取，scheme 不区分大小写。主体放在请求属性中，controller 经 `AuthContext` 读取。

路由声明分两个互相独立的维度，都写在实现方法（或类）上：

| 维度 | 注解 | 规则 |
|---|---|---|
| 鉴权（authentication） | `@PublicEndpoint`、`@OptionalAuth`、`@AuthenticatedOnly`（JWT）、`@MonitoringToken`（`healthCheck`、`/metrics`）；refresh 由独立链处理 | 每个 handler 必须且只能有一个，缺少即启动失败（默认拒绝） |
| 授权（RBAC） | `@PermissionsGuarded` + `@RequirePermissions` / `@RequireAnyPermission` | 只对应 nestjs 使用 `PermissionsGuard` 的路由；`auth/me` 这类仅 JWT 的路由不需要也不应有权限声明；带 `@PermissionsGuarded` 却没有权限列表的路由运行时返回 403（与 nestjs `No permission declared for this route` 一致），启动时打印告警 |

拦截器与授权规则都从这些注解读取，启动时遍历全部 handler 校验声明。哪些 operation 走权限守卫、需要哪些权限，记录在 `scope.yaml` 的 `permissions` 字段（照抄 nestjs controller），契约测试比对注册表与它；spec 中的 `security` 只交叉核对鉴权维度（§15.4）。

### 6.3 刷新轮换

- `POST /auth/refresh`，refresh JWT 放 Bearer 头，经 refresh 链校验签名与过期
- 会话不存在或已无关联用户 → 401
- `session.hash != payload.hash`：若 `payload.hash` 等于 `session:rotated:{id}` 中记录的「刚被换掉的旧 hash」（轮换时写入，TTL 60s；nestjs `session.service.ts` `wasRecentlyRotatedFrom`）→ 普通 401（并发刷新的落后请求）；否则判为重放，吊销会话并 401。该键只保存**最近一次**被换掉的一个 hash，再次轮换即覆盖：h0 → h1 → h2 两次轮换后再用 h0 刷新属于重放
- 会话匹配后重新读取用户：用户或角色不存在 → 401；用户状态不是 active / inactive → **422** `{refresh: 'userNotActive'}`（nestjs `validateUserStatusForAuth(user, 'refresh')`，输出译文 `User is not active` / `用户未激活`）
- 否则生成新 hash，以 `UPDATE session SET hash = :new WHERE id = :id AND hash = :old` 做 CAS（Mapper `@Update` 返回行数），0 行则 401；成功后把旧 hash 写入 `session:rotated:{id}`，返回 `RefreshResponseDto`（新 access 的 `role` 为 `{id}`）
- 重放吊销与 401 同在一个请求内：按 §5 第 8 项，401 属于 4xx，吊销写入在请求事务内，由 `@WriteTransaction` 在 401 时提交。集成测试（克隆库模式）：登录 → 刷新得到 h1 → 再刷新得到 h2 → 用 h0 刷新返回 401 → 此后用 h2 刷新返回 401、用登录时的 access 令牌请求 `auth/me` 返回 401 `Session expired`
- logout 软删会话并清缓存；改密 / 重置 / 换邮箱删除其它会话；03:00 物理删除软删超过 7 天的会话

### 6.4 密码

客户端传 MD5 hex（32 位，转小写）；服务端哈希见 §5 第 1 项；比较时同时尝试大写 MD5。

### 6.5 登录方式

| 方式 | 行为 |
|---|---|
| 邮箱 | 注册后 inactive 并发确认邮件；active / inactive 均可登录；错误 422 `{email:'notFound'}` / `{password:'incorrectPassword'}`（输出时按 §4.1 翻译） |
| 手机 + 密码 | 错误 422 `{credentials:'incorrectPhoneOrPassword'}` |
| 短信登录 | 不自动注册，未知手机号 422 `{phone:'notFound'}` |
| 手机注册 | 直接 active 并返回令牌 |
| 微信 | `mini_app` → jscode2session，`app` → OAuth；按 openId 自动建号，唯一冲突时重查 |
| 微信手机号 | 绑定 openId + 手机，不合并账号（`phoneBoundToOtherWechat`），非大陆号拒绝（`phoneRegionNotSupported`） |
| 支付宝 | 按 alipayOpenId 自动建号 |

### 6.6 短信验证码

- `send-code` 预检：register 要求手机号不存在；login / reset_password / verify_identity 要求存在；change_phone 返回 `unsupportedCodeType`
- `SMS_CODE_LENGTH`（6）位数字，缓存 `sms:code:{type}:{phone}` = `{code, attempts, createdAt}`，TTL 300s
- 重发间隔 60s（检查 + 锁 `sms:send:{type}:{phone}`），失败 422 `{phone:'rateLimited'|'sendFailed', retryAfter}`（`phone` 输出译文，`retryAfter` 为数字原样输出）
- 最多 5 次尝试；成功或超限删除；失败 422 `{code:'invalidCode'}`
- 阿里云 Dysms；`SMS_MOCK_MODE=true` 只打日志

### 6.7 RBAC

- 角色 admin=1、user=2、editor=3、reviewer=4；状态 active=1、inactive=2
- 权限枚举（`user:read` 等）、默认映射照抄 nestjs（枚举值不变）
- 表 `permission`、`role_permission`；数据库为空回落代码默认值；缓存 5 分钟
- `@RequirePermissions(...)`（AND）、`@RequireAnyPermission(...)`（OR），与 `@PermissionsGuarded` 一起标注在 controller 实现方法或类上（生成的接口不含权限信息）；方法级覆盖类级；守卫下未声明权限 → 403（message 原文 `No permission declared for this route`）；未加守卫的 JWT 路由不检查权限（§6.2）
- 缺权限时 403，message 为 nestjs 原文 `Missing permissions: a, b` / `Missing any of permissions: a, b`（无对应翻译键，按 §4.1 规则原文输出）
- 权限守卫中的其它 403 原文：请求无用户 `User not authenticated`，用户无角色（数据库角色为空，§6.2 覆盖后 `role.id` 缺失）`User role not found`
- 会话校验缓存（§6.2）与权限缓存只放 Redis，不使用进程内一级缓存，保证下线、改角色、改权限在所有实例上同时生效；仅 `REDIS_ENABLED=false`（单实例开发）时用进程内实现

---

## 7. 数据模型

表名、列名与 nestjs 一致（camelCase 列名），两个后端可指向同一个库。Mapper 用注解 SQL，表名与 camelCase 列名一律加双引号（`"user"`、`"firstName"`；`user` 是 PostgreSQL 保留字），结果映射到 record。

本期表：user、role、status、session、permission、role_permission、file、audit_logs、config、region、notification、notification_setting、address、favorite、feedback、user_consent、account_cleanup、admin_alert。约束名照抄 nestjs（含 `UQ_e12875dfb3b1d92d7d7c5377e22`、`REL_75e2be4ce11d447ef43be0e374`、`user_phone_key` 等，§4.1 的唯一约束映射依赖这些名字）；软删 `deletedAt`（SQL 中显式写 `"deletedAt" IS NULL` 条件与 `SET "deletedAt" = now()`）；已删除用户释放唯一字段的部分索引同样实现。

迁移：Flyway `V1__init.sql` 一次建出上述结构（以 `db/nestjs-schema.sql` 为起点整理）。Flyway 社区版不支持 undo，CI 用以下步骤替代 fastapi 的 downgrade 校验：空库 `migrate` → `validate` → 结构快照比对 → `clean`（仅 CI 开启）→ 再次 `migrate` 后快照与第一次相同。

结构快照比对以共享产物 `db/nestjs-schema.sql`（§4.3，fastapi-temp `scripts/schema_snapshot.py` 生成的规范化快照，每个对象一行）为准，不用 `pg_dump`：集成测试对 Flyway 迁移后的库执行与 `schema_snapshot.py` 相同的四条 `pg_catalog` 查询并按相同格式输出（openapi-tools 中移植查询与格式化，或调用复制来的脚本），断言「快照 − 实际」恰为 `constraint feedback.FK_feedback_errata …` 与 `constraint feedback.FK_feedback_question …` 两行（引用本期不做的 `errata`、`question` 表），「实际 − 快照」为空；只比较 `public` schema 中上述 18 张表，`flyway_schema_history` 与 JobRunr schema 不在范围内。Mapper SQL 与库结构的一致性由集成测试覆盖（各 Mapper 的语句都在真实 Postgres 上执行）。

**共库时的迁移归属**：两套迁移工具（TypeORM migrations、Flyway）不能同时管理同一个库，否则会重复建表、互相覆盖。约定：

| 部署方式 | 表结构归属 | Java 侧做法 |
|---|---|---|
| 独立部署（默认） | Flyway | 正常 `migrate` |
| 与 nestjs 共库 | nestjs（TypeORM） | 设 `DB_SCHEMA_OWNER=external`（默认 `flyway`，只接受这两个值；语义同 go-template）：migrate 不执行 Flyway、只建 JobRunr 表；migrate 与 api 在 Web 启动前以 `SchemaCheck` 比对打进 jar 的 `db/nestjs-schema.sql` 所列表 / 列，缺失时报 `database schema is missing: 表.列, …` 并启动失败；会话设备列已在 nestjs 迁移 `AddSessionClientInfo` 中；JobRunr 表放独立 schema，不与 nestjs 表混放 |

**Seed**：与 nestjs `user-seed.service.ts` 一致。非生产创建 `admin@example.com`、`john.doe@example.com`（密码 `Secret00`）等演示账号；生产不创建演示账号，仅在 `SEED_ADMIN_EMAIL`（去空白、转小写）与 `SEED_ADMIN_PASSWORD` 都设置且尚无管理员时创建管理员。nestjs 以 `ENV_MODE=prod` 判定生产，本服务与 fastapi-temp 一样以 `APP_ENV=production` 判定（nestjs 参照服务在 `scripts/nestjs/nestjs.compose.yaml` 中以 `ENV_MODE=dev` 执行 seed）。seed 幂等，可重复执行。

---

## 8. 基础设施

| 能力 | 实现 |
|---|---|
| 日志 | Boot 结构化日志（生产 logstash JSON、开发彩色单行；`LOG_PRETTY_PRINT` 未设置时非生产为单行文本，显式设置时只有 `true` 为单行文本）；级别 `LOG_LEVEL`（未设置时生产 info、其它 debug；作用于应用日志 `tech.xingyunqi`，框架日志不低于 info，`silent` 为 `OFF`，非法值启动失败），由 `ObservabilityEnvironment`（`EnvironmentPostProcessor`）转成 `logging.*` 属性；MDC 含 `request_id`、`user_id`（路由鉴权得到用户时写入）与 Micrometer Tracing 的 `traceId` / `spanId`；请求日志过滤器对应 pino-http：每个请求一条，消息 `<METHOD> <originalUrl> <status>`（异常时追加 ` - <异常消息>`，≥500 时追加 ` - failed with status code <status>`，同 pino-http），字段 `context: "HTTP"`、`req{id, method, url, query, headers, remoteAddress}`、`res{statusCode}`、`responseTime`（ms）以 SLF4J key-value 输出；≥500 或异常为 error、≥400 warn、其余 info；脱敏（值替换为 `[REDACTED]`）`req.headers.authorization`、`req.headers.cookie`、`req.query` 的 hash/token，在过滤器内完成；pino-http 默认不输出请求体，本服务同样不输出，`req.body` 的脱敏项无需处理 |
| 指标 | `METRICS_PATH`（默认 `/metrics`，端点输出 `PrometheusMeterRegistry.scrape()` 的 JVM 默认指标，其后为十个自定义指标）。名称、类型、标签、桶与 nestjs `metrics.module.ts` 相同：`http_requests_total`（counter，`method`、`path`、`status`）、`http_request_duration_seconds`（histogram，同标签，桶 0.005/0.01/0.025/0.05/0.1/0.25/0.5/1/2.5/5/10）、`http_active_connections`（gauge）、`database_query_duration_seconds`（histogram，`operation`、`table`，桶 0.001/0.005/0.01/0.025/0.05/0.1/0.25/0.5/1）、`cache_hits_total` / `cache_misses_total`（`cache_name`）、`queue_jobs_total`（`queue_name`、`status`）、`admin_alerts_total`（`type`、`level`）、`admin_alerts_deduplicated_total`（`type`）、`throttler_storage_fallback_total`（无标签）；HELP 文本照抄。十个自定义指标不经 Micrometer，由 `template-common-observability` 的 `AppMetrics` 自行计数并按 prom-client 的文本格式输出（未观测的带标签指标同样输出 HELP / TYPE，无 `_max` / `_created`，标签顺序与数值写法同 prom-client：counter 为 `{标签…, app}`，histogram 桶为 `{le, app, 标签…}`）；Micrometer 的 Prometheus 后端只提供 JVM / 进程 / 连接池等默认指标（含 Boot 自带的 `http.server.requests`），`management.metrics.tags.app` = `APP_NAME`。`app` 标签取 `APP_NAME`（未设置时为本服务应用名默认值 `app`；nestjs 为 `nestjs-app`，fastapi-temp 同 nestjs，差异写入 `docs/contract.md`）。HTTP 指标在响应结束时记录：`path` 为完整路由模板并转成 Express 写法（`/api/v1/users/{id}` → `/api/v1/users/:id`），路由模板取 `ExpressRoutingFilter` 命中的 handler 模板，未匹配路由为 `<unmatched>`；handler 经输出流写响应体时出现 IO 异常（客户端断开）记 `status="499"`；CORS 预检在指标过滤器之前结束，不计数；请求体解析失败（413 / 400）在路由之前，记为 `<unmatched>`。nestjs 只实际记录 HTTP、告警、限流回落三组，其余只注册；本服务全部记录（MyBatis 拦截器记数据库耗时，表名从 SQL 解析） |
| 链路 | `spring-boot-starter-opentelemetry` 自动埋点 MVC、JDBC、Redis、`RestClient`，OTLP/HTTP 导出，`service.name` = `APP_NAME`，`OTEL_ENABLED` 默认 false；Sentry 只在 `SENTRY_DSN` 设置时启用（`environment` = `APP_ENV`，不发送 PII）。nestjs 无这两项，不属于契约 |
| 健康检查 | 形状与检查项见 §4.6「health / metrics」（controller 组装 terminus 形状）；微信 / 支付宝探测用 5s 超时的 JDK `HttpClient`（`ExternalApiProbe`，测试中替换为 mock） |
| Redis 键 | 与 nestjs 完全相同（`jwt:session:{id}`、`session:rotated:{id}`、`sms:code:{type}:{phone}`、`lock:` 前缀等），不加全局前缀，因此不引入 `REDIS_KEY_PREFIX`（nestjs 也没有该变量）；共用 Redis 时两个后端可互认缓存。例外：限流 GCRA 键为 `throttle:<sha>:default:gcra`（§4.1「限流细则」）；队列在 Postgres（JobRunr），与 nestjs 的 BullMQ 不互通，共用部署时两边各自消费自己的任务 |
| 缓存 | Redis 命名空间 `cache`，默认 TTL 60s；不使用进程内一级缓存（多实例下失效无法传播）；`REDIS_ENABLED=false` 时整体换成进程内 `MemoryCache`（最多 1 万项，仅开发 / 测试） |
| 限流存储 | Redis；Redis 异常时回落进程内计数并累加 `throttler_storage_fallback_total`，首次降级告警（与 nestjs 一致，§4.1「限流细则」） |
| 分布式锁 | 与 nestjs `lock.service.ts` 同格式：键 `lock:<key>`、值为随机 token，`SET NX PX`（Spring Data Redis `SET … PX … NX`）+ Lua 比较 token 后删除（`RedisScript`），单条命令超时 1s（锁使用单独的 Lettuce 连接，`commandTimeout` 1s、断线时命令立即失败；不引入 Redisson）；Redis 未启用或命令失败时回落进程内锁（仅本实例生效）。不用 `RLock`：其哈希结构与看门狗续期和 nestjs 的字符串键不兼容，共用 Redis 时两边互不感知 |
| 队列 | JobRunr，PostgreSQL 存储，表在独立 schema `jobrunr`（表前缀 `jobrunr.`，`SchemaSnapshotIT` 只比 public）；`APP_ROLE=migrate` 在 Flyway 之后建 schema 并执行 JobRunr 迁移，api / worker 不建表也不在启动时访问任务表（`DatabaseOptions.NO_VALIDATE`）。队列与任务（nestjs BullMQ 队列名 / job 名）：`mail`（`send-mail`）、`notification`（`send-email`、`send-sms`、`send-wechat-subscribe`；BullMQ 并发 5、limiter 10 个 / 秒；随 M4 通知模块实现）、`account-cleanup`（`clean-account`，并发 1）；任务为 `JobRequest` + `JobRequestHandler`，`@Job(name = <job 名>, labels = <队列名>)`。默认共 3 次尝试，失败后等待 `1s × 2^(n-1)`（BullMQ `exponential`）。JobRunr 9.0.3 核对结果：`RetryFilter` 的 `retries` 不含首次、退避为 `seed^n` 秒；本服务自建 `BackgroundJobServer` bean，只登记 `QueueJobFilter`（`ElectStateFilter` + `JobServerFilter`，按队列标签决定尝试次数与退避，JobRunr 只保留最后一个 `ElectStateFilter`，默认 `RetryFilter` 被替换），不属于队列的任务（定时任务）失败后不重试。重试时刻写入 `ScheduledState.scheduledAt` 与上式一致；JobRunr 按轮询周期（`poll-interval-in-seconds: 5`，SQL 存储的下限）提前至多一个周期把到期任务转入队列，再由下一次轮询取走，实际间隔与公式相差至多一个周期。开源版没有队列级并发 / 限速：`notification` 由进程内 `JobLimiter`（并发 5、按开始时间计的 1 秒滑动窗口 10 个，与 fastapi-temp `JobLimiter` 同义）包住每次执行，`@Job(name = "%0")` 取 `NotificationJob.toString()` 即 job 名；`account-cleanup` 用进程内锁串行执行并持用户锁（见「账号清理」）；拿不到进程内锁的任务抛 `JobYieldException` 让出 worker，`QueueJobFilter` 将其安排在 5 秒后重跑，让出不计入失败次数。每次结束（成功或最后一次失败）计 `queue_jobs_total{queue_name, status="completed"|"failed"}`。background server：`APP_ROLE=worker` 运行（`worker-count` 5，停机时等待在途任务至多 20s）；api 进程由 `JOBS_IN_PROCESS` 决定（未设置时非生产运行、生产不运行，生产显式为 `true` 时启动失败），测试配置默认关闭、需要的测试单独开启（nestjs / fastapi-temp 在 `REDIS_ENABLED=false` 时改为进程内执行，本服务队列在 Postgres 上，无此分支） |
| 定时任务 | JobRunr recurring job（集群内单实例执行，nestjs 用 `lock:scheduler:<name>` 锁，本服务不需要）：每天 03:00 UTC 物理删除 `deletedAt` 早于 7 天的会话、每天 04:00 UTC 软删除 `readAt` 早于 30 天的已读通知（nestjs 这两项未指定时区，容器为 UTC）；每小时第 25 分（`Asia/Shanghai`）执行账号清理补偿（见「账号清理」）。不用 `@Recurring` 注解（其后处理器在每个进程启动时写任务表），由运行 background server 的进程在 `ApplicationReadyEvent` 时以 `JobScheduler.scheduleRecurrently(id, cron, zoneId, …)` 登记。任务以条件更新保证幂等，与 nestjs 同库同时运行也不会出错 |
| 审计 | `@Audit` 注解 + AOP 在响应成功后写 `audit_logs`（失败不影响响应） |
| 告警 | `AlertReporter.raise(type, level, message, payload, dedupKey)`（dedupKey 缺省为 type）：同 dedupKey 已有未确认告警时不写入，计 `admin_alerts_deduplicated_total{type}`；否则写 `admin_alert`、计 `admin_alerts_total{type, level}`，并给 admin 角色用户发站内通知（`[<level>] <type>`）；写入失败只记日志 |
| 账号清理 | 删会话 → 软删 → 提交后投递 `account-cleanup` 任务（参数 `{userId}`，5 次尝试（`retries = 4`），`10s × 2^(n-1)` 退避）。去重对应 nestjs jobId `user-<id>`（不按时间分桶）：以 `user-<id>` 生成名字型 UUID 作 JobRunr job id，入队前查该 job：排队 / 执行中则跳过，已结束（SUCCEEDED / FAILED / DELETED）或未结束但 24 小时未更新则先删除再入队，任务结束即可再次入队；查询与入队期间持 `lock:account-cleanup:enqueue:user-<id>`（30s，被占用时跳过），并发投递同一用户时只有一方入队，跨进程时 JobRunr 对相同 job id 的插入冲突会忽略后到者。注销、管理员重投递与补偿任务都走这一入口。任务按 order 升序执行全部清理器（nestjs：users 900 解除头像引用、files 1000 删除该用户文件与存储对象且仍被外键引用的保留；M4 模块 favorites 70、feedbacks 90、notifications 100、addresses 110），执行时进程内串行（BullMQ 并发 1；已有清理在执行时让出 worker、5 秒后重跑，不占槽位等待、不计入重试）并持 `lock:account-cleanup:user-<id>`（1 小时；锁被占用则跳过，防止重投递导致并发重复清理），单个失败不影响其余，全部成功后写 `account_cleanup(userId, completedAt)`；清理器为实现 `AccountDataCleaner`（`name()`、`order()`、`clean(userId)`）的 Spring bean，`AccountCleanupRunner` 收集全部此类 bean 按 order 排序；最后一次尝试仍失败时告警（type `accountCleanupFailed`、WARN、payload `{userId, reason, failedCleaners}`、dedupKey `accountCleanupFailed:<id>`），入队失败同样告警。补偿任务：每小时取已软删超过 30 分钟且无 `account_cleanup` 记录的用户（按 id 升序，200 个）重新入队 |
| 邮件 | 注册确认、确认新邮箱、找回密码三个模板，直接使用 nestjs `src/integrations/mail/mail-templates/*.hbs`（Handlebars.java，开启严格变量）。nestjs 在请求内同步发送（SMTP 失败时接口 500）；本服务与 fastapi-temp 一样提交后入 `mail` 队列异步发送，SMTP 失败不影响接口响应（有意偏离，parity 不覆盖 SMTP 失败场景；邮件配置仅生产必填，见 §9）：请求侧把模板名、收件人、数据与当前请求语言写入任务参数，worker 按该语言渲染。主题 = `common.<titleKey>` 译文（activation / confirm-new-email 为 `confirmEmail`，reset-password 为 `resetPassword`）；正文 `text{n}` 取 `confirm-email` / `confirm-new-email` / `reset-password` 命名空间；纯文本 = `<url> <title>`；链接 = `FRONTEND_DOMAIN` 逗号分隔的第一个 + `/confirm-email`、`/confirm-new-email`、`/password-change`，查询串按 WHATWG `URLSearchParams` 编码（`hash`，找回密码另有 `expires`；空格为 `+`，只保留 `*-._` 与字母数字，与 `URLEncoder.encode(v, UTF_8)` 的规则相同）；`app_name` = `APP_NAME`；发件人 `"<MAIL_DEFAULT_NAME>" <<MAIL_DEFAULT_EMAIL>>`；TLS 语义同 nodemailer。Handlebars.java 的 `{{x}}` 转义必须与 handlebars.js 相同（``& < > " ' ` =`` → `&amp; &lt; &gt; &quot; &#x27; &#x60; &#x3D;`），4.5.8 的 `EscapingStrategy.HTML_ENTITY`（默认值，显式设置）与之一致；严格变量用 `helperMissing` 抛错实现（值为 null 也抛错，模板上下文的值均非 null）。模板放在 `template-app` 的 `mail-templates/`，`template-common-mail` 提供渲染（`MailTemplates`）与发送（`SmtpMailTransport`，未设 `MAIL_HOST` 时为只写日志的 `LoggingMailTransport`）；以 fastapi-temp `tests/fixtures/nestjs_mail/*.json`（nestjs 渲染的 subject / text / html）作 golden 用例逐字节比对 |
| 文件 | `FILE_DRIVER=local|s3|s3-presigned`，规则见 §4.6 files；local 写到工作目录下 `./files`；s3 驱动先写临时文件再 `PutObject`（`Content-Type` 取用途表的 mime），超限或后续失败时删除对象 |
| 进程 | 同一镜像按 `APP_ROLE` 运行：`api`（Web，不跑任务）、`worker`（无 Web 端口，JobRunr background server，含 recurring）、`migrate`（一次性 Flyway）、`seed`（一次性写入 seed 数据，幂等）；nestjs 的独立 scheduler 合并进 worker，由 JobRunr 保证单实例执行 |

---

## 9. 配置（环境变量）

变量名与 nestjs `env-example-relational` 一致，差异：

| nestjs | Java |
|---|---|
| `NODE_ENV` | `APP_ENV`（development / test / production） |
| `DATABASE_TYPE`、`DATABASE_SYNCHRONIZE` | 不需要 |
| — | `PASSWORD_HASHER`、`PASSWORD_HASH_CONCURRENCY`（默认 `2`，每个进程同时进行的密码哈希 / 验证数，§5 第 1 项）、`WORKER_DATABASE_MAX_CONNECTIONS`（默认 `10`，worker 进程 Hikari `maximumPoolSize`）、`HEALTH_MEMORY_HEAP_THRESHOLD_MB`（默认 `300`，与 nestjs 写死的值相同；部署 compose 与镜像设为 `500`，§10.3）、`DB_SCHEMA_OWNER`、`OTEL_ENABLED`、`OTEL_EXPORTER_OTLP_ENDPOINT`、`SENTRY_DSN`、`APP_ROLE`（api / worker / migrate / seed / create-superuser / permissions-clear-cache）、`JOBS_IN_PROCESS`（未设置时非生产 `true`、生产 `false`，只认 `true`；api 进程是否运行 JobRunr background server，生产为 `true` 时启动失败，§8「队列」）、`JDK_JAVA_OPTIONS`（JVM 原生读取，追加到镜像 ENTRYPOINT 的参数之后；镜像不读 `JAVA_OPTS`） |

与 nestjs 同名同义、契约相关的变量（必须支持，默认值与 nestjs 一致）：

| 变量 | 默认 | 作用 |
|---|---|---|
| `APP_PORT` | `3000` | 监听端口；前端 Orval target、dev 环境、manage.sh、compose api 健康检查均按 3000 |
| `API_PREFIX` | `api` | 全局前缀，`/`、`/metrics` 除外 |
| `TRUST_PROXY` | `0` | 反向代理**层数**（整数，非布尔），限流按 `X-Forwarded-For` 倒数第 N 跳取客户端 IP |
| `REDIS_ENABLED` | 未设置视为 `false`（只认字符串 `true`）；`env-example-relational` 为 `true` | 关闭时缓存 / 限流回落进程内实现（队列在 Postgres 上，不受影响）；生产强制 `true` |
| `THROTTLE_ENABLED` / `THROTTLE_TTL` / `THROTTLE_LIMIT` | 未设置视为 `true`（nestjs 只有字符串 `false` 关闭），`env-example-relational` 为 `false` / `60` / `100`；TTL 单位秒，两者为 ≥1 的整数 | 限流开关与默认规则（§4.1「限流细则」） |
| `METRICS_ENABLED` / `METRICS_PATH` / `METRICS_TOKEN` | `true`（只有 `false` 关闭）/ `metrics` / 空；`METRICS_TOKEN` 设置时至少 16 个字符，否则启动失败 | 令牌同时保护指标端点与 `/api/v1/health`；生产未配令牌时两者都 403；`METRICS_ENABLED=false` 只关闭 HTTP 指标与 JVM 默认指标（§4.6） |
| `LOG_LEVEL` / `LOG_PRETTY_PRINT` | 未设置时生产 `info` / `false`，其它 `debug` / `true`；`LOG_LEVEL` 取 `fatal/error/warn/info/debug/trace/silent`（映射到 Logback 级别，`silent` 为 `OFF`） | 日志级别与格式（§8） |
| `FILE_DRIVER` | `local` | `local` / `s3` / `s3-presigned`；后两者要求 `ACCESS_KEY_ID`、`SECRET_ACCESS_KEY`、`AWS_DEFAULT_S3_BUCKET`、`AWS_S3_REGION`，可选 `AWS_S3_ENDPOINT` |
| `AWS_S3_FORCE_PATH_STYLE` | `true`（只有 `false` 关闭） | 仅在设置了 `AWS_S3_ENDPOINT` 时生效：MinIO 为 `true`，阿里云 OSS 为 `false` |
| `FILE_IMAGE_MAX_SIZE` / `FILE_PRIVATE_IMAGE_MAX_SIZE` / `FILE_CONTENT_IMAGE_MAX_SIZE` / `FILE_AUDIO_MAX_SIZE` / `FILE_IMPORT_MAX_SIZE` / `FILE_EXPORT_MAX_SIZE` | `5242880` / `10485760` / `5242880` / `20971520` / `52428800` / `20971520`（字节，≥1 的整数） | 各上传用途的大小上限（§4.6 files）；multipart 单文件上限取其最大值 |
| `WECHAT_APP_ID` / `WECHAT_APP_SECRET` / `ALIPAY_APP_ID` | 空 | 除登录外，`healthCheck` 据此决定是否探测微信 / 支付宝（§4.6） |
| `WECHAT_MINI_APP_ID` / `WECHAT_MINI_APP_SECRET` | 空 | 小程序登录、手机号、订阅消息 |
| `WECHAT_MINI_PROGRAM_STATE` / `WECHAT_SUBSCRIBE_ORDER_PAID_TEMPLATE_ID` / `WECHAT_SUBSCRIBE_ORDER_PAID_FIELDS` | `formal` / 空 / 空 | 订阅消息跳转版本（`developer` / `trial` / `formal`）与 `orderPaid` 模板（字段串形如 `orderNo:character_string1,amount:amount2`），非法值启动失败；空串按未设置处理（nestjs 对空串校验失败） |
| `ALIPAY_PRIVATE_KEY` / `ALIPAY_GATEWAY` / `ALIPAY_SIGN_TYPE` / `ALIPAY_CHARSET` / `ALIPAY_SANDBOX` | 空 / 空（`ALIPAY_SANDBOX=true` 时为沙箱网关，否则 `https://openapi.alipay.com/gateway.do`）/ `RSA2` / `utf-8` / `false` | 支付宝登录 |
| `SMS_ACCESS_KEY_ID` / `SMS_ACCESS_KEY_SECRET` / `SMS_SIGN_NAME` / `SMS_TEMPLATE_CODE` / `SMS_ENDPOINT` / `SMS_MOCK_MODE` / `SMS_CODE_LENGTH` / `SMS_CODE_EXPIRES` / `SMS_RESEND_INTERVAL` / `SMS_MAX_ATTEMPTS` | 空 / 空 / 空 / 空 / `dysmsapi.aliyuncs.com` / `false`（只认 `true`）/ `6` / `300` / `60` / `5` | 阿里云短信与验证码（§6.6）；mock 模式只写日志 |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USER` / `MAIL_PASSWORD` / `MAIL_DEFAULT_EMAIL` / `MAIL_DEFAULT_NAME` / `MAIL_IGNORE_TLS` / `MAIL_SECURE` / `MAIL_REQUIRE_TLS` | `MAIL_PORT` 587，三个布尔只认 `true` | nestjs 任何环境都要求 `MAIL_HOST`、`MAIL_DEFAULT_EMAIL`、`MAIL_DEFAULT_NAME`；本服务与 fastapi-temp 一样只在生产强制，非生产未设置 `MAIL_HOST` 时邮件只写日志 |
| `SWAGGER_ENABLED` | 非生产 `true`、生产 `false` | 控制 `/docs`、`/docs-json` |
| `APP_FALLBACK_LANGUAGE` | `en` | 语言回落 |
| `DATABASE_MAX_CONNECTIONS` | `80`（与 nestjs 05c3756 一致） | 所有 API 进程合计的连接上限（与 fastapi-temp 1eb15d5 同义）；Java 单进程，直接作为 api 进程 Hikari `maximumPoolSize`，多副本部署时每个副本设为合计值 / 副本数。api 进程内 JobRunr 的入队与存储读写共用该连接池，计入此值。worker 进程（`APP_ROLE=worker`，JobRunr background server，§8「进程」）是独立 JVM，连接池由 `WORKER_DATABASE_MAX_CONNECTIONS`（默认 `10`，与 fastapi-temp 同名同义）决定。约束：**API 合计 + worker 合计 + migrate / seed ≤ Postgres `max_connections`**（默认 100，另扣超级用户保留连接 3 个）；默认 80 给 worker 与迁移留出余量；虚拟线程模式下连接池是真正的并发上限（§5 第 13 项） |
| `DATABASE_CONNECTION_TIMEOUT_MS` | `10000` | 从连接池获取连接的超时（毫秒），对应 Hikari `connectionTimeout`，与 nestjs 05c3756 同名同义；**下限 250**，低于 250（含 0、负数）启动失败。nestjs 只要求正整数，这里更严是因为 Hikari 对 0 视为无限等待、对小于 250 的值静默改用 30000 |
| `AUTH_JWT_TOKEN_EXPIRES_IN` 等 `*_EXPIRES_IN` | `15m` / `3650d` / `30m` / `1d` | 按 npm `ms` 规则解析（§6.1） |
| `SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD` | 空 | 仅生产 seed 使用（§7「Seed」）；nestjs 侧生产判定变量为 `ENV_MODE=prod`，本服务为 `APP_ENV=production` |

`server.port=${APP_PORT:3000}`：前端模板与 Orval 都指向 `localhost:3000`，默认端口必须保持 3000。`SWAGGER_ENABLED` 语义与 nestjs `app.config.ts` 一致：显式设置时按 `true` 判定，未设置时 `APP_ENV != production` 即开启；关闭时 `/docs` 与 `/docs-json` 都返回 404。不使用 `REDIS_KEY_PREFIX`（§8）。`application.yml` 只用 `${ENV_NAME:default}` 占位读取上述变量，不按环境拆 profile 文件；配置按分组拆成多个 `@ConfigurationProperties` record，敏感字段不进日志，启动时一次性校验。

---

## 10. 工程配置（业界当前最佳实践）

### 10.1 项目配置

| 项 | 做法 |
|---|---|
| 布局 | Maven 多模块（§3）；`${revision}` + flatten-maven-plugin；根 pom `<parent>` 为 `spring-boot-starter-parent` 4.1.2 |
| 依赖版本 | 优先使用 Boot BOM 管理的版本，不单独覆盖；非 Boot 管理的依赖在根 pom `<properties>` 集中声明；maven-enforcer-plugin 3.7.0：`requireJavaVersion [25,)`、`requireMavenVersion 3.9`、`dependencyConvergence`、`banDuplicatePomDependencyVersions`、`bannedDependencies`（`org.dromara:*`、`cn.hutool:*`、`cn.dev33:*`） |
| 编译 | `maven.compiler.release=25`，`-parameters`；Error Prone 2.52.0 + NullAway 0.14.3（`AnnotatedPackages=<pkg>`，JSpecify 模式），排除 `target/generated-sources` |
| 格式化 | Spotless 3.11.0 + palantir-java-format 2.103.0；同时格式化 pom（sortPom）、Markdown、YAML；`spotless:check` 进 CI |
| Checkstyle | 14.4.0，只启用注释与测试命名规则（见 §10.5），不重复格式规则 |
| 测试 | surefire 3.6.1 跑 `*Test`（单元），failsafe 3.6.1 跑 `*IT`（集成）；`junit-platform.properties` 开启并行；详见 §15 |
| 覆盖率 | JaCoCo 0.8.16：各模块 `prepare-agent`（surefire + failsafe），由独立的 `coverage-report` 聚合模块执行 `report-aggregate`，`-Pci` 下对聚合结果 `check`：行 ≥ 80%、分支 ≥ 70%（排除 `target/generated-sources`、`cli`） |
| Maven 配置 | `.mvn/maven.config`：`--no-transfer-progress -Dstyle.color=always`；`.mvn/jvm.config` 固定内存参数；wrapper `distributionUrl` 指向 Maven Central，国内本机用 `MVNW_REPOURL` 或 `~/.m2/settings.xml` 镜像，不写进仓库 |
| 编辑器 | `.editorconfig`；`.vscode/settings.json`（Java 格式化指向 palantir）、`extensions.json` 推荐插件；IntelliJ 用 palantir 插件 |
| 任务入口 | 根目录 `Makefile`：`dev`、`check`、`fmt`、`test`、`it`、`test-contract`、`test-e2e`、`test-parity`、`migrate`、`seed`、`openapi-sync`、`infra`、`ci`、`clean`，全部调用 `./mvnw`（`openapi-sync` 除外，见 §4.3）；其中 `openapi-sync`、`test-contract`、`test-parity` 与 fastapi-temp、go-template 同名同义；CI 与本地调用同一组目标 |

### 10.2 Git 钩子（prek）

| 阶段 | 内容 |
|---|---|
| pre-commit | `spotless:apply`（仅暂存文件）、typos、gitleaks（密钥泄露）、check-yaml / json / xml、end-of-file（排除 `*.md`，md 结尾由 spotless 的 markdown 格式化负责，避免两者互相改写）、trailing-whitespace、注释禁用词检查（§10.5） |
| commit-msg | conventional commits，类型集合与 nestjs commitlint 一致，header ≤72 |
| pre-push | `make check`（spotless:check + 编译（含 Error Prone / NullAway）+ Checkstyle + 单元测试 + ArchUnit） |

### 10.3 Docker

- 多阶段：builder（`eclipse-temurin:25.0.5_8-jdk-noble`，先拷 pom 与 `.mvn` 做 `dependency:go-offline` 缓存层，再拷源码 `./mvnw -DskipTests package`，BuildKit cache mount `~/.m2`；同阶段 `javac` 编译 `docker/HealthProbe.java` 为 class 文件）→ extract（`java -Djarmode=tools -jar app.jar extract --layers --launcher`）→ development（JDK，供 dev compose）→ production（`eclipse-temurin:25.0.5_8-jre-noble`，按 dependencies / spring-boot-loader / snapshot-dependencies / application 分层拷贝，非 root 用户 uid 10001，`ENV APP_PORT=3000`、`HEALTH_MEMORY_HEAP_THRESHOLD_MB=500`）
- 健康检查：Dockerfile 不写 `HEALTHCHECK`（worker / migrate / seed 与 api 共用镜像且没有 HTTP 端口，在 compose 中给 worker 关闭 healthcheck 会让 `up --wait` 报错；与 go-template、fastapi-temp 统一）。只在 compose 的 api 服务配置 healthcheck：`java -cp /app/probe HealthProbe http://localhost:${APP_PORT}/api/v1/health/live`（10s 间隔、3 次重试）。JRE 镜像不含 `jdk.compiler`，不能用单文件源码启动器，所以在 builder 阶段预编译到 `/app/probe`；不额外装 curl。manage.sh 用 `docker compose exec -T api` 执行探针，manage.sh 与 deploy.yml 另外轮询 `/api/v1/health/ready`
- JVM：`-XX:MaxRAMPercentage=60 -XX:+UseZGC`、`-Duser.timezone=UTC`；按 2 CPU / 1GB 容器取值，1GB 下堆上限约 614MB，其余留给元空间、代码缓存、线程栈与直接内存，RSS 不得触及容器限制；`memory_heap` 探针（§4.6）阈值由 `HEALTH_MEMORY_HEAP_THRESHOLD_MB` 配置，代码默认 300（与 nestjs 相同），部署 compose 与 Dockerfile 的 `ENV` 设为 `500`。探针比较的是 **GC 后的堆占用**（最近一次 GC 结束时的整堆占用（`com.sun.management.GarbageCollectorMXBean.getLastGcInfo()` 中结束时间最晚、堆占用非 0 的一次，对全部堆内存池的 `memoryUsageAfterGc` 求和；young GC 后同样包含老年代；尚无 GC 记录时取当前已用量）），不是 `MemoryMXBean` 的已用堆。依据（M5 压测，`docs/benchmark.md`「堆阈值复核」）：1GB 下最大堆 616MiB，GC 后存活数据约 88MiB；ZGC 下已用堆含未回收垃圾、在两次回收之间锯齿上升，正常运行即达 550–600MiB，按已用堆比较时 500 会偶发 503，且与是否泄漏无关；GC 后占用持续超过 500 才说明存活数据异常增长，此时重启是合理的。AOT cache（JDK 25）M5 评估：启动 5.8s → 4.45s，但需要 `-jar` 布局、训练运行需要数据库、镜像增加约 91MB，不采用
- 构建：buildx 多架构（amd64 / arm64），生成 SBOM 与 provenance attestation
- `.dockerignore` 白名单写法；hadolint 检查 Dockerfile
- compose：`docker/` 下与 nestjs 同名同职责（base、postgres、redis、dev、test、ci、ecs），邮件服务用 Mailpit；dev 用 `develop.watch`：宿主编译出的 `template-app/target/classes` 以 `sync+restart` 同步进 api / worker（不引入 DevTools），pom 与其它模块变化时 `rebuild`
- 部署 compose 文件写死项目名（顶层 `name:`），manage.sh 与 deploy.yml 中的 `docker compose` 调用都显式带 `--env-file .env`；否则项目名取部署目录名（如 `docker`），部署目录的 `.env` 也不参与变量替换
- 开发机已有本地 Docker：镜像构建、compose、Testcontainers 在每个阶段都必须在本地跑通，CI 再跑一遍

### 10.4 依赖与安全

- Renovate（`.github/renovate.json5`，结构同 nestjs）：Maven、Maven Wrapper、Docker 镜像、GitHub Actions 统一管理；`helpers:pinGitHubActionDigests`；patch / minor 分组（spring-boot、testcontainers、errorprone + nullaway、spotless + palantir、aws-sdk）自动合并（CI 通过前提下），major 单独 PR；版本上限：JDK / Temurin 镜像 `allowedVersions: "/^25\\./"`（只跟 LTS）、Maven `<4`、Boot BOM 已管理的依赖不单独升级、Postgres / Redis 镜像禁用 major；每周一 9 点前（Asia/Shanghai）
- 漏洞扫描（与 nestjs 的 check.yml 一致）：依赖用 `trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 .`（解析 pom，使用与 image-scan 相同的固定 Trivy 镜像，等价 nestjs `pnpm audit --prod --audit-level high`）；CycloneDX SBOM 2.9.3；Trivy 镜像扫描（HIGH / CRITICAL 且有修复版本）；gitleaks（只扫本次 push / PR 引入的提交）；zizmor（`.github/zizmor.yml` 配置忽略项，门槛 medium）；actionlint
- GitHub Actions 全部按 commit SHA 固定版本并注释版本号，由 Renovate 更新；checkout 一律 `persist-credentials: false`

### 10.5 文档与仓库约定

README（中文，结构参照 nestjs-template）、`docs/`：architecture、auth、database、deploy、testing、cli、contract（契约与 nestjs 的差异清单：未实现的基线 operation、`schemaMappings` 登记、s3-presigned 上传、upload 201）、benchmark（压测基线）。

CLAUDE.md / AGENTS.md 写入架构决策、禁止事项，并原文收录以下规则：

```
## 注释规范

- 禁止段落式注释：不复述代码在做什么，不写"为什么不用另一种写法"的论证，不在 JSDoc 里写背景故事。
- 注释只描述**当前**代码，不写变更历史：禁止"原来/之前/现在改/不再/已修复/回归/新增了/移除了"等字眼；变更原因交给 commit message。
- 测试用例名只陈述行为，不写"回归 / 防止 / 修复"。
- 通过 Agent 派发的子任务同样受本规则约束，派发时必须把本规则原文写进提示词，并要求其收尾时统计新增注释行数。

## 提交与推送

- **只有用户当次明确说「提交 / commit」才能提交**。"开始做吧""继续""按这个改""可以"都只是让你写代码，不是让你提交。
- 一次提交授权只覆盖那一批改动，**不延续到后续任务**。下一轮要提交必须重新拿到许可。
- 改完停在工作区，汇报改了什么 + 验证结果，等用户 review。想提交就问一句，别自己决定。
- push 同理，且比 commit 更严：没听到"推 / push"就不要推。
- 通过 Agent 派发的子任务同样受本规则约束，派发时必须把本规则原文写进提示词。
```

Javadoc 同样适用「禁止背景故事」。机器检查：Checkstyle `TodoComment`（format 为上述禁用词正则）扫描注释；Checkstyle `RegexpSinglelineJava` 检查 `@DisplayName` 与测试方法名不含「回归 / 防止 / 修复 / regression」；prek 钩子在提交前运行同一规则。

---

## 11. CI / CD（参照 nestjs-template）

### 11.1 check.yml

触发：push / PR 到 main、master、`release/**`，手动。`permissions: contents: read`；concurrency 取消旧运行；每个 job `timeout-minutes: 15`（image-scan 20、e2e / parity 30）；`actions/setup-java`（temurin 25，`cache: maven`）；checkout 一律 `persist-credentials: false`；工具镜像（typos、actionlint、zizmor、hadolint、gitleaks、trivy）以版本 + digest 固定在 Makefile 中。job 划分与 nestjs 的 check.yml 对应（lint / type-check / test / build / migrations / workflow-lint / security / image-scan / e2e），另加 contract、parity；每个 job 只调用同名 Makefile 目标，`make ci` 依次运行除 parity 外的全部 job（与 go-template 相同）。

| job | 内容 | 依赖 |
|---|---|---|
| lint | `spotless:check`、Checkstyle、typos、hadolint | – |
| workflow-lint | actionlint（docker 镜像）、zizmor-action（min-severity medium） | – |
| type-check | `./mvnw -Pci compile`：javac + Error Prone + NullAway（Java 的类型门禁）；生成代码编译通过即证明已有 controller 无漏实现方法 | – |
| test | `make test-ci`（`./mvnw -Pci verify`）：单元 + ArchUnit + 集成（Testcontainers，缺 Docker 直接失败）+ `validation-cases.json` 全部用例；`ci/CiGate.java` 检查 JaCoCo 聚合覆盖率（行 ≥80%、分支 ≥70%）与 `ci/test-floor.properties` 中的测试数量下限；上传覆盖率 | – |
| migrations | `scripts/migrations.sh` 起临时 Postgres 容器（不用 GitHub service）；Flyway（`ci/flyway`）migrate → validate → 结构快照与 `db/nestjs-schema.sql` 比对（§7，`schema_snapshot.py`）→ clean → migrate → 快照与第一次相同 | – |
| contract | 契约测试（§15.4） | – |
| security | `make vuln`：`trivy fs`（HIGH / CRITICAL 失败；挂载 `~/.m2` 解析 Maven 依赖）；`make gitleaks`（docker 镜像，`--log-opts` 为本次提交范围）；CycloneDX SBOM（`-Pci`） | – |
| build | `make build`：`-Pci package` 后以产物 jar 对临时空库执行 `APP_ROLE=migrate` 冒烟 | – |
| image-scan | 构建生产镜像 + Trivy（`--severity HIGH,CRITICAL --ignore-unfixed`）；失败单独标红，不阻塞 e2e | – |
| e2e | `make e2e`：compose（base + postgres + redis + ci）起 migrate → seed → api + worker + Mailpit，跑 `-Pe2e`（`APP_URL`、`MAILPIT_URL`），失败输出 compose 日志并上传 | lint、type-check、test、build、migrations、workflow-lint、security |
| parity | 以共享的 `scripts/nestjs/nestjs.compose.yaml`（`FILE_DRIVER=local`，映射 3001）先 `make openapi-sync`（按 `openapi/SOURCE` 固定提交构建 nestjs 参照镜像，同时校验共享产物无 diff），再 `make nestjs-up` 启动 nestjs（不拉 GHCR，不需要 `packages: read`），本服务同一份 seed、同一组测试密钥、两端 `THROTTLE_ENABLED=true` / `TRUST_PROXY=1`，跑 `make test-parity`；另外以第二个关闭限流的本服务实例（3002 端口）运行 nestjs 的 `test/user/auth.e2e-spec.ts`（11 个用例；该 spec 开着限流时对 nestjs 自身也会失败） | build、migrations |

parity job 是「前端无改动切换」最直接的证据。说明：

- nestjs-template（`xingyunqi-tech/nestjs-template`）当前为公开仓库（2026-11-11 `gh repo view` 查询），可匿名 checkout；CI 按固定提交构建 nestjs 参照镜像；仓库日后转私有时补只读 deploy key；test / migrations job 的结构比对用入库的 `db/nestjs-schema.sql`，只有 parity job 重跑 nestjs 迁移生成快照
- nestjs 的 e2e 中只有 `test/user/auth.e2e-spec.ts` 通过 `APP_URL` 以 HTTP 黑盒方式访问，可直接指向本服务复用；其余目录用 `createTestApp` 在进程内启动 Nest，无法复用
- 因此本仓库建 `tests/parity`（§15.5），用例清单按 fastapi-temp `tests/parity` 逐条移植，覆盖本期全部端点的正常与错误路径；同一套用例对 nestjs 也必须通过，用来证明用例本身写的是 nestjs 的真实行为
- 前提：seed 账号一致（`admin@example.com`、`john.doe@example.com`，密码 `Secret00`）、测试用密钥一致、端口可配置
- 限流约定（与 fastapi-temp 0baf8cc 相同，逐条移植用例时照做）：
  - 两端都设 `THROTTLE_ENABLED=true`（`env-example-relational` 默认 false）、`TRUST_PROXY=1`
  - 每个请求带不同的 `X-Forwarded-For`
  - 需要登录的接口按用户计数限流，改用每次运行新建的临时用户
  - 每对响应比对限流头：头集合一致、取值都是整数、`X-RateLimit-Limit` 相等
  - 出现 429 一律算失败，专门的 429 用例除外
- 本地与 CI 执行同一组目标：开发机已有 Docker，`make it`（Testcontainers）、`make test-e2e`、`make test-parity`、镜像构建在每个阶段结束前都在本地跑通，CI 结果用于复核

另有 `drift.yml`：每周定时 + 手动，对 nestjs release 分支 HEAD（而非 `SOURCE` 固定提交）重跑 `openapi-sync`，与入库的共享产物比对，有差异时失败并在 job summary 中列出，提醒执行 `make openapi-sync`。

### 11.2 deploy.yml

与 nestjs 一致：手动触发，输入 deploy、target（aliyun / tencent / all）；多架构镜像推 GHCR（+ 腾讯 TCR），带 SBOM / provenance；矩阵部署 `aliyun-production` / `tencent-production`；scp compose + manage.sh；compose 调用带 `--env-file .env`（§10.3）；SSH 拉镜像（3 次重试）→ 执行 migrate 服务 → `up -d --remove-orphans` → 轮询 `/api/v1/health/ready` 30 次 → 保留最新 3 个镜像；notify `timeout-minutes: 5`。

### 11.3 cleanup-registry.yml

与 nestjs 一致：每周一 03:00（北京时间）+ 手动 dry_run + workflow_call；GHCR 保留 2 个带标签镜像并删无标签；TCR 未配密钥时跳过。

### 11.4 deploy/manage.sh

命令集同 nestjs（start / stop / restart / remove / status / logs / health / cleanup / help），容器名 `springboot-api`，健康检查 `http://localhost:3000/api/v1/health/ready`（端口取 `APP_PORT`）；`start` 先跑 migrate 服务；所有 `docker compose` 调用带 `--env-file .env`，项目名由 compose 文件的 `name:` 固定（§10.3）。

---

## 12. 实施阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M0 骨架** | 在本仓库（已 `git init`）用 Spring Initializr 生成 Boot 4.1.2 / Java 25 / Maven 骨架；按 §3 建模块树；§10 工程配置（Spotless、Error Prone、NullAway、Checkstyle、enforcer、JaCoCo 聚合、prek、Makefile、Renovate、编辑器配置）；ArchUnit 基础规则；NOTICE | `make check`、`make test` 本地全绿（含 Testcontainers 冒烟用例）；enforcer `bannedDependencies` 通过；工作树中除 NOTICE 外无 `dromara` / `hutool` / `satoken` 字样（`git grep -il 'dromara\|hutool\|satoken' -- ':!NOTICE' ':!TEMPLATE_PLAN.md' ':!pom.xml' ':!**/ArchitectureTest.java'` 为空；排除的是方案本身与 enforcer / ArchUnit 的禁用规则） |
| **M1 全局契约** | `openapi-sync`（§4.3 共享产物全部文件，与 fastapi-temp 逐字节一致）、openapi-tools（叠加、可空性修正）、代码生成（含覆盖的 `api.mustache`）、`template-views`、`/docs-json`；**`ClassValidatorEngine`（复刻 ValidationPipe，含 validator.js 移植，本阶段最大工作量）**；Express 路由语义（方法不符 404、尾斜杠、原始路径匹配）、`JsonBodyFilter`（413 / 400 先于路由与鉴权）、OPTIONS 204；信封与 `VoidEnvelopeReturnValueHandler`、异常处理与翻译规则、422 映射、唯一约束表、i18n、Request ID、CORS、安全头（helmet 集合）、分页；契约测试框架 | **`tests/contract/fixtures/validation-cases.json` 约 4516 条 nestjs oracle 用例全部通过**；每种错误形状有单元测试；`/docs-json` 与基线逐字节一致；`void` 接口响应体为 `{"code":200,"message":"success"}`；scope 内除 upload 外全部 operation 返回 HTTP 200；非法 `:id` 为 400；契约测试通过；`tests/parity` 全局契约用例（与 fastapi-temp `test_global_contract.py` 对应）对 nestjs 与本服务均通过 |
| **M2 数据层与认证核心** | MyBatis 注解 SQL 与 camelCase 列引用、Flyway `V1`、核心表、seed（含生产 `SEED_ADMIN_*`）；`@WriteTransaction`（§5 第 8 项）；路由鉴权拦截器、路由声明注册表、JWT；邮箱注册 / 登录 / 确认 / 找回、refresh 轮换、logout、me、改密、注销、会话管理接口；RBAC；users 接口 | 集成测试覆盖认证与用户全部分支（含：过期 access 令牌访问公开登录接口返回 200、refresh 成功轮换、并发刷新落后请求 401 而不吊销、**重放刷新返回 401 且会话被吊销（之后 access 令牌 401 `Session expired`）**、登录响应键顺序、两种令牌 `role` 形状、`exp` 计算、§7 结构快照比对），本地与 CI 均通过；nestjs `test/user/auth.e2e-spec.ts`（11 个用例）以 `APP_URL` 指向本服务全部通过；`tests/parity` 认证与用户部分对两个后端均通过；vite-vue3-temp 不改代码指向本服务，可完成登录、刷新令牌、获取用户信息、退出（后台里的考试业务页面依赖本期不做的接口，不在验收范围）。不以「Orval 重新生成零 diff / `vue-tsc`」作验收：`/docs-json` 与基线逐字节相同，这两项必然成立，不构成证据 |
| **M3 基础设施** | 缓存 / 锁（`SET NX` 格式）、GCRA 限流（§4.1「限流细则」）、日志、指标、链路、健康检查、Sentry、JobRunr 队列与定时（自写退避、去重）、审计、告警、账号清理（含补偿任务）、邮件（`.hbs` + 队列）、文件三驱动（含 s3-presigned、multipart 流式解析）；同步共享产物 `openapi/nest_handlers.json`，复制 fastapi-temp 的 `tests/fixtures/nestjs_mail/` | 单元：`normalizeIp`、限流键（与 nestjs `sha256` 键逐字相同）、GCRA 参数与整数微秒判定（`limit=1` 全新键首个请求放行、fastapi-temp be36777 的反例 `now`、读取 fastapi 格式的 TAT 字符串）、指标路由标签转换、邮件 golden 用例；集成：限流头出现条件（放行 / 429 / 关闭 / 未匹配路由）、按用户与按 IP 计数、Redis 故障回落与恢复（计数器 + 告警）、锁（Redis 与回落）、health 三个探针成功与 503 body、监控令牌、`/metrics` 名称 / 标签 / 桶 / `<unmatched>` / 499、上传矩阵（local 与 MinIO 上的 s3 / s3-presigned，201）、私有文件访问、任务重试 / 去重 / 最终失败告警与 afterCommit 投递、账号清理全流程与补偿、两个清理任务的条件；parity：限流头、files parity 全部用例 |
| **M4 扩展登录与通用模块** | 短信、手机号、微信、支付宝登录；8 个通用模块；代码生成器 | uniapp-template 指向本服务可手机号登录；契约测试本期路由零差异；parity 覆盖全部本期端点 |
| **M5 交付** | Dockerfile（含预编译 HealthProbe）、compose、manage.sh、三个 workflow、Renovate、安全扫描、README / CLAUDE.md / docs；AOT cache 评估 | CI 全绿（lint、workflow-lint、type-check、test、migrations、contract、security、build、image-scan、e2e、parity）；JaCoCo 聚合阈值与测试数量下限达标；k6 压测登录、刷新、`auth/me`、列表四个接口，复用 fastapi-temp `scripts/bench/` 与 `docs/benchmark.md` 的场景与格式，同样 2 CPU / 1GB，结果记录到 `docs/benchmark.md`，与 nestjs 同环境对比 |

阶段流程：每个阶段「开发 → 子 agent review → 修复 → 本地提交」后再进入下一阶段；阶段验收中的容器类检查（Testcontainers、compose e2e、parity、镜像构建）在本地跑通后才提交；不 push。

---

## 13. 待决策

| # | 问题 | 建议 |
|---|---|---|
| 1 | 起点 | **已定（2026-11-11）：Spring Initializr 新起**，RuoYi 只读参考（§2.2）。未采用的备选：在 RuoYi 上裁剪改造——代价是删除约 750 个类与四套 SQL、为约 170 个「保留」类中 56 个去掉 Hutool、重写依赖 Sa-Token 的自动填充、67 个测试类逐个改（含 Hutool / MP-Join / `pageNum` 依赖与违规 Javadoc），并承担 XSS 过滤、Long 转字符串等隐性行为漏删的风险；收益只有 Boot 4 下 Redisson / S3 的调试时间 |
| 2 | 仓库与历史 | **已定（2026-11-11）：新建仓库**。`springboot-template` 为空仓库（分支 `main`），本地不保留 RuoYi 克隆，需要时查阅上游仓库 `github.com/dromara/RuoYi-Vue-Plus`（分支 6.X，提交 `835246e`）；未采用的备选：在 RuoYi 克隆中建 orphan 分支 |
| 3 | JDK 25 还是 21 | 25（LTS、虚拟线程 JEP 491、Boot 4.1 支持）；27 非 LTS 不选 |
| 4 | OpenAPI 策略 | spec-first（§4.3）；备选 code-first springdoc + 契约测试 |
| 5 | 认证框架 | 自写 jsonwebtoken 兼容 JWT + MVC 拦截器，`spring-security-crypto` 做密码哈希（§6.0，M2 定）；备选 Sa-Token |
| 6 | 持久层 | MyBatis 注解 SQL + record（已定）；备选 jOOQ（类型安全 SQL，代码生成可基于 Flyway DDL 离线解析） |
| 7 | groupId / 包名 | 由用户指定；占位 `com.example.template` |
| 8 | 是否与 nestjs 共用表结构（camelCase 列名） | 共用，便于切换与对照测试；实际共库时 `DB_SCHEMA_OWNER=external`（表结构归 nestjs，§7）、`PASSWORD_HASHER=bcrypt` |
| 9 | 默认密码哈希（`PASSWORD_HASHER`） | 默认 argon2id；与 nestjs 共库时配置为 bcrypt |
| 10 | 是否保留六边形分层 | core 保留；通用模块用 service + repository 两层，减少样板 |
| 11 | orders、payments、shares 是否进第二期 | 作为可选模块进第二期 |
| 12 | 任务框架 | JobRunr（PostgreSQL 存储）；备选 Redis Stream 自写（与 nestjs BullMQ 同介质） |
| 13 | 短信 | 自写阿里云 Dysms 客户端；备选 sms4j（多厂商，但已停更 18 个月） |
| 14 | parity 获取 nestjs 的方式 | 已定：按 `openapi/SOURCE` 固定提交 checkout 构建（`make openapi-sync`），不依赖 GHCR；仓库转私有时用只读 deploy key |
| 15 | 是否把 API 契约抽成独立文档 | 抽出 §4、§6、§7、§9 中的契约部分为与语言无关的《后端模板 API 契约》，放在 template 目录下；nestjs、Python、Go、Java 模板都以它为准；机器可读部分即 §4.3「共享产物」，不在文档中重复；生成工具届时也从 fastapi-temp 移到同一位置 |
| 16 | class-validator 元数据导出脚本的推送 | 脚本已在 nestjs-template 落地（`scripts/export-validation.ts`，本地提交 `8b5f296`，`release/exam-prep` 领先 origin 1 个提交），由 nestjs 维护者随 DTO 变更更新；三个后端的 `openapi-sync` 使用它并提交同一份产物。待定的只是何时推送到 origin：推送前 CI 无法按 `SOURCE` 的 `validation_script` checkout，只能使用入库产物 |
| 17 | 是否复现 Express 的弱 ETag 与 304（nestjs 未关闭 Express 默认的 `etag: weak`，GET 响应带 `ETag`，请求带匹配的 `If-None-Match` 时返回 304 空体） | ETag 取值不纳入契约；parity 以各自上一次响应的 ETag / Last-Modified 发送条件请求，比对 304 / 412 / 206 / 416 的状态、正文与头 |
| 18 | parity 用例是否跨语言共享 | **已定（2026-11-12）：不抽跨语言共用数据文件**，逐条移植 fastapi-temp `tests/parity` 的用例（与 go-template 方案 §13 第 8 项一致）；fastapi-temp 的 pytest 用例为基准，Java 用 JUnit 参数化实现，用例 id 相同 |

---

## 14. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 校验执行器与 class-validator / validator.js 语义不等价 | 本服务比 nestjs 宽松或更严 | 未实现约束启动失败；validator.js 逐函数移植；约 4516 条 nestjs oracle 用例作 M1 门禁；parity 覆盖 422 响应体 |
| 事务按 Spring 默认「RuntimeException 即回滚」 | 刷新重放的会话吊销随 401 一起回滚，重放被检测后会话仍然有效 | §5 第 8 项 `@WriteTransaction`（4xx 提交）+ §6.3 克隆库集成测试 |
| 响应可空性 / 分组字段处理遗漏 | 前端拿到缺键或多余键 | 手写视图 + 对 nestjs 录制样本的键存在性测试（§15.3） |
| 422 消息文本、顺序或错误翻译与 nestjs 不一致 | 前端按文本判断时显示不同 | `defaultMessage` 模板 + 装饰器顺序 + oracle 用例 + §4.1 翻译规则单元测试 + parity 比对 `message` / `data` |
| openapi-generator 对个别 schema 生成不理想（allOf、枚举、nullable）或模板覆盖随升级失效 | 编译失败或行为偏差 | `schemaMappings` 映射到手写类并登记；升级生成器后跑 `void` 响应体与契约测试 |
| 序列化细节差异（时间格式、null / 缺省） | 前端显示或判断异常 | §4.4 对齐表 + parity job |
| 共库时两套迁移冲突 | 表结构被覆盖或重复创建 | §7 迁移归属约定，`DB_SCHEMA_OWNER=external` |
| nestjs-template 后续接口或表结构变更 | 两个后端契约漂移 | 基线与 `db/nestjs-schema.sql` 一起手动更新，生成代码编译失败或契约 / schema diff 失败即提醒同步 |
| nestjs 仓库可见性变化 | parity job 无法 checkout | §13 第 14 项，缺权限时 job 失败而非跳过 |
| GitHub / 镜像仓库网络不稳定 | 依赖、镜像下载失败 | Maven 依赖走本机镜像配置，Docker 镜像加速；CI 步骤对网络操作加重试 |
| 虚拟线程下的钉住或 ThreadLocal 滥用 | 吞吐下降 | JDK 25 已解决 `synchronized` 钉住；压测时开 `jdk.VirtualThreadPinned` JFR 事件检查 |
| 与 nestjs 共用 Redis 且两边都开启限流 | 若写同一个键（GCRA 存 TAT 毫秒十进制串，nestjs 存 `INCR` 计数），nestjs `INCR` 报错后降级为单实例内存计数并告警，或误封 | GCRA 写 `throttle:<sha>:default:gcra`，不碰 nestjs 计数键；共用 Redis 时 Python、Go、Java 三个 GCRA 后端共用 `:gcra` 键、共同计数，nestjs 用固定窗口键、单独计数，同一客户端在 nestjs 与 GCRA 后端上的配额相互独立；parity 环境两边各用独立 Redis |
| multipart 错误语义（multer / busboy）与 Servlet / commons-fileupload 不同 | 上传错误的状态码与 `message` 和 nestjs 不同 | 关闭 Spring multipart 解析，流式读取 + 显式错误映射，按 files parity 矩阵逐条对照；文本未在 parity 中核实的项先在 nestjs 上实测 |
| JobRunr 的重试计数、退避公式、job id 去重语义与 BullMQ 不同；JobRunr（Postgres 存储）与 BullMQ 不互通 | 重试次数或间隔与 nestjs 不同；账号清理重复执行或无法再次入队；共用部署时一方投递的任务另一方不会消费 | §8 队列 / 账号清理中的映射；M3 按 JobRunr 9.0.3 源码核对并以集成测试断言尝试次数与间隔 |
| Handlebars.java 转义与 handlebars.js 不同 | 邮件 HTML 字节与 nestjs 不同 | golden 用例逐字节比对，不一致时自定义 `EscapingStrategy` |

---

## 15. 测试策略

五层测试 + 架构测试。开发机已有本地 Docker：五层与 ArchUnit 在每个阶段都在本地跑通，CI 再跑一遍；Testcontainers 起不来时测试直接失败，不跳过（不使用 `disabledWithoutDocker`）。

### 15.1 总览

| 层 | 框架（版本） | 目录 | 命令 | CI job |
|---|---|---|---|---|
| 1 单元 | JUnit Jupiter 6.0.3、Mockito 5.23.0、AssertJ 3.27.7（Boot 管理） | 各模块 `src/test/java`，类名 `*Test` | `./mvnw test`（`make test`） | test |
| 2 API / 集成 | Spring Boot Test 4.1.2、MockMvcTester / RestTestClient（Spring Framework 7.0.10）、Testcontainers 2.0.5 + `@ServiceConnection` | `template-app/src/test/java`，类名 `*IT` | `./mvnw verify`（`make it`） | test |
| 3 E2E | JUnit 6 + `RestTestClient`（绑定 `APP_URL`）；docker compose | `tests/e2e` | `./mvnw -Pe2e verify -DAPP_URL=...`（`make test-e2e`） | e2e |
| 4 契约 | JUnit 6 + openapi-tools 比对器 + Spring MVC `RequestMappingHandlerMapping` | `tools/openapi-tools/src/test`、`template-app/src/test/.../contract` | `./mvnw -pl tools/openapi-tools,template-app verify -Dgroups=contract`（`make test-contract`） | contract |
| 5 Parity | JUnit 6 参数化 + `RestTestClient` + JSON 比对 | `tests/parity` | `./mvnw -Pparity verify -DPARITY_TARGETS=java=...,nestjs=...`（`make test-parity`） | parity |
| 架构 | ArchUnit 1.5.1（archunit-junit5） | `template-app/src/test/.../architecture` | 随 `./mvnw test` | test |

覆盖率：JaCoCo 0.8.16 `report-aggregate` 合并第 1、2 层，`-Pci` 下行 ≥ 80%、分支 ≥ 70%。阈值高于 nestjs 当前值（statements 54 / branches 59），因为新代码从零按测试先行写。

测试数量下限：`-Pci` 下读取 surefire / failsafe 报告，单元与集成测试数分别低于 `ci/test-floor.properties` 中的下限即失败（下限随阶段上调，防止整类被静默跳过或过滤）。

### 15.2 第 1 层：单元测试

- 范围：service（mock 端口接口）、过滤器 / 拦截器 / advice / 返回值处理器（`ApiResponseAdvice`、`VoidEnvelopeReturnValueHandler`、`GlobalExceptionHandler` 及翻译规则、Request ID 过滤器、`LocaleResolver`、`RouteAuthInterceptor` 路由判定、GCRA 脚本参数计算）、工具（`UtcInstantSerializer`、MD5 校验、密码哈希识别与重哈希、422 嵌套 map 构造、唯一约束表、`ms` 解析与 `exp` 计算）、`ClassValidatorEngine`（validator.js 移植函数的单测 + `validation-cases.json` 全部用例，后者属契约层门禁）、JWT 签发与解析（两种 `role` 形状）、openapi-tools 的叠加
- 约定：不起 Spring 上下文；`@ExtendWith(MockitoExtension.class)` + AssertJ；时间用注入的 `Clock`；测试名只陈述行为（`@DisplayName("刷新令牌 hash 等于刚轮换掉的旧值时返回 401 且不吊销会话")`）
- 并行：`junit.jupiter.execution.parallel.enabled=true`

### 15.3 第 2 层：API / 集成测试

- `@SpringBootTest(webEnvironment = MOCK)` + MockMvc，请求与测试方法同线程，过滤器（`JsonBodyFilter`、CORS、安全头）注册进 MockMvc；测试按真实提交运行（不加 `@Transactional`），同一类内的用例各自注册临时用户，不依赖彼此留下的数据
- **克隆库**：`support/TestDatabase` 启动容器后在模板库 `template_seeded` 上执行 Flyway 迁移 + seed（模板库本身不被测试连接，否则无法克隆）；`TestcontainersConfiguration` 为每个 Spring 上下文 `CREATE DATABASE ctx_<n> TEMPLATE template_seeded` 并用 `DynamicPropertyRegistrar` 指向它。afterCommit 副作用、并发刷新 CAS、§6.3 重放吊销在 401 后仍生效、§5 第 8 项 4xx 提交 / 回滚、提交前后清缓存都在这种模式下直接测；默认 `REDIS_ENABLED` 未开启（进程内缓存），Redis 相关用例单独开一个上下文设 `REDIS_ENABLED=true`
- 容器：一个 `@TestConfiguration(proxyBeanMethods = false)` 声明 `PostgreSQLContainer`（`postgres:18.6-alpine`，与 go-template / fastapi-temp 相同；parity 相关用 17.11）与 Redis 容器，`@ServiceConnection` 自动注入连接；容器单例复用；Docker Desktop 的 socket 不在默认路径时按 fastapi-temp 的做法探测 `~/.docker/run/docker.sock`
- Redis 状态：键名写死且与 nestjs 逐字相同（`throttle:`、`lock:`、`jwt:session:`、`session:rotated:` …），生产代码不接受键前缀。所有用到 Redis 的上下文共用一个单例 Redis 容器的 DB 0，不按逻辑库隔离：用例以各自新建的临时用户、随机 id 与随机 `X-Forwarded-For` 构造键，互不冲突；需要 Redis 故障的用例（限流回落、健康检查 down）单独起上下文指向不可用地址
- 文件：local 驱动写工作目录下 `./files`（与 nestjs 相同），存储根目录为 `app.file.local-root`（默认 `files`，不对应环境变量），测试默认 `target/test-files`，上传测试类各自指向独立临时目录；s3 / s3-presigned 用 Testcontainers MinIO 模块（`chainguard/minio:latest`，与 fastapi-temp 相同；单例容器、每个测试类独立桶）
- 邮件：测试内起 SMTP 接收器（GreenMail 或 Mailpit 容器），对收到的 subject / text / html 与 golden 文件逐字节比对
- JobRunr：断言入队用 JobRunr 存储查询；重试次数 / 间隔 / 最终失败告警在克隆库中启动 background server、注入时钟或缩短退避基数验证
- 必含断言：
  - `POST auth/logout` 响应体 JSON 严格等于 `{"code":200,"message":"success"}`（无 `data` 键），覆盖每个 `void` operation
  - 携带**过期 access 令牌**请求 `POST auth/email/login` 返回 200（公开路由忽略 `Authorization`）
  - refresh 令牌经 refresh 链成功轮换；用 access 令牌调用 refresh 返回 401；并发两个相同 refresh 请求，一个成功、一个 401 且会话未被吊销；h0 → h1 → h2 后用 h0 刷新返回 401 且会话被吊销（克隆库模式，之后 h2 刷新 401、原 access 令牌请求 `auth/me` 401 `Session expired`）；非 active / inactive 用户刷新返回 422 `{refresh: "User is not active"}`
  - `POST files/upload` 返回 HTTP 201、体内 `code` 200；scope 内其余 operation（含基线标 201 的 `createUser`）返回 HTTP 200
  - 非法 `:id`（`/users/abc`）返回 400 `{id: "Invalid id"}`（`Accept-Language: zh` 时 `{id: "ID 格式不正确"}`）而非 422；错误方法（`PATCH auth/me`）返回 404，`message` 为 `Cannot PATCH /api/v1/auth/me`、`data: null`；`/api/v1/auth/me/` 与无尾斜杠等价，`/API/V1/AUTH/ME` 与小写路径等价
  - 同一字段多个约束同时失败时，消息按 `validation.json` 顺序以 `, ` 拼接
  - 响应视图键存在性：对 `User` 的 me / admin / public 视图及其它可空字段较多的 DTO，与 `tests/fixtures/nestjs/*.json`（在 parity 环境中从 nestjs 录制）逐键比对「键是否存在」与「值是否为 null」
  - 鉴权与授权分离：JWT-only 路由（`auth/me`）普通用户可访问；权限守卫路由缺权限 403
  - 不输出 `Content-Language`，`Vary` 中不含 `Accept-Language`；`SWAGGER_ENABLED=false` 时 `/docs-json` 返回 404
  - 请求顺序：未知路径带非法 JSON → 400（不是 404）；`auth/me` 不带令牌、JSON 非法 → 400（不是 401）；超过 256kb → 413 `request entity too large`；任意路径 `OPTIONS` → 204；`/api/%68ealth` → 404
  - 安全头集合与 nestjs helmet 输出逐头相等（含 HSTS，测试环境也发送）；多个 `X-Request-Id` 头时生成新 UUID
  - 权限守卫路由缺权限时 403 `message` 为原文 `Missing permissions: …`（不翻译）
  - 未实现的基线 operation 返回 404 信封
  - 错误 `data` 的翻译（`{email: notFound}` → 译文）与 `BusinessException` 不翻译
- 其余覆盖：§4.6 每个端点的成功与错误形状、会话设备列与 `lastActiveAt` 节流、唯一约束映射、实体与库结构一致性（§7）

### 15.4 第 4 层：契约测试

| 检查 | 方式 |
|---|---|
| 运行时输出 | MockMvc 请求 `/docs-json`，响应体与 `openapi/nestjs-baseline.json` 逐字节一致 |
| scope 合法 | `scope.yaml` 中每个 operationId 都存在于基线 |
| 每个 scope operation 都有实现 | 启动上下文后遍历 `RequestMappingHandlerMapping`：scope 内每个 method + path 都映射到某个 handler（这是发现「整个 `*Api` 没有实现类」的唯一手段）；scope 外的基线 operation 没有 handler |
| 没有契约外端点 | 反向检查 handler 全集，只允许 scope 内 operation 与白名单（`/docs`、`/docs-json`、`METRICS_PATH`、`scope.yaml` 的 `undocumentedOperations`（nestjs `@ApiExcludeEndpoint` 路由：`GET files/private/{id}`、`GET files/{path}`，记 method、path、operationId，不进代码生成）、s3-presigned 上传的 JSON 变体、支付回调） |
| handler 名 | scope 内与 `undocumentedOperations` 的每个 handler 方法名等于 operationId，且在 `openapi/nest_handlers.json` 中精确命中（`uploadFile` 按 `FILE_DRIVER` 对应三个 controller，除外），限流键由此得到 |
| 实现签名 | handler 的参数名、请求体类型、返回类型与生成接口一致（生成 DTO 即基线 schema 名） |
| 鉴权声明一致 | 每个 handler 恰有一个鉴权维度注解（§6.2）；基线中带 `security` 的 operation 不得标 `@PublicEndpoint`（例外：`healthCheck` 监控令牌、可选鉴权路由、refresh） |
| 授权声明一致 | 注册表中 `@PermissionsGuarded` 的 operation 及其权限集合与 `scope.yaml` 的 `permissions`（照抄 nestjs）完全一致；JWT-only 路由不得带权限注解 |
| 状态码 | 生成代码中不存在 `@ResponseStatus`（扫描 `target/generated-sources`）；只有 upload 实现方法带 `CREATED` |
| 校验执行器 | `tests/contract/fixtures/validation-cases.json` 全部用例（约 4516 条）逐条通过；`validation.json` 中每个约束 `key` / `name` 都已实现；scope 内每个请求 DTO 在 `validation.json` 中存在 |
| 共享产物 | `openapi/SOURCE` 五个键齐全、`environment` 为 `NODE_ENV=development FILE_DRIVER=local`；有同级 fastapi-temp 时，§4.3 共享产物与其逐字节相同 |
| 生成代码完整 | `skipDefaultInterface=true`，已有 controller 漏方法时编译失败（type-check job） |

需要 Spring 上下文的部分用 `@Tag("contract")` 标记，`make test-contract` 本地与 CI contract job 都运行。

### 15.5 第 3 层 E2E 与第 5 层 Parity

- **E2E**：compose（base + postgres + redis + ci）依次运行 migrate（`APP_ROLE=migrate`）→ seed（`APP_ROLE=seed`）→ api + worker + Mailpit；测试以黑盒 HTTP 访问 `APP_URL`，覆盖完整用户旅程：注册 → 读 Mailpit 邮件确认 → 登录 → 刷新 → 我的设备 → 下线其他设备 → 改密 → 上传文件 → 退出；异步任务通过轮询结果验证（邮件读 `MAILPIT_URL` 的 Mailpit API；账号清理轮询该用户上传的文件直到返回 404）
- **Parity**：同一套 JUnit 参数化用例对 `PARITY_TARGETS` 中每个目标执行，两端使用同一份 seed 与测试密钥；比对 HTTP 状态码、响应头（`X-Request-Id` 回写；安全头逐头相等；CORS 用例比对 `Access-Control-*` 与 `Vary`；放行响应的 `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset` 与 429 的 `Retry-After` 只校验存在且为整数，429 不带 `X-RateLimit-*`；不比对 `Content-Language`，非 CORS 用例不比对 `Vary`）与响应体，忽略字段（`id`、`createdAt`、`updatedAt`、`lastActiveAt`、`token`、`refreshToken`、`tokenExpires`、`hash`、文件 URL 中的随机段等）由各用例类的 `normalize` 按场景处理，不集中到配置文件；`Parity.assertSameJson` 比较序列化后的字符串，键顺序也必须一致；用例清单与 fastapi-temp `tests/parity` 一一对应（目标地址：本服务默认 3000、nestjs 默认 3001，即 `PARITY_TARGETS=java=http://localhost:3000,nestjs=http://localhost:3001`）；用例先对 nestjs 通过才算有效；另外复用 nestjs 的 `test/user/auth.e2e-spec.ts`（11 个用例）指向本服务。文件用例与 fastapi-temp `tests/parity/test_files_parity.py` 一一对应（local 驱动的上传成功 / 错误矩阵含 multipart 边界、`en` / `zh-CN` 两种语言、公开下载头、私有文件访问、uuid 校验）；health 的 503 与 terminus 形状由集成测试覆盖（parity 环境无法让 nestjs 的依赖故障）
- 两者都是独立 Maven 模块，只依赖 HTTP，不依赖 template-app 代码，默认构建不包含（`-Pe2e` / `-Pparity` 激活）

### 15.6 ArchUnit 规则

| 规则 | 内容 |
|---|---|
| 分层 | `..web..` → `..service..`/端口 → `..domain..`；`..persistence..` 只被本模块端口实现使用；service 不依赖 `..web..` 与生成的 DTO |
| 领域纯净 | `..domain..` 不依赖 MyBatis、Spring Web、Jackson、Jakarta Servlet |
| 生成代码 | `template-contract` 的 DTO / `*Api` 只被 `..web..` 引用 |
| 模块无环 | `core.*`、`modules.*` 按包切片无循环依赖 |
| 外部依赖 | 不依赖 `org.dromara`、`cn.hutool`、`cn.dev33` 包（与 enforcer 双保险） |
| 编码约定 | 禁止字段注入（`@Autowired` 字段）、禁止 `System.out` / `printStackTrace`、配置类只通过 `@ConfigurationProperties` 读取环境 |
| 事务 | 业务代码不得直接使用 `@Transactional`，只能用 `@WriteTransaction`（§5 第 8 项）；request DTO 不得带 `jakarta.validation` 注解 |

### 15.7 测试数据

Seed 与 nestjs 一致（角色、状态、权限、`admin@example.com`、`john.doe@example.com`，密码 `Secret00`）；集成测试的模板库已含 seed（与 fastapi-temp 相同），用例需要的其它数据用 builder（`UserFixtures`）造；时间相关逻辑注入 `Clock`。

### 15.8 RuoYi 现有测试

RuoYi 的 67 个测试类（common 61、workflow 6）不迁移：被测代码不进入本模板，且这些测试依赖 Hutool、MP-Join、`pageNum` 分页，部分 Javadoc 含「防止…」等不符合注释规范的写法。本模板全部测试按 §15.1–§15.6 新写。

---

## 附：版本核对来源

- Maven Central `maven-metadata.xml`（2026-11-10 查询，括号内为 metadata 最后更新日）：spring-boot 4.1.2（最新预览 4.2.0-RC2，2026-11-05）、spring-core 7.0.10（7.1.0-RC2）、spring-security-core 7.1.2（7.2.0-RC1）、mybatis-plus-spring-boot4-starter 3.5.18.1 / 3.5.18（2026-10-27，metadata `<release>` 字段写 3.5.18，版本列表含 3.5.18.1）、mybatis 3.6.0、flyway-core 13.12.1（2026-11-05）、liquibase-core 5.0.4、postgresql 42.7.14（2026-10-07）、lettuce-core 7.9.0.RELEASE（8.0.0.BETA1）、redisson / redisson-spring-boot-starter 4.8.0（2026-10-02）、jobrunr / jobrunr-spring-boot-4-starter 9.0.3（2026-10-29）、snail-job-client-starter 2.0.2、jjwt-api 0.13.0（2025-08-20）、nimbus-jose-jwt 10.10（2026-09-17）、springdoc-openapi-starter-webmvc-api 3.2.0、openapi-generator-maven-plugin 7.27.0（2026-11-02）、micrometer-core 1.17.2（1.18.0-RC2）、micrometer-tracing-bridge-otel 1.7.2、micrometer-registry-prometheus 1.17.2、opentelemetry-spring-boot-starter 2.33.1、spring-boot-starter-opentelemetry 4.1.2、sentry-spring-boot-4-starter 8.63.0（2026-11-05）、testcontainers / testcontainers-postgresql / testcontainers-junit-jupiter 2.0.5（2026-04-20）、junit-jupiter 6.1.3、mockito-core 5.24.0、assertj-core 3.27.7（4.0.0-M1）、archunit-junit5 1.5.1（2026-09-25）、spotless-maven-plugin 3.11.0（2026-11-02）、palantir-java-format 2.103.0（2026-10-27）、google-java-format 2.0.0、checkstyle 14.4.0（2026-10-31）、error_prone_core 2.52.0（2026-11-02）、nullaway 0.14.3（2026-10-22）、jspecify 1.0.1、dependency-check-maven 13.0.0、maven-core 3.9.16（4.0.0-rc-7）、maven-wrapper 3.3.4、jib-maven-plugin 3.5.2、mapstruct 1.6.3（1.7.0.Beta2）、bcprov-jdk18on 1.86、caffeine 3.3.1、sms4j-spring-boot-starter 3.3.5（2025-05-10）、weixin-java-miniapp 4.8.6-20261108.214338、alipay-sdk-java 4.40.1067.ALL（2026-11-04）、awssdk s3 2.57.13、jooq 3.21.9、hibernate-core 7.4.15.Final（8.0.0.Beta4）、jacoco-maven-plugin 0.8.16、maven-enforcer-plugin 3.7.0、maven-surefire-plugin / maven-failsafe-plugin 3.6.1、maven-compiler-plugin 3.16.0、shedlock-spring 7.11.0、cyclonedx-maven-plugin 2.9.3、sa-token-core 1.46.0（2026-08-18）
- `spring-boot-dependencies-4.1.2.pom` 管理版本：spring-framework 7.0.10、spring-security 7.1.2、flyway 12.4.0、liquibase 5.0.4、postgresql 42.7.14、lettuce 7.5.2.RELEASE、jackson-bom 3.1.7、micrometer 1.17.2、micrometer-tracing 1.7.2、opentelemetry 1.62.0、testcontainers 2.0.5、junit-jupiter 6.0.3、mockito 5.23.0、assertj 3.27.7、hibernate 7.4.13.Final、jooq 3.21.9、caffeine 3.2.4、logback 1.5.38、tomcat 11.0.27
- Spring Boot 文档 `system-requirements.adoc`（tag v4.1.2）：Java 17 至 27、Maven 3.6.3+
- Maven Central `maven-metadata.xml`（2026-11-14 查询）：commons-fileupload2-core / commons-fileupload2-jakarta-servlet6 最新为 2.0.0-M5（2026-02-17，仍是里程碑版；只依赖 core 的 `MultipartInput`，不需要 jakarta-servlet6 模块）；AWS SDK bom 2.57.13 存在（最新 2.59.1），沿用 2.57.13，HTTP 客户端用 `url-connection-client`（排除默认的 apache-client 与 netty-nio-client）
- openapi-generator `docs/generators/spring.md`、`docs/customization.md`（tag v7.27.0）：`useSpringBoot4`、`useJackson3`、`useRecords`、`interfaceOnly`、`skipDefaultInterface`、`useResponseEntity`（关闭时按首个响应码加 `@ResponseStatus`）、`x-field-extra-annotation`、`x-jackson-json-include-policy`、`openapiNormalizer FILTER`；未提供 OpenAPI Overlay 支持
- endoflife.date（2026-11-10）：eclipse-temurin 25 LTS 最新 25.0.5+8（2026-10-22，支持到 2031-09-30）、27 非 LTS（27.0.1）；oracle-jdk 25 LTS；spring-boot 4.1.2（2026-10-22，4.1 支持到 2027-07-31）、4.0.9；spring-framework 7.0.10（2026-10-22）；PostgreSQL 18.7 / 17.12（2026-11-09）；Redis 8.10.3（2026-10-26）
- Docker Hub：`eclipse-temurin:25.0.5_8-jre-noble`、`25-jre-noble`、`25.0.5_8-jre-alpine` 等标签存在
- GitHub API releases/latest：spring-boot v4.1.2（2026-10-22）、openapi-generator v7.27.0（2026-11-02）、osv-scanner v2.7.1（2026-11-09）、lefthook v2.2.2（2026-10-13）、prek v0.5.10（2026-11-04）、gitleaks v8.30.1（2026-03-21）、trivy v0.76.2（2026-11-05）、zizmor v1.32.0（2026-11-02）、graalvm-ce-builds graal-25.5.5.0.3；actionlint 查询失败，沿用 nestjs check.yml 中的 `rhysd/actionlint:1.7.12`；RuoYi-Vue-Plus GitHub 无 release
- RuoYi-Vue-Plus 仓库：根 `pom.xml`、各模块 `pom.xml`、`R.java`、`PageResult.java`、`GlobalExceptionHandler.java`、`InjectionMetaObjectHandler.java`（依赖 `LoginHelper`）、`MybatisPlusConfig.java`、`RedisConfig.java`、`application.yml`、`script/sql`、`ruoyi-admin/Dockerfile`；core / json / redis / mybatis / oss / mail / web 主代码 170 个类中 56 个 import `cn.hutool`
- nestjs-template（`release/exam-prep` @ `02fdb4d`，契约相对 `9ab33ab` 无变化）：`src/common/filters/all-exceptions.filter.ts`（`translateErrors`、`getTranslatedMessage`）、`src/common/utils/exceptions.util.ts`（`UNIQUE_CONSTRAINT_RESPONSES`）、`src/core/session/session.service.ts`（`wasRecentlyRotatedFrom`）、`src/common/validation-options.ts`、`src/core/auth/auth.controller.ts`（`RefreshResponseDto`、`AuthDeleteAccountDto`）、`src/modules/files/infrastructure/uploader/*/files.controller.ts`（`upload` 无 `@HttpCode`）、`test/platform/platform-foundation.e2e-spec.ts`（upload `expect(201)`）、`src/i18n/{en,zh}/common.json`（`unprocessableEntity`）、`src/config/app.config.ts`（`SWAGGER_ENABLED`）、`src/main.ts`、`nest-cli.json`（未启用 swagger 插件）、`@nestjs/swagger` 12.1.1 输出 `openapi: 3.0.0`、`env-example-relational`、`.github/workflows/check.yml`、`.github/renovate.json5`、`vitest.config` 覆盖率阈值；远程 `xingyunqi-tech/nestjs-template`（2026-11-11 查询为 PUBLIC）
- nestjs-template 补充核对（`02fdb4d`）：`src/common/pipes/parse-id.pipe.ts`（400 `invalidId`）、`src/core/permissions/permissions.guard.ts`（无权限声明 → 403）、`src/core/users/users.controller.ts`（`createUser` 有 `@ApiCreatedResponse` 与 `@HttpCode(200)`）、`src/core/users/domain/user.ts`（`email: string | null` + `@Expose({groups})`）、`src/config/app.config.ts`（`SWAGGER_ENABLED` 默认非生产开启）、`env-example-relational`（`THROTTLE_ENABLED=false`、无 `REDIS_KEY_PREFIX`）；`src/` 中无 `Content-Language` / `Vary` 设置（`Vary: Origin` 来自 CORS 中间件）；nestjs 仓库中暂无 class-validator 元数据导出脚本；`@nestjs/core` 12.4.2 `routes-resolver.js` 未匹配路由抛 ``NotFoundException(`Cannot ${method} ${url}`)``，url 取 `request.originalUrl`；Express 5.3.0 默认关闭 `case sensitive routing` 与 `strict routing`，`etag` 默认 `weak`
- nestjs-template 补充核对（`02fdb4d`，2026-11-11 第三轮）：`src/core/auth/auth.service.ts`（`createSessionAndTokens` 返回键顺序 `refreshToken, token, tokenExpires, user`、登录签发 `role: user.role`、`refreshToken()` 签发 `role: {id}`、`validateUserStatusForAuth(user, 'refresh')`、重放时 `sessionService.revoke` 后抛 401）、`src/core/auth/services/token.service.ts`（`tokenExpires = Date.now() + ms(expiresIn)`）、`src/core/auth/strategies/jwt.strategy.ts`（`Session expired`、`User not found`、`User is not active`）、`src/core/auth/helpers/auth-validation.helper.ts`（422 `userNotActive`）、`src/core/permissions/permissions.guard.ts`（`User not authenticated`、`User role not found`）、`src/core/session/session.service.ts`（`session:rotated:{id}` 只存一个 hash）、`src/common/request-context.ts`（`typeof incoming === 'string'` 且匹配正则才沿用）、`src/main.ts`（helmet 配置、非生产 `enableCors({origin: true, credentials: true})`、`useBodyParser('json', {limit})`、`setGlobalPrefix` 排除 `/`、`metrics`）、`src/infrastructure/database/seeds/relational/user/user-seed.service.ts`（`ENV_MODE === 'prod'`、`SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD`）、`env-example-relational`（`AUTH_*_EXPIRES_IN`）；`8b5f296`（本地未推送）：`scripts/export-validation.ts`。前文「nestjs 仓库中暂无 class-validator 元数据导出脚本」已不成立
- fastapi-temp 实现（`303bf57`）：`src/app/infrastructure/database/session.py`（`Database.transaction`：`HttpError` 提交、其它异常回滚；连接 `server_settings.timezone = UTC`）、`src/app/core/session/service.py`（`forget` 提交前后各清一次缓存）、`src/app/common/security.py`（`ms` 解析、`exp` 计算）、`src/app/common/middleware.py`（helmet 头集合与取值、CORS 默认方法与 `Vary`、原始路径匹配）、`src/app/core/users/repository.py`（排序追加主键）、`src/app/cli/seed.py`、`src/app/common/validation/`（ValidationPipe 复刻与 validator.js 13.15.35 移植）、`openapi/validation.json`（136 个 DTO、32 种约束）、`openapi/SOURCE`、`tests/contract/fixtures/validation-cases.json`（4516 条）、`scripts/{openapi-sync.sh,schema_snapshot.py,gen_validation_cases.py,nestjs/validation-cases.ts}`、`docker/nestjs.compose.yaml`、`tests/integration/{conftest.py,test_schema.py,test_auth.py}`、`tests/parity/*.py`；计划文本修订见 `git diff 76aa96a 303bf57 -- TEMPLATE_PLAN.md`
- nestjs-template M3 相关源码（`02fdb4d`，2026-11-11 第四轮）：`src/infrastructure/throttler/{user-throttler.guard.ts,redis-throttler.storage.ts,throttler.module.ts,config/throttler.config.ts}`、`node_modules/@nestjs/throttler`（6.7.1：`throttler.guard.js` 的头写入与 `generateKey`、`ip.js` 的 `normalizeIp`）、`src/core/auth/auth.controller.ts`（`@Throttle`）、`src/infrastructure/metrics/{metrics.module.ts,metrics.middleware.ts,metrics.controller.ts,config/metrics.config.ts}`、`src/common/guards/monitoring-token.guard.ts`、`src/infrastructure/health/**`、`node_modules/@nestjs/terminus`（12.1.0：`health-indicator.service.js` 的 `responseTime` 与超时文本、`health-check.decorator.js` 的 `Cache-Control`）、`src/modules/home/home.service.ts`、`src/common/filters/all-exceptions.filter.ts`（503 / 413 走 `HttpStatus[code]`）、`src/modules/files/**`（用途表、守卫、拦截器、三个驱动、`file-url.ts`、`files-account.cleaner.ts`）、`node_modules/multer`（2.5.0 错误文本）、`src/infrastructure/queue/**`、`src/infrastructure/account-cleanup/**`、`src/modules/account-cleanup/**`（补偿任务 `25 * * * *` Asia/Shanghai）、`src/infrastructure/scheduler/**`、`src/infrastructure/redis/lock.service.ts`、`src/modules/monitor/alert.service.ts`、`src/integrations/mail/**`、`src/infrastructure/logger/config/logger.config.ts`、`src/app.module.ts`（pino-http 配置）、`src/main.ts`（`setGlobalPrefix` 排除 `metrics`、中间件顺序）、`env-example-relational`
- fastapi-temp M3 工作区（`303bf57` 之后未提交，2026-11-11 读取，仍在编辑）：`src/app/infrastructure/{throttler,metrics,health,logging,observability,queue,redis,scheduler,account_cleanup}/`、`src/app/modules/files/`、`src/app/integrations/mail/`、`src/app/config/{file,health,log,metrics,observability,throttler}.py`、`tests/integration/test_{throttler,metrics,health,files,background_jobs}.py`、`tests/parity/test_files_parity.py`、`tests/fixtures/nestjs_mail/`、`.env.example`、`docs/contract.md`
- **未核实**：`nest_handlers.json` 的生成方式（仓库内无生成脚本）、JobRunr 9.0.3 的重试计数 / 自定义退避 / 按 id 去重 / 并发控制、Handlebars.java 默认转义与 handlebars.js 是否一致、Handlebars.java 版本、阿里云 Dysms 官方 SDK 版本（方案不依赖）、hadolint 版本、Trivy `fs` 对 Maven pom 传递依赖的解析完整度、Renovate `maven-wrapper` manager 对 `only-script` 分发的处理、Spring Boot 4.1 对 JDK 25 AOT cache 的集成方式、JobRunr schema 配置项名称、nestjs 可选鉴权守卫遇到无效令牌时的行为、nestjs GHCR 镜像的包可见性与是否包含 e2e 测试文件、`HttpEntityMethodProcessor` 对 `ResponseEntity<Void>` 是否调用 `ResponseBodyAdvice`（因此未选 `useResponseEntity=true` 方案）、Spring `PathPattern` 按解码后段匹配的具体位置（M1 按 7.0.10 源码核对）
