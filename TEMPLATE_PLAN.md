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
| 构建 | Maven + Maven Wrapper | 3.9.16 / 3.3.4 | Maven 4 仍是 4.0.0-rc-7 |
| 框架 | Spring Boot | 4.1.2（2026-10-22） | 4.2.0-RC2 已出，GA 后由 Renovate 升级；4.1 支持到 2027-07 |
| | Spring Framework | 7.0.10（Boot 管理） | |
| Web | Spring MVC + 内嵌 Tomcat | Tomcat 11.0.27（Boot 管理） | `spring.threads.virtual.enabled=true` |
| JSON | Jackson 3 | 3.1.7（Boot 管理） | 包名 `tools.jackson` |
| 校验 | 请求：自写 class-validator 执行器（`validation.json` 驱动，含 validator.js 移植）；配置：Jakarta Validation（Hibernate Validator） | Boot 管理 | 请求 DTO 不用 Bean Validation 注解，见 §4.3 |
| 契约 | openapi-generator-maven-plugin（`spring` 生成器） | 7.27.0（2026-11-02） | spec-first，见 §4.3 |
| 认证 | Spring Security 7（oauth2-resource-server，Nimbus JOSE） | 7.1.2 / nimbus-jose-jwt 10.10 | 见 §6.0 |
| 密码哈希 | Spring Security `Argon2PasswordEncoder` + `BCryptPasswordEncoder` | 7.1.2；BouncyCastle 1.86 | 见 §5 第 1 项 |
| ORM | **MyBatis-Plus** | 3.5.18.1（`mybatis-plus-spring-boot4-starter`，2026-10-27） | 见 §2.1 |
| 迁移 | **Flyway** | 12.4.0（Boot 管理；最新 13.12.1） | 跟随 Boot BOM，不单独覆盖 |
| 驱动 / 连接池 | PostgreSQL JDBC + HikariCP | 42.7.14 / Boot 管理 | |
| 数据库 | PostgreSQL | 18（18.7） | 独立部署用 18；与 nestjs 共库、parity 对照时用 17（nestjs CI 为 17.11）；镜像标签以 Docker Hub 实际可用为准，测试与 go-template / fastapi-temp 相同用 `postgres:18.6-alpine` / `17.11-alpine` |
| 缓存 / 锁 / 限流 | Redis + Redisson | 8.10（8.10.3）/ 4.8.0 | Redisson 同时提供 `RedisConnectionFactory`；Caffeine（Boot 管理 3.2.4）只在 `REDIS_ENABLED=false` 时作进程内实现，见 §8 |
| 队列 + 定时 | **JobRunr**（`jobrunr-spring-boot-4-starter`） | 9.0.3（2026-10-29） | PostgreSQL 存储，重试 + 指数退避 + 集群内单实例 recurring job |
| HTTP 客户端 | Spring `RestClient` | Framework 7.0.10 | 微信、支付宝、阿里云短信 |
| 支付宝 | alipay-sdk-java | 4.40.1067.ALL | RSA2 签名 |
| 对象存储 | AWS SDK for Java v2（S3） | 2.57.13 | 阿里云 OSS 走 endpoint |
| 邮件 | spring-boot-starter-mail + Handlebars.java | Boot 管理 / 未核实 | 直接复用 nestjs `.hbs` 模板 |
| 日志 | Logback + Boot 结构化日志（logstash JSON） | Logback 1.5.38 | Boot 内置 |
| 指标 | Micrometer + Prometheus registry | 1.17.2（Boot 管理） | 指标名与 nestjs 一致，见 §8 |
| 链路 | `spring-boot-starter-opentelemetry` + micrometer-tracing-bridge-otel | 4.1.2 / 1.7.2 | OTLP 导出，`OTEL_ENABLED` 开关 |
| 错误上报 | sentry-spring-boot-4-starter | 8.63.0 | |
| 对象映射 | MapStruct | 1.6.3 | 1.7 仍是 Beta |
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
| JJWT | 0.13.0 后（2025-08）无发布；Nimbus 已随 spring-security-oauth2-jose 引入，签发与校验同一库 |
| springdoc（code-first） | schema 名、paths 顺序、OpenAPI 3.0 写法与 nestjs 逐项对齐成本高，且无法输出本服务未实现的考试接口，见 §4.3 |
| Spring Data JPA / Hibernate | 共库时表结构归 nestjs，JPA 的实体状态管理、脏检查在「刷新令牌 CAS」「部分唯一索引」场景下反而绕；团队熟悉 MyBatis-Plus |
| jOOQ | 类型安全最好，但代码生成要连库或解析 DDL，模板使用者学习成本高；列入 §13 待决策 |
| Liquibase | XML / YAML changelog 比纯 SQL 繁琐；Flyway 有 Boot 一等支持 |
| SnailJob | 需要单独部署 SnailJob Server 和其库表 |
| Spring `@Scheduled` + ShedLock + Redis Stream 自写队列 | 重试、退避、死信、看板都要自写；JobRunr 一并提供 |
| lock4j、Redisson `RRateLimiter` | 锁直接用 Redisson `RLock`；限流算法用 GCRA（§5 第 4 项），且响应头需与 nestjs 一致 |
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
│   ├── template-common-redis/           # Redisson、缓存、RLock、GCRA 限流
│   ├── template-common-mybatis/         # MP 配置、时间戳自动填充、camelCase 列引用
│   ├── template-common-security/        # Spring Security 双过滤器链、JWT、路由鉴权注解、权限注解、密码哈希
│   ├── template-common-oss/             # FILE_DRIVER=local|s3|s3-presigned
│   ├── template-common-mail/            # 邮件发送 + Handlebars 模板
│   └── template-common-observability/   # 日志脱敏、指标、OTel、Sentry
├── template-contract/                   # openapi-generator 产物：*Api 接口 + DTO（target/generated-sources，不入库）
│   └── src/main/templates/              # 覆盖的 api.mustache（去掉 @ResponseStatus，见 §4.1）
├── template-views/                      # 手写响应视图 record（User 等带 @Expose 分组的 schema），经 schemaMappings 替换生成类
├── template-app/
│   ├── src/main/java/<pkg>/
│   │   ├── TemplateApplication.java
│   │   ├── cli/                         # seed、permissions-clear-cache、create-superuser
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
├── docker/                              # 含共享的 nestjs.compose.yaml（§4.3）
├── deploy/
├── scripts/                             # 含共享的 openapi-sync.sh、schema_snapshot.py、gen_validation_cases.py、nestjs/validation-cases.ts
├── .github/{workflows, renovate.json5, zizmor.yml, ISSUE_TEMPLATE}
├── Dockerfile  .dockerignore  .env.example  .editorconfig  .gitattributes
├── .vscode/{settings,extensions}.json
├── Makefile                             # 统一入口：make dev / check / test / it / test-contract / test-e2e / test-parity / migrate / openapi-sync / ...
├── README.md  CLAUDE.md  AGENTS.md  LICENSE（MIT）  NOTICE（借鉴 RuoYi-Vue-Plus 的文件及其 MIT 署名）
└── docs/
```

`<pkg>`、groupId 见 §13 第 7 项。

### 3.1 模块内分层（对应 nestjs 六边形架构）

```
core/users/
├── domain/              # 纯领域对象（record），不引用 MyBatis / Spring Web
├── persistence/         # UserEntity（@TableName）、UserMapper、UserRepositoryImpl、MapStruct entity ↔ domain
├── UserRepository.java  # 端口接口
├── UserService.java     # 只依赖端口
└── web/                 # UsersController implements 生成的 UsersApi；DTO ↔ domain 映射
```

规则同 nestjs CLAUDE.md：domain 不引用 ORM；controller 不写业务；配置只从 `@ConfigurationProperties` 读取。ArchUnit 强制（§15.6）。

### 3.2 RuoYi 参考价值

| RuoYi 位置 | 处理 |
|---|---|
| `ruoyi-common-redis` `RedisConfig` | 参考其 Redisson + Jackson 3 `TypedJsonJackson3Codec` 组合编解码写法，新写，NOTICE 署名 |
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
| 默认消息 | 400/401/403/404/409/410/422/429/500 → i18n `common.<key>`（`badRequest`、`unauthorized`、`forbidden`、`notFound`、`conflict`、`gone`、`unprocessableEntity`、`tooManyRequests`、`internalServerError`） |
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
| 限流 | 规则同 nestjs（`THROTTLE_ENABLED` 开关，默认 `THROTTLE_LIMIT=100` 次 / `THROTTLE_TTL=60`s，鉴权路由按用户、否则按 IP，IPv6 按子网，客户端 IP 按 `TRUST_PROXY` 跳数解析），算法见 §5 第 4 项；响应头 `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`，429 带 `Retry-After`；契约只约定头存在且为整数，具体数值随算法不同可不一致 |
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

**Java 侧实现**

| 项 | 做法 |
|---|---|
| 信封（有返回值） | `ApiResponseAdvice implements ResponseBodyAdvice<Object>` 写 `{code: 200, message, data}`；已是 `ApiResponse` 透传；Jackson 转换器排在 `StringHttpMessageConverter` 之前，避免 `String` 返回值被包装后类型不符 |
| 信封（`void`） | 生成器在 `useResponseEntity=false` 时给方法加 `@ResponseStatus`，而 `ServletInvocableHandlerMethod` 遇到「返回 null + 有 `@ResponseStatus`」会直接结束请求、输出空体，`ResponseBodyAdvice` 不会执行。做法：`template-contract` 用 `templateDirectory` 覆盖 `api.mustache` 去掉 `@ResponseStatus`；`template-common-web` 注册排在首位的 `VoidEnvelopeReturnValueHandler`，对 `@RestController` 中返回 `void` 的方法写 `{"code":200,"message":"success"}` |
| HTTP 状态 | 覆盖模板去掉所有生成的 `@ResponseStatus`，spec 中的 201 / 204 文档响应码不会进入代码，默认 200；`POST files/upload` 的实现方法单独标 `@ResponseStatus(HttpStatus.CREATED)`（有返回值，正常写体）；集成测试遍历 scope 内全部 operation 断言状态码（只有 upload 为 201） |
| 异常 | `@RestControllerAdvice GlobalExceptionHandler` 处理 MVC 内异常；Security 的 `AuthenticationEntryPoint` / `AccessDeniedHandler`、限流过滤器、请求体超限都调用同一个 `ErrorResponseWriter`，保证过滤器层与控制器层错误形状、翻译规则一致 |
| 422 | 由 `ClassValidatorEngine`（§4.3「校验」）在绑定生成 record 之前对已解析的 JSON 树执行，抛出的校验异常交给 `ErrorResponseWriter`：嵌套对象为嵌套 map、数组下标作 key，与 class-validator 一致；同字段所有失败约束按装饰器顺序（`@Transform` 之后）以 `, ` 拼接，消息为 `validation.json` 中的自定义 `message` 或 `defaultMessage` 模板；类型错误（如 `must be a string`）与其它约束消息一起输出，不由 Jackson 短路；422 的 `data` 是校验消息，按 nestjs 规则同样尝试 `common.<value>` 翻译。请求 DTO 不使用 Bean Validation（`useBeanValidation=false`），`MethodArgumentNotValidException` 等不会出现在请求路径上 |
| 未知字段 | `FAIL_ON_UNKNOWN_PROPERTIES=false`（Boot 默认），等价 whitelist 剔除 |
| 非法 `:id` | 路径参数 `id` 的 `MethodArgumentTypeMismatchException`、`MissingPathVariableException`、方法参数校验失败（`HandlerMethodValidationException` 中来源为 `@PathVariable` 的项）→ 400 `{id: invalidId}`（翻译前的键，输出为译文）；只有请求体 / 查询参数的错误进入 422 |
| 方法不匹配 | `HttpRequestMethodNotSupportedException` → 404 信封（`message` 为 `Cannot <METHOD> <originalUrl>` 原文，`data: null`），不输出 `Allow` 头 |
| 尾斜杠 | 注册 `UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest()`（Spring Framework 6.2+），去掉尾斜杠后再匹配 |
| 原始路径匹配 | Spring MVC `PathPattern` 按解码后的段（`PathContainer.PathSegment.valueToMatch()`）比较，`/api/%68ealth` 会命中 `/api/health`；在 DispatcherServlet 之前的过滤器中，若请求的原始路径（`getRequestURI()`）与解码后路径在静态段上不同，按原始路径重新判断是否命中某个 handler 模板，未命中则直接输出 404 信封；大小写不敏感用 `PathPatternParser.setCaseSensitive(false)`，尾斜杠处理同样不得解码路径。M1 按 Spring Framework 7.0.10 源码核对，并以 parity 用例 `/api/%68ealth`、`/API/HEALTH`、`/api/health/` 验证 |
| 请求体前置解析 | 排在 Spring Security 过滤器链之前的 `JsonBodyFilter`：`application/json` 请求读取并缓存原始体（超过 256kb → 413，非法 JSON / 顶层原始值 → 400），解析结果放入请求属性供执行器与绑定复用；Tomcat 的 `maxPostSize` 与 Boot 的 multipart 上限另行配置，不影响 JSON |
| OPTIONS | 最高优先级的 CORS 过滤器对所有 `OPTIONS` 请求直接写 204（按 §4.1 CORS 规则输出头），不交给 Spring Security 与 DispatcherServlet |
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

1. **`make openapi-sync`**：写入下方「共享产物」全部文件（默认从同级 fastapi-temp 复制，或重跑共享脚本），一起提交
2. **服务**：`GET /docs-json` 返回 classpath 上的 `nestjs-baseline.json` **完整原文，逐字节一致**（`application/json`，不经过 Jackson 重新序列化）；`/docs` 用 Scalar 静态页指向 `/docs-json`；`SWAGGER_ENABLED` 控制，生产默认关闭
3. `openapi/scope.yaml`：本期实现的 operationId 白名单，**只用于代码生成与契约测试**，不影响 `/docs-json`
4. **叠加** → `target/openapi/codegen.json`（只用于代码生成，不对外输出）：应用 `codegen-overlay.yaml`：对响应 DTO 中实际可空的字段补 `nullable: true`、对不总是输出的字段移出 `required`；`@JsonInclude` 用原生 `x-jackson-json-include-policy`。请求校验不经过生成注解（见下「校验」）
5. **生成**（template-contract 模块）：`generatorName=spring`，`interfaceOnly=true`、`skipDefaultInterface=true`、`useSpringBoot4=true`、`useJackson3=true`、`useRecords=true`、`useBeanValidation=false`、`useResponseEntity=false`、`openApiNullable=false`、`documentationProvider=none`、`annotationLibrary=none`、`useTags=true`、`templateDirectory=src/main/templates`、`openapiNormalizer=FILTER=operationId:<scope.yaml 中的列表>`；产物放 `target/generated-sources`，不入库

**其余约束**

- 响应 schema 为**未包装的 DTO**（前端 mutator 自己剥 `.data`）；信封在运行时加，不出现在 spec
- 安全方案 `bearer`；全局 header 参数 `Accept-Language`
- **schema 名与 nestjs DTO 类名一致**：生成器直接用 spec 中的 schema 名作类名
- 查询参数生成为方法上的独立 `@RequestParam`，与 Orval 的 `XxxParams` 对应；`page` / `limit` 空串视为缺省（Spring `defaultValue` 对空值同样生效）
- 文件上传：基线以 local 驱动导出，`POST files/upload` 为 `multipart/form-data`、字段名 `file`，由生成接口实现；`FILE_DRIVER=s3-presigned` 时该路径改收 `application/json` `{fileName, fileSize}`，由手写 controller 在同一路径上以 `consumes = application/json` 实现，列入契约反向检查白名单并在 `docs/contract.md` 说明
- **paths 顺序与 nestjs 一致**：`/docs-json` 输出的就是基线原文
- **响应视图**：带 `@Expose({groups: ['me', 'admin']})` 的 schema（`User` 等）不用生成 record，经 `schemaMappings` 映射到 `template-views` 中的手写 record：字段按 nestjs 分组标 Jackson `@JsonView(Views.Me.class / Views.Admin.class)`，`MapperFeature.DEFAULT_VIEW_INCLUSION=true`（对应 class-transformer 默认 exposeAll，未分组字段总输出），controller 方法标对应视图；视图内字段 `@JsonInclude(ALWAYS)`，可空字段输出 `null`。其它可空问题用 `codegen-overlay.yaml` 修正。所有映射在 `docs/contract.md` 登记，并有 §15.3 的键存在性测试
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
| `docker/nestjs.compose.yaml` | nestjs 参照服务（compose project `nestjs-openapi-sync`，postgres 17.11 / redis 7.4.12 / mailpit，api 映射宿主 `NESTJS_PORT`，默认 3001；迁移 → `ENV_MODE=dev` seed → 启动） | fastapi-temp |
| `tests/parity` 用例 | 用例清单（请求、比对规则）与 fastapi-temp `tests/parity`（`test_global_contract.py`、`test_auth_parity.py`）一一对应，用例 id 相同；目标地址 nestjs 默认 3001、本服务默认 3000 | fastapi-temp 为当前基准；抽成语言无关数据文件见 §13 第 18 项 |

归属：契约内容的唯一来源是 nestjs（`02fdb4d` 契约提交 + `export-validation.ts`）；生成工具（`scripts/openapi-sync.sh`、`scripts/schema_snapshot.py`、`scripts/gen_validation_cases.py`、`scripts/nestjs/validation-cases.ts`、`docker/nestjs.compose.yaml`）由 fastapi-temp 维护，本仓库原样复制，不做改动。`make openapi-sync` 默认从同级 fastapi-temp 复制上表产物（`FASTAPI_DIR`，要求两边 `openapi/SOURCE` 相同）；需要独立重跑时执行复制来的 `scripts/openapi-sync.sh`（依赖 Docker 与 uv），产物必须与 fastapi-temp 中的逐字节相同。`export-validation.ts` 目前只在 nestjs 本地提交 `8b5f296`（`release/exam-prep` 领先 origin 1 个提交，未推送）：本地 `openapi-sync` 从 `NESTJS_DIR` 读取即可，CI 中 checkout 并固定 `validation_script` 提交要求该提交已推送到 origin，推送前 CI 只能使用入库产物。

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

**files**：`POST files/upload?purpose=...`（**HTTP 201**；local / s3 返回 `{file}`；s3-presigned body `{fileName, fileSize}`，返回 `{file, uploadSignedUrl}`，见 §4.3）、`GET files/private/:id`（uuid，非法值按 nestjs 内置 `ParseUUIDPipe` 返回 400，`message` 为原文 `Validation failed (uuid is expected)`（无翻译键），`data: null`）、local 驱动 `GET files/:path`。

**health / metrics**

| 方法 | 路径 | operationId | 鉴权 |
|---|---|---|---|
| GET | /api/v1/health | healthCheck | 监控令牌 |
| GET | /api/v1/health/live | livenessProbe | – |
| GET | /api/v1/health/ready | readinessProbe | – |
| GET | /api/health | simpleHealthCheck | – |
| GET | / | getAppInfo | – |
| GET | /metrics（`METRICS_PATH`） | 不进文档 | 监控令牌 |

**通用模块**：路由、operationId、权限照抄 nestjs 对应 controller（纳入 `scope.yaml` 即生成接口）。

Spring Boot Actuator 只作内部实现（`HealthContributor`、`PrometheusMeterRegistry`），其 `/actuator/**` 端点不对外暴露；上表路径由 controller 实现。

---

## 5. 相对 nestjs-template 的改进（均不破坏现有契约）

| # | 项 | nestjs 现状 | 本模板 |
|---|---|---|---|
| 1 | 密码哈希 | bcrypt(md5)，cost 10 | **argon2id(md5)**（OWASP 首选，Spring Security `Argon2PasswordEncoder` + BouncyCastle）；按哈希前缀识别 `$2a/$2b`（bcrypt）与 `$argon2id`，登录成功后旧 bcrypt 自动重哈希。客户端仍传 MD5，前端无感 |
| 2 | 会话 | 已同步到 nestjs | 会话表可空列 `userAgent`（≤512）、`ip`、`lastActiveAt`（每会话最多 5 分钟写一次）；「我的设备」列表、下线指定设备、下线其他设备三个接口，见 §4.6 |
| 3 | 数据库迁移 | 每个应用容器启动时跑 `migration:run` | 独立一次性 `migrate` 容器（同一镜像，`APP_ROLE=migrate` 只跑 Flyway 后退出），应用 `depends_on: service_completed_successfully`；api / worker 设 `spring.flyway.enabled=false` |
| 4 | 限流算法 | 固定窗口 | Redis Lua **GCRA**（Redisson `RScript`，平滑、无窗口边界突刺）；响应头与 nestjs 的 @nestjs/throttler 一致：`X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`，429 时带 `Retry-After` |
| 5 | Request ID | 已同步到 nestjs（校验 + 回写） | 同 nestjs；日志另带 OTel `trace_id` / `span_id`（Micrometer Tracing 写入 MDC），日志与链路可互相跳转 |
| 6 | OpenAPI | 运行时生成，无版本管理 | spec-first：`/docs-json` 输出入库的基线原文；DTO 与接口由基线生成，契约测试保证本期 operation 全部有实现 |
| 7 | 新表主键 | uuid v4 | **UUIDv7**（按时间有序，索引友好；JDK 25 的 `java.util.UUID` 只有 v3 / v4 工厂方法，自写 RFC 9562 生成器）；已有 int 主键（user 等）保持不变 |
| 8 | 事务 | 服务内手动管理 | service 写方法统一用组合注解 `@WriteTransaction`（= `@Transactional(noRollbackFor = HttpStatusException.class)`，`HttpStatusException` 为 `BusinessException` 与 401 / 403 / 404 / 422 等 HTTP 异常的共同父类），ArchUnit 禁止直接使用 `@Transactional`。结束规则（与 fastapi-temp `src/app/infrastructure/database/session.py` 的 `Database.transaction` 相同，语言无关）：**成功 → 提交；4xx 业务 / HTTP 异常 → 提交已写入的内容后再抛出；其它异常 → 回滚**。Spring 默认「RuntimeException 即回滚」会把 §6.3 刷新重放时的会话吊销随 401 一起撤销，因此不能用默认规则；吊销本身另用 `REQUIRES_NEW` 独立事务执行，双重保证。例外：数据库语句失败（如唯一约束冲突）后事务已处于失败状态，**不得按 4xx 提交**：`DataIntegrityViolationException` 等数据访问异常在事务边界之外（`GlobalExceptionHandler`）才翻译成 422 / 409，service 内不得捕获后改抛 `HttpStatusException`；内层参与事务的方法同样使用 `@WriteTransaction`，避免被标记为 rollback-only 后提交时抛 `UnexpectedRollbackException`。发邮件、投递任务等副作用通过 `TransactionSynchronization.afterCommit` 在提交成功后执行；4xx 提交路径与 fastapi-temp 一致丢弃已登记的提交后副作用（副作用先登记到事务绑定的列表，`@WriteTransaction` 切面在 4xx 时清空列表再提交）。测试要求见 §6.3 |
| 9 | 优雅停机 | 依赖 Nest 默认 | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`；JobRunr 停机等待在途任务；容器 `stop_grace_period` 与之匹配 |
| 10 | 配置校验 | 运行时按模块校验 | 全部 `@ConfigurationProperties` + `@Validated`；启动时自定义校验器汇总全部缺失项一次性报错退出 |
| 11 | 语言头 | 已同步到 nestjs（`AcceptLanguageResolver`） | 标准头 `Accept-Language`，见 §4.2 |
| 12 | 测试隔离 | e2e 连真实服务、串行执行 | 集成测试用 Testcontainers 起 Postgres / Redis；大部分测试在测试事务内执行后回滚（应用内的事务加入测试事务，`REQUIRES_NEW` 的写入除外，见 §15.3）；依赖真实提交的测试从已迁移 + seed 的模板库 `CREATE DATABASE … TEMPLATE` 克隆独立库，不用 TRUNCATE；可并行，见 §15 |
| 13 | 并发模型 | 单线程事件循环 | **虚拟线程**（`spring.threads.virtual.enabled=true`）；JDK 24 起 `synchronized` 不再钉住载体线程（JEP 491），这是选 25 而非 21 的主要原因；连接池大小仍是数据库并发上限 |

第 1 项与「两个后端共用同一个库」有冲突（nestjs 无法验证 argon2id）：共用期间设 `PASSWORD_HASHER=bcrypt`，独立部署时用默认 `argon2id`。第 2、5、11 项与限流响应头已在 nestjs 实现，属于共同契约。

---

## 6. 认证与权限

### 6.0 选型：Spring Security 7 + Nimbus JOSE（不用 Sa-Token）

| 需求 | Spring Security 7 | Sa-Token 1.46.0 |
|---|---|---|
| access JWT `{id, role:{id}, sessionId}` HS256 | `NimbusJwtEncoder` / `NimbusJwtDecoder.withSecretKey`，载荷完全自定义 | 默认不透明 token；sa-token-jwt 的载荷结构固定为其 loginId 模型 |
| refresh JWT 独立密钥 + hash CAS 轮换 | 独立过滤器链 + 第二个 `JwtDecoder` + 自写 service | 无对应能力，仍需自写 |
| 会话存数据库 `session` 表 + 30s 缓存 | 自定义 `Converter<Jwt, AbstractAuthenticationToken>` 查缓存 / 库 | 会话存 Redis，与表结构冲突 |
| 401 / 403 统一信封 | `AuthenticationEntryPoint` / `AccessDeniedHandler` | 异常处理器 |
| 权限注解 | `@RequirePermissions` / `@RequireAnyPermission` + 自定义 `AuthorizationManager`（方法安全） | `@SaCheckPermission` |
| 版本与安全公告 | Boot BOM 管理 | 单独跟踪 |

结论：Sa-Token 能提供的只剩注解，其余都要重写；选 Spring Security。

### 6.1 JWT

- access：`{id, role, sessionId, iat, exp}`，HS256，`AUTH_JWT_SECRET`，默认 15m。`role` 的形状随签发来源不同（照抄 nestjs `token.service.ts` 的调用方）：登录 / 注册类接口签发时为完整角色实体 `{id, name, __entity: "RoleEntity"}`（键顺序如此），`auth/refresh` 签发时为 `{id}`
- refresh：`{sessionId, hash, iat, exp}`，`AUTH_REFRESH_SECRET`，默认 3650d
- `iat` 为秒；`exp = iat + floor(ms(expiresIn) / 1000)`（与 jsonwebtoken 相同）；`tokenExpires = 当前毫秒时间 + ms(AUTH_JWT_TOKEN_EXPIRES_IN)`。`ms` 按 npm `ms` 2.x 规则解析：纯数字（含小数、负数）视为毫秒；单位大小写不敏感，支持 `ms/msec(s)/millisecond(s)`、`s/sec(s)/second(s)`、`m/min(s)/minute(s)`、`h/hr(s)/hour(s)`、`d/day(s)`、`w/week(s)`、`y/yr(s)/year(s)`（年按 365.25 天）；数字与单位间可有空格；超过 100 字符或无法解析视为配置错误（启动失败），不使用 `java.time.Duration.parse` 或 Spring `DurationStyle`
- forgot / confirm-email 令牌独立密钥（30m / 1d）；找回密码令牌内嵌密码指纹 `sha256(hash)[:16]`，改密后失效
- 生产环境四个密钥必须 ≥32 字符且互不相同（启动校验）

### 6.2 access 校验

只认 Bearer 头 → 必须有 `id`、`sessionId` → 查缓存 `jwt:session:{sessionId}`（30s，存 `{statusId, roleId}`），未命中查库：会话存在且属于该用户、用户状态 active / inactive → 以数据库角色覆盖令牌角色；同时节流更新 `lastActiveAt`（每会话每 5 分钟最多一次）。失败 401，`message` 为 nestjs `jwt.strategy.ts` 原文（无翻译键，原文输出）：

| 情形 | message |
|---|---|
| 无令牌、签名 / 过期无效、载荷缺 `id` / `sessionId` | `common.unauthorized` 译文 |
| 会话不存在、已删除或不属于该用户 | `Session expired` |
| 用户不存在 | `User not found` |
| 用户状态不是 active / inactive（缓存命中或查库） | `User is not active` |

会话被删除（logout、吊销、改密删除其它会话、重放吊销）时，`jwt:session:{id}` 缓存**在写库后立即清除一次，并在事务提交后（`afterCommit`）再清除一次**：只在提交前清除时，并发请求可能在提交前从库中读到旧会话并回填缓存，令已删除会话在 30s 内仍可用。

**Java 侧过滤器链**（`BearerTokenAuthenticationFilter` 只要请求带 Bearer 头就会解析并在失败时直接 401，即使路由是 `permitAll`，因此不能只靠 `permitAll`）：

| 链 | 匹配 | 配置 |
|---|---|---|
| refresh 链（`@Order(1)`） | `securityMatcher("/api/v1/auth/refresh")` | `oauth2ResourceServer().jwt()` 使用 refresh 密钥的 `JwtDecoder`；认证后交给 controller 做 §6.3 的轮换 |
| 主链（`@Order(2)`） | 其余全部 | 无状态，关闭 CSRF / 表单登录 / session；access 解码器 + 上面的会话转换器；自定义 `BearerTokenResolver`：只对「需要鉴权」和「可选鉴权」的路由返回令牌，公开路由一律返回 null（忽略 `Authorization` 头）；可选鉴权路由令牌无效时的处理与 nestjs 可选守卫一致（M2 以 parity 用例确认） |

路由声明分两个互相独立的维度，都写在实现方法（或类）上：

| 维度 | 注解 | 规则 |
|---|---|---|
| 鉴权（authentication） | `@PublicEndpoint`、`@OptionalAuth`、`@AuthenticatedOnly`（JWT）、`@MonitoringToken`（`healthCheck`、`/metrics`）；refresh 由独立链处理 | 每个 handler 必须且只能有一个，缺少即启动失败（默认拒绝） |
| 授权（RBAC） | `@PermissionsGuarded` + `@RequirePermissions` / `@RequireAnyPermission` | 只对应 nestjs 使用 `PermissionsGuard` 的路由；`auth/me` 这类仅 JWT 的路由不需要也不应有权限声明；带 `@PermissionsGuarded` 却没有权限列表的路由运行时返回 403（与 nestjs `No permission declared for this route` 一致），启动时打印告警 |

`BearerTokenResolver` 与授权规则都从这份注册表读取。哪些 operation 走权限守卫、需要哪些权限，记录在 `scope.yaml` 的 `permissions` 字段（照抄 nestjs controller），契约测试比对注册表与它；spec 中的 `security` 只交叉核对鉴权维度（§15.4）。

### 6.3 刷新轮换

- `POST /auth/refresh`，refresh JWT 放 Bearer 头，经 refresh 链校验签名与过期
- 会话不存在或已无关联用户 → 401
- `session.hash != payload.hash`：若 `payload.hash` 等于 `session:rotated:{id}` 中记录的「刚被换掉的旧 hash」（轮换时写入，TTL 60s；nestjs `session.service.ts` `wasRecentlyRotatedFrom`）→ 普通 401（并发刷新的落后请求）；否则判为重放，吊销会话并 401。该键只保存**最近一次**被换掉的一个 hash，再次轮换即覆盖：h0 → h1 → h2 两次轮换后再用 h0 刷新属于重放
- 会话匹配后重新读取用户：用户或角色不存在 → 401；用户状态不是 active / inactive → **422** `{refresh: 'userNotActive'}`（nestjs `validateUserStatusForAuth(user, 'refresh')`，输出译文 `User is not active` / `用户未激活`）
- 否则生成新 hash，以 `UPDATE session SET hash = :new WHERE id = :id AND hash = :old` 做 CAS（MP `update(entity, wrapper)` 返回行数），0 行则 401；成功后把旧 hash 写入 `session:rotated:{id}`，返回 `RefreshResponseDto`（新 access 的 `role` 为 `{id}`）
- 重放吊销与 401 同在一个请求内：按 §5 第 8 项，401 属于 4xx，吊销写入必须提交（另以 `REQUIRES_NEW` 执行）。集成测试（克隆库模式）：登录 → 刷新得到 h1 → 再刷新得到 h2 → 用 h0 刷新返回 401 → 此后用 h2 刷新返回 401、用登录时的 access 令牌请求 `auth/me` 返回 401 `Session expired`
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

表名、列名与 nestjs 一致（camelCase 列名），两个后端可指向同一个库。MyBatis-Plus 配置：`map-underscore-to-camel-case: false`，`global-config.db-config.column-format: "\"%s\""`、`table-format: "\"%s\""`（`user` 是 PostgreSQL 保留字，camelCase 列也必须加引号）；XML 中手写 SQL 同样加引号。

本期表：user、role、status、session、permission、role_permission、file、audit_logs、config、region、notification、notification_setting、address、favorite、feedback、user_consent、account_cleanup、admin_alert。约束名照抄 nestjs（含 `UQ_e12875dfb3b1d92d7d7c5377e22`、`REL_75e2be4ce11d447ef43be0e374`、`user_phone_key` 等，§4.1 的唯一约束映射依赖这些名字）；软删 `deletedAt`（MP `@TableLogic` 配为时间戳逻辑删除）；已删除用户释放唯一字段的部分索引同样实现。

迁移：Flyway `V1__init.sql` 一次建出上述结构（以 `db/nestjs-schema.sql` 为起点整理）。Flyway 社区版不支持 undo，CI 用以下步骤替代 fastapi 的 downgrade 校验：空库 `migrate` → `validate` → 结构快照比对 → `clean`（仅 CI 开启）→ 再次 `migrate` 后快照与第一次相同。

结构快照比对以共享产物 `db/nestjs-schema.sql`（§4.3，fastapi-temp `scripts/schema_snapshot.py` 生成的规范化快照，每个对象一行）为准，不用 `pg_dump`：集成测试对 Flyway 迁移后的库执行与 `schema_snapshot.py` 相同的四条 `pg_catalog` 查询并按相同格式输出（openapi-tools 中移植查询与格式化，或调用复制来的脚本），断言「快照 − 实际」恰为 `constraint feedback.FK_feedback_errata …` 与 `constraint feedback.FK_feedback_question …` 两行（引用本期不做的 `errata`、`question` 表），「实际 − 快照」为空；只比较 `public` schema 中上述 18 张表，`flyway_schema_history` 与 JobRunr schema 不在范围内。实体与库结构一致性由集成测试检查：遍历 MP `TableInfoHelper` 中所有实体的表 / 列，与 `information_schema` 比对。

**共库时的迁移归属**：两套迁移工具（TypeORM migrations、Flyway）不能同时管理同一个库，否则会重复建表、互相覆盖。约定：

| 部署方式 | 表结构归属 | Java 侧做法 |
|---|---|---|
| 独立部署（默认） | Flyway | 正常 `migrate` |
| 与 nestjs 共库 | nestjs（TypeORM） | 设 `DB_SCHEMA_OWNER=external`：migrate 容器跳过，启动时只校验所需表 / 列存在；会话设备列已在 nestjs 迁移 `AddSessionClientInfo` 中；JobRunr 表放独立 schema，不与 nestjs 表混放 |

**Seed**：与 nestjs `user-seed.service.ts` 一致。非生产创建 `admin@example.com`、`john.doe@example.com`（密码 `Secret00`）等演示账号；生产不创建演示账号，仅在 `SEED_ADMIN_EMAIL`（去空白、转小写）与 `SEED_ADMIN_PASSWORD` 都设置且尚无管理员时创建管理员。nestjs 以 `ENV_MODE=prod` 判定生产，本服务与 fastapi-temp 一样以 `APP_ENV=production` 判定（nestjs 参照服务在 `docker/nestjs.compose.yaml` 中以 `ENV_MODE=dev` 执行 seed）。seed 幂等，可重复执行。

---

## 8. 基础设施

| 能力 | 实现 |
|---|---|
| 日志 | Boot 结构化日志（生产 logstash JSON、开发彩色单行）；MDC 含 request_id、trace_id、user_id；脱敏 authorization、cookie、password/oldPassword/newPassword/code/phone/email、query 中的 hash/token（自定义 `StructuredLoggingJsonMembersCustomizer` + 请求日志过滤器）；≥500 error、≥400 warn |
| 指标 | `METRICS_PATH`（默认 `/metrics`，controller 调 `PrometheusMeterRegistry.scrape()`），指标名同 nestjs（http_requests_total、http_request_duration_seconds、http_active_connections、database_query_duration_seconds、cache_hits_total、cache_misses_total、queue_jobs_total、admin_alerts_total、admin_alerts_deduplicated_total、throttler_storage_fallback_total），标签 `app=APP_NAME`；Micrometer 名用点分形式注册（如 `http.requests` → `http_requests_total`），Boot 自带的 `http.server.requests` 保留 |
| 链路 | `spring-boot-starter-opentelemetry` 自动埋点 MVC、JDBC、Redis、`RestClient`，OTLP 导出 |
| 健康检查 | 内存、磁盘、数据库 ping、连接池、Redis、微信 / 支付宝 API 可达（Actuator `HealthContributor` 实现，经 §4.6 路径输出 nestjs 形状） |
| Redis 键 | 与 nestjs 完全相同（`jwt:session:{id}`、`session:rotated:{id}`、`sms:code:{type}:{phone}`、`lock:` 前缀等），不加全局前缀，因此不引入 `REDIS_KEY_PREFIX`（nestjs 也没有该变量）；共用 Redis 时两个后端可互认缓存 |
| 缓存 | Redis 命名空间 `cache`，默认 TTL 60s；不使用进程内一级缓存（多实例下失效无法传播）；`REDIS_ENABLED=false` 时整体换成 Caffeine 进程内实现（仅开发 / 测试） |
| 限流存储 | Redis；Redis 异常时回落进程内计数并累加 `throttler_storage_fallback_total`（与 nestjs 一致） |
| 分布式锁 | Redisson `RLock`（看门狗续期） |
| 队列 | JobRunr，PostgreSQL 存储（独立 schema）；重试 3 次、指数退避；队列 mail、notification、account-cleanup；开发 / 测试可在 api 进程内运行 background server，生产只在 worker 运行 |
| 定时任务 | JobRunr recurring job（`@Recurring`，时区 Asia/Shanghai，集群内单实例执行）：03:00 清理会话、04:00 清理已读通知 |
| 审计 | `@Audit` 注解 + AOP 写 `audit_logs` |
| 告警 | `AlertReporter.raise(...)` 写 `admin_alert` 并去重 |
| 账号清理 | 删会话 → 软删 → 投递 `account-cleanup` 任务（5 次重试，10s 退避） |
| 文件 | `FILE_DRIVER=local|s3|s3-presigned`，按用途限制大小，私有文件经接口鉴权 |
| 进程 | 同一镜像按 `APP_ROLE` 运行：`api`（Web，不跑任务）、`worker`（无 Web 端口，JobRunr background server，含 recurring）、`migrate`（一次性 Flyway）、`seed`（一次性写入 seed 数据，幂等）；nestjs 的独立 scheduler 合并进 worker，由 JobRunr 保证单实例执行 |

---

## 9. 配置（环境变量）

变量名与 nestjs `env-example-relational` 一致，差异：

| nestjs | Java |
|---|---|
| `NODE_ENV` | `APP_ENV`（development / test / production） |
| `DATABASE_TYPE`、`DATABASE_SYNCHRONIZE` | 不需要 |
| — | `PASSWORD_HASHER`、`DB_SCHEMA_OWNER`、`OTEL_ENABLED`、`OTEL_EXPORTER_OTLP_ENDPOINT`、`SENTRY_DSN`、`APP_ROLE`（api / worker / migrate / seed）、`JAVA_OPTS` |

与 nestjs 同名同义、契约相关的变量（必须支持，默认值与 nestjs 一致）：

| 变量 | 默认 | 作用 |
|---|---|---|
| `APP_PORT` | `3000` | 监听端口；前端 Orval target、dev 环境、manage.sh、HEALTHCHECK 均按 3000 |
| `API_PREFIX` | `api` | 全局前缀，`/`、`/metrics` 除外 |
| `TRUST_PROXY` | `0` | 反向代理**层数**（整数，非布尔），限流按 `X-Forwarded-For` 倒数第 N 跳取客户端 IP |
| `REDIS_ENABLED` | 未设置视为 `false`（只认字符串 `true`）；`env-example-relational` 为 `true` | 关闭时缓存 / 限流回落进程内实现（队列在 Postgres 上，不受影响）；生产强制 `true` |
| `THROTTLE_ENABLED` / `THROTTLE_TTL` / `THROTTLE_LIMIT` | 未设置视为 `true`，`env-example-relational` 为 `false` / `60` / `100` | 限流开关与默认规则 |
| `METRICS_ENABLED` / `METRICS_PATH` / `METRICS_TOKEN` | `true` / `metrics` / 空 | 指标路径与监控令牌；生产未配令牌时拒绝访问 |
| `SWAGGER_ENABLED` | 非生产 `true`、生产 `false` | 控制 `/docs`、`/docs-json` |
| `APP_FALLBACK_LANGUAGE` | `en` | 语言回落 |
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
| pre-commit | `spotless:apply`（仅暂存文件）、typos、gitleaks（密钥泄露）、check-yaml / json / xml、end-of-file、trailing-whitespace、注释禁用词检查（§10.5） |
| commit-msg | conventional commits，类型集合与 nestjs commitlint 一致，header ≤72 |
| pre-push | `make check`（spotless:check + 编译（含 Error Prone / NullAway）+ Checkstyle + 单元测试 + ArchUnit） |

### 10.3 Docker

- 多阶段：builder（`eclipse-temurin:25.0.5_8-jdk-noble`，先拷 pom 与 `.mvn` 做 `dependency:go-offline` 缓存层，再拷源码 `./mvnw -DskipTests package`，BuildKit cache mount `~/.m2`；同阶段 `javac` 编译 `docker/HealthProbe.java` 为 class 文件）→ extract（`java -Djarmode=tools -jar app.jar extract --layers --launcher`）→ runtime（`eclipse-temurin:25.0.5_8-jre-noble`，按 dependencies / spring-boot-loader / snapshot-dependencies / application 分层拷贝，非 root 用户）
- `HEALTHCHECK`：`java -cp /app/probe HealthProbe http://localhost:${APP_PORT}/api/v1/health/live`。JRE 镜像不含 `jdk.compiler`，不能用单文件源码启动器，所以在 builder 阶段预编译；不额外装 curl。manage.sh 与 deploy.yml 另外轮询 `/api/v1/health/ready`
- 另有 `development` 目标
- JVM：`-XX:MaxRAMPercentage=75 -XX:+UseZGC`、`-Duser.timezone=UTC`；AOT cache（JDK 25）M5 评估后决定是否加训练步骤
- 构建：buildx 多架构（amd64 / arm64），生成 SBOM 与 provenance attestation
- `.dockerignore` 白名单写法；hadolint 检查 Dockerfile
- compose：`docker/` 下与 nestjs 同名同职责（base、postgres、redis、dev、test、ci、ecs），邮件服务用 Mailpit；dev 用 `develop.watch` 同步 `target/classes` 配合 DevTools
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

触发：push / PR 到 main、master、`release/**`，手动。`permissions: contents: read`（parity 另加 `packages: read`）；concurrency 取消旧运行；每个 job `timeout-minutes: 15`（image-scan 20、e2e / parity 30）；`actions/setup-java`（temurin 25，`cache: maven`）；checkout 一律 `persist-credentials: false`；工具镜像在 `env` 中固定版本（actionlint、gitleaks、trivy，与 nestjs 相同写法）。job 划分与 nestjs 的 check.yml 对应（lint / type-check / test / build / migrations / workflow-lint / security / image-scan / e2e），另加 contract、parity。

| job | 内容 | 依赖 |
|---|---|---|
| lint | `spotless:check`、Checkstyle、typos、hadolint | – |
| workflow-lint | actionlint（docker 镜像）、zizmor-action（min-severity medium） | – |
| type-check | `./mvnw -Pci compile`：javac + Error Prone + NullAway（Java 的类型门禁）；生成代码编译通过即证明已有 controller 无漏实现方法 | – |
| test | `./mvnw -Pci verify`：单元 + ArchUnit + 集成（Testcontainers，缺 Docker 直接失败）+ `validation-cases.json` 全部用例；JaCoCo 聚合阈值；测试数量下限检查；上传覆盖率 | – |
| migrations | postgres service；Flyway migrate → validate → 结构快照与 `db/nestjs-schema.sql` 比对（§7，`schema_snapshot.py` 同格式）→ clean → migrate | – |
| contract | 契约测试（§15.4） | – |
| security | `trivy fs`（HIGH / CRITICAL 失败）、gitleaks（docker 镜像，`--log-opts` 为本次提交范围） | – |
| build | `./mvnw -DskipTests package`，产物 jar 可启动（`APP_ROLE=migrate` 对空库冒烟） | – |
| image-scan | 构建生产镜像 + Trivy（`--severity HIGH,CRITICAL --ignore-unfixed`）；失败单独标红，不阻塞 e2e | – |
| e2e | compose（base + postgres + redis + ci）起 migrate → seed → api + worker，跑 `-Pe2e`，失败上传日志 | lint、type-check、test、build、migrations、workflow-lint、security |
| parity | 以共享的 `docker/nestjs.compose.yaml`（`FILE_DRIVER=local`，映射 3001）启动 nestjs（镜像优先拉取 GHCR，`packages: read`；不可用时按 `openapi/SOURCE` 的 `commit` checkout 后构建），本服务同一份 seed、同一组测试密钥，跑 `-Pparity`；重跑 `scripts/openapi-sync.sh`（`export-validation.ts` 取 `validation_script` 提交，要求已推送到 origin），共享产物无 diff；另外 checkout nestjs 固定提交，复用 `test/user/auth.e2e-spec.ts`（11 个用例）打本服务 | build、migrations |

parity job 是「前端无改动切换」最直接的证据。说明：

- nestjs-template（`xingyunqi-tech/nestjs-template`）当前为公开仓库（2026-11-11 `gh repo view` 查询），可匿名 checkout；仍以固定提交 + GHCR 镜像为主，避免每次 CI 构建 nestjs，且仓库日后转私有时只需补 `packages: read` 或只读 deploy key；test / migrations job 的结构比对用入库的 `db/nestjs-schema.sql`，只有 parity job 重跑 nestjs 迁移生成快照
- nestjs 的 e2e 中只有 `test/user/auth.e2e-spec.ts` 通过 `APP_URL` 以 HTTP 黑盒方式访问，可直接指向本服务复用；其余目录用 `createTestApp` 在进程内启动 Nest，无法复用
- 因此本仓库建 `tests/parity`（§15.5），用例清单按 fastapi-temp `tests/parity` 逐条移植，覆盖本期全部端点的正常与错误路径；同一套用例对 nestjs 也必须通过，用来证明用例本身写的是 nestjs 的真实行为
- 前提：seed 账号一致（`admin@example.com`、`john.doe@example.com`，密码 `Secret00`）、测试用密钥一致、端口可配置；两端都设 `THROTTLE_ENABLED=true`（`env-example-relational` 默认 false），限流用例单独放在最后执行并在用例间等待窗口恢复
- 本地与 CI 执行同一组目标：开发机已有 Docker，`make it`（Testcontainers）、`make test-e2e`、`make test-parity`、镜像构建在每个阶段结束前都在本地跑通，CI 结果用于复核

另有 `drift.yml`：每周定时 + 手动，对 nestjs release 分支 HEAD（而非 `SOURCE` 固定提交）重跑 `openapi-sync`，与入库的共享产物比对，有差异时失败并在 job summary 中列出，提醒执行 `make openapi-sync`。

### 11.2 deploy.yml

与 nestjs 一致：手动触发，输入 deploy、target（aliyun / tencent / all）；多架构镜像推 GHCR（+ 腾讯 TCR），带 SBOM / provenance；矩阵部署 `aliyun-production` / `tencent-production`；scp compose + manage.sh；SSH 拉镜像（3 次重试）→ 执行 migrate 服务 → `up -d --remove-orphans` → 轮询 `/api/v1/health/ready` 30 次 → 保留最新 3 个镜像；notify `timeout-minutes: 5`。

### 11.3 cleanup-registry.yml

与 nestjs 一致：每周一 03:00（北京时间）+ 手动 dry_run + workflow_call；GHCR 保留 2 个带标签镜像并删无标签；TCR 未配密钥时跳过。

### 11.4 deploy/manage.sh

命令集同 nestjs（start / stop / restart / remove / status / logs / health / cleanup / help），容器名 `springboot-api`，健康检查 `http://localhost:3000/api/v1/health/ready`（端口取 `APP_PORT`）；`start` 先跑 migrate 服务。

---

## 12. 实施阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M0 骨架** | 在本仓库（已 `git init`）用 Spring Initializr 生成 Boot 4.1.2 / Java 25 / Maven 骨架；按 §3 建模块树；§10 工程配置（Spotless、Error Prone、NullAway、Checkstyle、enforcer、JaCoCo 聚合、prek、Makefile、Renovate、编辑器配置）；ArchUnit 基础规则；NOTICE | `make check`、`make test` 本地全绿（含 Testcontainers 冒烟用例）；enforcer `bannedDependencies` 通过；工作树中除 NOTICE 外无 `dromara` / `hutool` / `satoken` 字样（`git grep -il 'dromara\|hutool\|satoken' -- ':!NOTICE'` 为空） |
| **M1 全局契约** | `openapi-sync`（§4.3 共享产物全部文件，与 fastapi-temp 逐字节一致）、openapi-tools（叠加、可空性修正）、代码生成（含覆盖的 `api.mustache`）、`template-views`、`/docs-json`；**`ClassValidatorEngine`（复刻 ValidationPipe，含 validator.js 移植，本阶段最大工作量）**；Express 路由语义（方法不符 404、尾斜杠、原始路径匹配）、`JsonBodyFilter`（413 / 400 先于路由与鉴权）、OPTIONS 204；信封与 `VoidEnvelopeReturnValueHandler`、异常处理与翻译规则、422 映射、唯一约束表、i18n、Request ID、CORS、安全头（helmet 集合）、分页；契约测试框架 | **`tests/contract/fixtures/validation-cases.json` 约 4516 条 nestjs oracle 用例全部通过**；每种错误形状有单元测试；`/docs-json` 与基线逐字节一致；`void` 接口响应体为 `{"code":200,"message":"success"}`；scope 内除 upload 外全部 operation 返回 HTTP 200；非法 `:id` 为 400；契约测试通过；`tests/parity` 全局契约用例（与 fastapi-temp `test_global_contract.py` 对应）对 nestjs 与本服务均通过 |
| **M2 数据层与认证核心** | MyBatis-Plus camelCase 映射、Flyway `V1`、核心表、seed（含生产 `SEED_ADMIN_*`）；`@WriteTransaction`（§5 第 8 项）；Spring Security 双过滤器链、路由声明注册表、JWT；邮箱注册 / 登录 / 确认 / 找回、refresh 轮换、logout、me、改密、注销、会话管理接口；RBAC；users 接口 | 集成测试覆盖认证与用户全部分支（含：过期 access 令牌访问公开登录接口返回 200、refresh 成功轮换、并发刷新落后请求 401 而不吊销、**重放刷新返回 401 且会话被吊销（之后 access 令牌 401 `Session expired`）**、登录响应键顺序、两种令牌 `role` 形状、`exp` 计算、§7 结构快照比对），本地与 CI 均通过；nestjs `test/user/auth.e2e-spec.ts`（11 个用例）以 `APP_URL` 指向本服务全部通过；`tests/parity` 认证与用户部分对两个后端均通过；vite-vue3-temp 不改代码指向本服务，可完成登录、刷新令牌、获取用户信息、退出（后台里的考试业务页面依赖本期不做的接口，不在验收范围）。不以「Orval 重新生成零 diff / `vue-tsc`」作验收：`/docs-json` 与基线逐字节相同，这两项必然成立，不构成证据 |
| **M3 基础设施** | 缓存 / 锁、GCRA 限流、日志、指标、链路、健康检查、Sentry、JobRunr 队列与定时、审计、告警、账号清理、邮件、文件三驱动 | 集成测试覆盖限流（含响应头）、健康检查、上传（201）、任务重试与 afterCommit 投递；`/metrics` 指标名与 nestjs 一致 |
| **M4 扩展登录与通用模块** | 短信、手机号、微信、支付宝登录；8 个通用模块；代码生成器 | uniapp-template 指向本服务可手机号登录；契约测试本期路由零差异；parity 覆盖全部本期端点 |
| **M5 交付** | Dockerfile（含预编译 HealthProbe）、compose、manage.sh、三个 workflow、Renovate、安全扫描、README / CLAUDE.md / docs；AOT cache 评估 | CI 全绿（lint、workflow-lint、type-check、test、migrations、contract、security、build、image-scan、e2e、parity）；JaCoCo 聚合阈值与测试数量下限达标；k6 压测登录、刷新、`auth/me`、列表四个接口，结果记录到 `docs/benchmark.md`，与 nestjs 同环境对比 |

阶段流程：每个阶段「开发 → 子 agent review → 修复 → 本地提交」后再进入下一阶段；阶段验收中的容器类检查（Testcontainers、compose e2e、parity、镜像构建）在本地跑通后才提交；不 push。

---

## 13. 待决策

| # | 问题 | 建议 |
|---|---|---|
| 1 | 起点 | **已定（2026-11-11）：Spring Initializr 新起**，RuoYi 只读参考（§2.2）。未采用的备选：在 RuoYi 上裁剪改造——代价是删除约 750 个类与四套 SQL、为约 170 个「保留」类中 56 个去掉 Hutool、重写依赖 Sa-Token 的自动填充、67 个测试类逐个改（含 Hutool / MP-Join / `pageNum` 依赖与违规 Javadoc），并承担 XSS 过滤、Long 转字符串等隐性行为漏删的风险；收益只有 Boot 4 下 Redisson / S3 的调试时间 |
| 2 | 仓库与历史 | **已定（2026-11-11）：新建仓库**。`springboot-template` 为空仓库（分支 `main`），本地不保留 RuoYi 克隆，需要时查阅上游仓库 `github.com/dromara/RuoYi-Vue-Plus`（分支 6.X，提交 `835246e`）；未采用的备选：在 RuoYi 克隆中建 orphan 分支 |
| 3 | JDK 25 还是 21 | 25（LTS、虚拟线程 JEP 491、Boot 4.1 支持）；27 非 LTS 不选 |
| 4 | OpenAPI 策略 | spec-first（§4.3）；备选 code-first springdoc + 契约测试 |
| 5 | 认证框架 | Spring Security 7 + Nimbus（§6.0）；备选 Sa-Token |
| 6 | 持久层 | MyBatis-Plus；备选 jOOQ（类型安全 SQL，代码生成可基于 Flyway DDL 离线解析） |
| 7 | groupId / 包名 | 由用户指定；占位 `com.example.template` |
| 8 | 是否与 nestjs 共用表结构（camelCase 列名） | 共用，便于切换与对照测试；实际共库时 `DB_SCHEMA_OWNER=external`（表结构归 nestjs，§7）、`PASSWORD_HASHER=bcrypt` |
| 9 | 默认密码哈希（`PASSWORD_HASHER`） | 默认 argon2id；与 nestjs 共库时配置为 bcrypt |
| 10 | 是否保留六边形分层 | core 保留；通用模块用 service + repository 两层，减少样板 |
| 11 | orders、payments、shares 是否进第二期 | 作为可选模块进第二期 |
| 12 | 任务框架 | JobRunr（PostgreSQL 存储）；备选 Redis Stream 自写（与 nestjs BullMQ 同介质） |
| 13 | 短信 | 自写阿里云 Dysms 客户端；备选 sms4j（多厂商，但已停更 18 个月） |
| 14 | parity 获取 nestjs 的方式 | GHCR 镜像（固定标签）+ `packages: read`；备选按固定提交 checkout 构建（仓库现为公开，转私有时用只读 deploy key） |
| 15 | 是否把 API 契约抽成独立文档 | 抽出 §4、§6、§7、§9 中的契约部分为与语言无关的《后端模板 API 契约》，放在 template 目录下；nestjs、Python、Go、Java 模板都以它为准；机器可读部分即 §4.3「共享产物」，不在文档中重复；生成工具届时也从 fastapi-temp 移到同一位置 |
| 16 | class-validator 元数据导出脚本的推送 | 脚本已在 nestjs-template 落地（`scripts/export-validation.ts`，本地提交 `8b5f296`，`release/exam-prep` 领先 origin 1 个提交），由 nestjs 维护者随 DTO 变更更新；三个后端的 `openapi-sync` 使用它并提交同一份产物。待定的只是何时推送到 origin：推送前 CI 无法按 `SOURCE` 的 `validation_script` checkout，只能使用入库产物 |
| 17 | 是否复现 Express 的弱 ETag 与 304（nestjs 未关闭 Express 默认的 `etag: weak`，GET 响应带 `ETag`，请求带匹配的 `If-None-Match` 时返回 304 空体） | 不纳入契约，parity 忽略 ETag 头并且不发送 If-None-Match |
| 18 | parity 用例是否跨语言共享 | 当前基准是 fastapi-temp `tests/parity`（pytest），Java 按用例清单逐条移植；建议之后抽出一份语言无关的用例数据文件（HTTP 请求 + 比对规则），三个后端各写一个很薄的执行器，放在 template 目录的独立仓库或 fastapi-temp，与 §4.3 其它共享产物同样逐字节复制 |

---

## 14. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 校验执行器与 class-validator / validator.js 语义不等价 | 本服务比 nestjs 宽松或更严 | 未实现约束启动失败；validator.js 逐函数移植；约 4516 条 nestjs oracle 用例作 M1 门禁；parity 覆盖 422 响应体 |
| 事务按 Spring 默认「RuntimeException 即回滚」 | 刷新重放的会话吊销随 401 一起回滚，重放被检测后会话仍然有效 | §5 第 8 项 `@WriteTransaction` + 吊销 `REQUIRES_NEW` + §6.3 克隆库集成测试 |
| 响应可空性 / 分组字段处理遗漏 | 前端拿到缺键或多余键 | 手写视图 + 对 nestjs 录制样本的键存在性测试（§15.3） |
| 422 消息文本、顺序或错误翻译与 nestjs 不一致 | 前端按文本判断时显示不同 | `defaultMessage` 模板 + 装饰器顺序 + oracle 用例 + §4.1 翻译规则单元测试 + parity 比对 `message` / `data` |
| openapi-generator 对个别 schema 生成不理想（allOf、枚举、nullable）或模板覆盖随升级失效 | 编译失败或行为偏差 | `schemaMappings` 映射到手写类并登记；升级生成器后跑 `void` 响应体与契约测试 |
| 序列化细节差异（时间格式、null / 缺省） | 前端显示或判断异常 | §4.4 对齐表 + parity job |
| 共库时两套迁移冲突 | 表结构被覆盖或重复创建 | §7 迁移归属约定，`DB_SCHEMA_OWNER=external` |
| nestjs-template 后续接口或表结构变更 | 两个后端契约漂移 | 基线与 `db/nestjs-schema.sql` 一起手动更新，生成代码编译失败或契约 / schema diff 失败即提醒同步 |
| nestjs 仓库可见性或 GHCR 包权限变化 | parity job 无法拉取 | §13 第 14 项，缺权限时 job 失败而非跳过 |
| GitHub / 镜像仓库网络不稳定 | 依赖、镜像下载失败 | Maven 依赖走本机镜像配置，Docker 镜像加速；CI 步骤对网络操作加重试 |
| 虚拟线程下的钉住或 ThreadLocal 滥用 | 吞吐下降 | JDK 25 已解决 `synchronized` 钉住；压测时开 `jdk.VirtualThreadPinned` JFR 事件检查 |

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

- 范围：service（mock 端口接口）、过滤器 / 拦截器 / advice / 返回值处理器（`ApiResponseAdvice`、`VoidEnvelopeReturnValueHandler`、`GlobalExceptionHandler` 及翻译规则、Request ID 过滤器、`LocaleResolver`、`BearerTokenResolver` 路由判定、GCRA 脚本参数计算）、工具（UUIDv7、`UtcInstantSerializer`、MD5 校验、密码哈希识别与重哈希、422 嵌套 map 构造、唯一约束表、`ms` 解析与 `exp` 计算）、`ClassValidatorEngine`（validator.js 移植函数的单测 + `validation-cases.json` 全部用例，后者属契约层门禁）、JWT 签发与解析（两种 `role` 形状）、openapi-tools 的叠加
- 约定：不起 Spring 上下文；`@ExtendWith(MockitoExtension.class)` + AssertJ；时间用注入的 `Clock`；测试名只陈述行为（`@DisplayName("刷新令牌 hash 等于刚轮换掉的旧值时返回 401 且不吊销会话")`）
- 并行：`junit.jupiter.execution.parallel.enabled=true`

### 15.3 第 2 层：API / 集成测试

- `@SpringBootTest(webEnvironment = MOCK)` + `MockMvcTester`（或 `RestTestClient.bindTo(mockMvc)`），请求与测试方法同线程，过滤器（`JsonBodyFilter`、CORS、安全头）注册进 MockMvc；默认测试方法加 `@Transactional`，**每个测试回滚**（应用内 `@WriteTransaction` 加入测试事务；`REQUIRES_NEW` 的写入会真实提交，涉及它的用例归入下一条）
- **需要真实提交的测试用克隆库，不用回滚、不用 TRUNCATE**：afterCommit 副作用（发邮件、投递任务）、JobRunr 入队与执行、并发刷新 CAS（多线程各自事务）、§6.3 重放吊销在 401 后仍生效、§5 第 8 项 4xx 提交 / 数据库错误回滚、提交前后清缓存。容器启动后先在模板库 `app_template` 上执行 Flyway 迁移 + seed（模板库本身不被测试连接，否则无法克隆），回滚模式的测试共用一份克隆出的工作库；这类测试每个类（或每个用例）`CREATE DATABASE t_<随机> TEMPLATE app_template` 克隆独立库，`@DynamicPropertySource` 指向它，用完 `DROP`；标 `@Tag("committing")`，可与其它测试并行
- 容器：一个 `@TestConfiguration(proxyBeanMethods = false)` 声明 `PostgreSQLContainer`（`postgres:18.6-alpine`，与 go-template / fastapi-temp 相同；parity 相关用 17.11）与 Redis 容器，`@ServiceConnection` 自动注入连接；容器单例复用；Docker Desktop 的 socket 不在默认路径时按 fastapi-temp 的做法探测 `~/.docker/run/docker.sock`
- Redis 状态：每个测试使用随机 key 前缀，`@AfterEach` 按前缀清理
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
| 没有契约外端点 | 反向检查 handler 全集，只允许 scope 内 operation 与白名单（`/docs`、`/docs-json`、`METRICS_PATH`、`/files/{path}`、s3-presigned 上传的 JSON 变体、支付回调） |
| 实现签名 | handler 的参数名、请求体类型、返回类型与生成接口一致（生成 DTO 即基线 schema 名） |
| 鉴权声明一致 | 每个 handler 恰有一个鉴权维度注解（§6.2）；基线中带 `security` 的 operation 不得标 `@PublicEndpoint`（例外：`healthCheck` 监控令牌、可选鉴权路由、refresh） |
| 授权声明一致 | 注册表中 `@PermissionsGuarded` 的 operation 及其权限集合与 `scope.yaml` 的 `permissions`（照抄 nestjs）完全一致；JWT-only 路由不得带权限注解 |
| 状态码 | 生成代码中不存在 `@ResponseStatus`（扫描 `target/generated-sources`）；只有 upload 实现方法带 `CREATED` |
| 校验执行器 | `tests/contract/fixtures/validation-cases.json` 全部用例（约 4516 条）逐条通过；`validation.json` 中每个约束 `key` / `name` 都已实现；scope 内每个请求 DTO 在 `validation.json` 中存在 |
| 共享产物 | `openapi/SOURCE` 五个键齐全、`environment` 为 `NODE_ENV=development FILE_DRIVER=local`；有同级 fastapi-temp 时，§4.3 共享产物与其逐字节相同 |
| 生成代码完整 | `skipDefaultInterface=true`，已有 controller 漏方法时编译失败（type-check job） |

需要 Spring 上下文的部分用 `@Tag("contract")` 标记，`make test-contract` 本地与 CI contract job 都运行。

### 15.5 第 3 层 E2E 与第 5 层 Parity

- **E2E**：compose（base + postgres + redis + ci）依次运行 migrate（`APP_ROLE=migrate`）→ seed（`APP_ROLE=seed`）→ api + worker + Mailpit；测试以黑盒 HTTP 访问 `APP_URL`，覆盖完整用户旅程：注册 → 读 Mailpit 邮件确认 → 登录 → 刷新 → 我的设备 → 下线其他设备 → 改密 → 上传文件 → 退出；异步任务（邮件、账号清理）通过轮询结果验证
- **Parity**：同一套 JUnit 参数化用例对 `PARITY_TARGETS` 中每个目标执行，两端使用同一份 seed 与测试密钥；比对 HTTP 状态码、响应头（`X-Request-Id` 回写；安全头逐头相等；CORS 用例比对 `Access-Control-*` 与 `Vary`；`X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset` 与 429 的 `Retry-After` 只校验存在且为整数；不比对 `Content-Language`，非 CORS 用例不比对 `Vary`）与响应体，忽略字段列表（`id`、`createdAt`、`updatedAt`、`lastActiveAt`、`token`、`refreshToken`、`tokenExpires`、`hash`、文件 URL 中的随机段）集中维护在 `tests/parity/src/test/resources/ignore.yaml`；用例清单与 fastapi-temp `tests/parity` 一一对应（目标地址：本服务默认 3000、nestjs 默认 3001，即 `PARITY_TARGETS=java=http://localhost:3000,nestjs=http://localhost:3001`）；用例先对 nestjs 通过才算有效；另外复用 nestjs 的 `test/user/auth.e2e-spec.ts`（11 个用例）指向本服务
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
- openapi-generator `docs/generators/spring.md`、`docs/customization.md`（tag v7.27.0）：`useSpringBoot4`、`useJackson3`、`useRecords`、`interfaceOnly`、`skipDefaultInterface`、`useResponseEntity`（关闭时按首个响应码加 `@ResponseStatus`）、`x-field-extra-annotation`、`x-jackson-json-include-policy`、`openapiNormalizer FILTER`；未提供 OpenAPI Overlay 支持
- endoflife.date（2026-11-10）：eclipse-temurin 25 LTS 最新 25.0.5+8（2026-10-22，支持到 2031-09-30）、27 非 LTS（27.0.1）；oracle-jdk 25 LTS；spring-boot 4.1.2（2026-10-22，4.1 支持到 2027-07-31）、4.0.9；spring-framework 7.0.10（2026-10-22）；PostgreSQL 18.7 / 17.12（2026-11-09）；Redis 8.10.3（2026-10-26）
- Docker Hub：`eclipse-temurin:25.0.5_8-jre-noble`、`25-jre-noble`、`25.0.5_8-jre-alpine` 等标签存在
- GitHub API releases/latest：spring-boot v4.1.2（2026-10-22）、openapi-generator v7.27.0（2026-11-02）、osv-scanner v2.7.1（2026-11-09）、lefthook v2.2.2（2026-10-13）、prek v0.5.10（2026-11-04）、gitleaks v8.30.1（2026-03-21）、trivy v0.76.2（2026-11-05）、zizmor v1.32.0（2026-11-02）、graalvm-ce-builds graal-25.5.5.0.3；actionlint 查询失败，沿用 nestjs check.yml 中的 `rhysd/actionlint:1.7.12`；RuoYi-Vue-Plus GitHub 无 release
- RuoYi-Vue-Plus 仓库：根 `pom.xml`、各模块 `pom.xml`、`R.java`、`PageResult.java`、`GlobalExceptionHandler.java`、`InjectionMetaObjectHandler.java`（依赖 `LoginHelper`）、`MybatisPlusConfig.java`、`RedisConfig.java`、`application.yml`、`script/sql`、`ruoyi-admin/Dockerfile`；core / json / redis / mybatis / oss / mail / web 主代码 170 个类中 56 个 import `cn.hutool`
- nestjs-template（`release/exam-prep` @ `02fdb4d`，契约相对 `9ab33ab` 无变化）：`src/common/filters/all-exceptions.filter.ts`（`translateErrors`、`getTranslatedMessage`）、`src/common/utils/exceptions.util.ts`（`UNIQUE_CONSTRAINT_RESPONSES`）、`src/core/session/session.service.ts`（`wasRecentlyRotatedFrom`）、`src/common/validation-options.ts`、`src/core/auth/auth.controller.ts`（`RefreshResponseDto`、`AuthDeleteAccountDto`）、`src/modules/files/infrastructure/uploader/*/files.controller.ts`（`upload` 无 `@HttpCode`）、`test/platform/platform-foundation.e2e-spec.ts`（upload `expect(201)`）、`src/i18n/{en,zh}/common.json`（`unprocessableEntity`）、`src/config/app.config.ts`（`SWAGGER_ENABLED`）、`src/main.ts`、`nest-cli.json`（未启用 swagger 插件）、`@nestjs/swagger` 12.1.1 输出 `openapi: 3.0.0`、`env-example-relational`、`.github/workflows/check.yml`、`.github/renovate.json5`、`vitest.config` 覆盖率阈值；远程 `xingyunqi-tech/nestjs-template`（2026-11-11 查询为 PUBLIC）
- nestjs-template 补充核对（`02fdb4d`）：`src/common/pipes/parse-id.pipe.ts`（400 `invalidId`）、`src/core/permissions/permissions.guard.ts`（无权限声明 → 403）、`src/core/users/users.controller.ts`（`createUser` 有 `@ApiCreatedResponse` 与 `@HttpCode(200)`）、`src/core/users/domain/user.ts`（`email: string | null` + `@Expose({groups})`）、`src/config/app.config.ts`（`SWAGGER_ENABLED` 默认非生产开启）、`env-example-relational`（`THROTTLE_ENABLED=false`、无 `REDIS_KEY_PREFIX`）；`src/` 中无 `Content-Language` / `Vary` 设置（`Vary: Origin` 来自 CORS 中间件）；nestjs 仓库中暂无 class-validator 元数据导出脚本；`@nestjs/core` 12.4.2 `routes-resolver.js` 未匹配路由抛 ``NotFoundException(`Cannot ${method} ${url}`)``，url 取 `request.originalUrl`；Express 5.3.0 默认关闭 `case sensitive routing` 与 `strict routing`，`etag` 默认 `weak`
- nestjs-template 补充核对（`02fdb4d`，2026-11-11 第三轮）：`src/core/auth/auth.service.ts`（`createSessionAndTokens` 返回键顺序 `refreshToken, token, tokenExpires, user`、登录签发 `role: user.role`、`refreshToken()` 签发 `role: {id}`、`validateUserStatusForAuth(user, 'refresh')`、重放时 `sessionService.revoke` 后抛 401）、`src/core/auth/services/token.service.ts`（`tokenExpires = Date.now() + ms(expiresIn)`）、`src/core/auth/strategies/jwt.strategy.ts`（`Session expired`、`User not found`、`User is not active`）、`src/core/auth/helpers/auth-validation.helper.ts`（422 `userNotActive`）、`src/core/permissions/permissions.guard.ts`（`User not authenticated`、`User role not found`）、`src/core/session/session.service.ts`（`session:rotated:{id}` 只存一个 hash）、`src/common/request-context.ts`（`typeof incoming === 'string'` 且匹配正则才沿用）、`src/main.ts`（helmet 配置、非生产 `enableCors({origin: true, credentials: true})`、`useBodyParser('json', {limit})`、`setGlobalPrefix` 排除 `/`、`metrics`）、`src/infrastructure/database/seeds/relational/user/user-seed.service.ts`（`ENV_MODE === 'prod'`、`SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD`）、`env-example-relational`（`AUTH_*_EXPIRES_IN`）；`8b5f296`（本地未推送）：`scripts/export-validation.ts`。前文「nestjs 仓库中暂无 class-validator 元数据导出脚本」已不成立
- fastapi-temp 实现（`303bf57`）：`src/app/infrastructure/database/session.py`（`Database.transaction`：`HttpError` 提交、其它异常回滚；连接 `server_settings.timezone = UTC`）、`src/app/core/session/service.py`（`forget` 提交前后各清一次缓存）、`src/app/common/security.py`（`ms` 解析、`exp` 计算）、`src/app/common/middleware.py`（helmet 头集合与取值、CORS 默认方法与 `Vary`、原始路径匹配）、`src/app/core/users/repository.py`（排序追加主键）、`src/app/cli/seed.py`、`src/app/common/validation/`（ValidationPipe 复刻与 validator.js 13.15.35 移植）、`openapi/validation.json`（136 个 DTO、32 种约束）、`openapi/SOURCE`、`tests/contract/fixtures/validation-cases.json`（4516 条）、`scripts/{openapi-sync.sh,schema_snapshot.py,gen_validation_cases.py,nestjs/validation-cases.ts}`、`docker/nestjs.compose.yaml`、`tests/integration/{conftest.py,test_schema.py,test_auth.py}`、`tests/parity/*.py`；计划文本修订见 `git diff 76aa96a 303bf57 -- TEMPLATE_PLAN.md`
- **未核实**：Handlebars.java 版本、阿里云 Dysms 官方 SDK 版本（方案不依赖）、hadolint 版本、Trivy `fs` 对 Maven pom 传递依赖的解析完整度、Renovate `maven-wrapper` manager 对 `only-script` 分发的处理、Spring Boot 4.1 对 JDK 25 AOT cache 的集成方式、JobRunr schema 配置项名称、nestjs 可选鉴权守卫遇到无效令牌时的行为、nestjs GHCR 镜像的包可见性与是否包含 e2e 测试文件、`HttpEntityMethodProcessor` 对 `ResponseEntity<Void>` 是否调用 `ResponseBodyAdvice`（因此未选 `useResponseEntity=true` 方案）、Spring `PathPattern` 按解码后段匹配的具体位置（M1 按 7.0.10 源码核对）
