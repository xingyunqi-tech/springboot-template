# springboot-template

Spring Boot 后端模板。HTTP API 契约与 [nestjs-template](https://github.com/xingyunqi-tech/nestjs-template)（`release/exam-prep` 分支 `02fdb4d`，见 `openapi/SOURCE`）一致：vite-vue3-temp、uniapp-template 由 `/docs-json` 经 Orval 生成的前端可在 nestjs、fastapi-temp、go-template 与本服务之间无改动切换。改造方案见 [TEMPLATE_PLAN.md](TEMPLATE_PLAN.md)，开发约定见 [CLAUDE.md](CLAUDE.md)。

|      项      |                                                              选型                                                               |
|-------------|-------------------------------------------------------------------------------------------------------------------------------|
| 语言 / 构建     | Java 25（Temurin）、Maven Wrapper（多模块，`${revision}` + flatten）                                                                   |
| Web         | Spring Boot 4.1.2、Spring MVC + Tomcat、虚拟线程；spec-first：openapi-generator（`spring` 生成器）以 nestjs 导出的 OpenAPI 文档生成 `*Api` 接口与 DTO |
| JSON        | Jackson 3                                                                                                                     |
| 数据          | PostgreSQL 18、MyBatis-Plus 3.5.18、Flyway、HikariCP                                                                             |
| 缓存 / 锁 / 限流 | Redis 8、Spring Data Redis（Lettuce）；GCRA 限流，Redis 不可用时回落进程内                                                                    |
| 队列 / 定时任务   | JobRunr 9（Postgres 存储）                                                                                                        |
| 认证          | 自写 jsonwebtoken 兼容 JWT 双令牌 + 会话、Spring MVC 拦截器；`spring-security-crypto` 的 argon2id / bcrypt                                   |
| 可观测性        | Logback 结构化日志、Micrometer + Prometheus、OpenTelemetry、Sentry                                                                    |
| 质量          | Spotless（palantir-java-format）、Error Prone + NullAway、Checkstyle（注释与测试名）、ArchUnit、JUnit 6 + Testcontainers、JaCoCo、prek 钩子     |

## 功能

- 统一响应信封、错误翻译（i18n en / zh）、与 nestjs ValidationPipe 等价的校验（`openapi/validation.json` 驱动）、Request ID、CORS、安全头、限流，Express 路由语义
- 认证：邮箱、手机号密码、短信验证码、微信（小程序 / App / 手机号）、支付宝；JWT access + refresh 轮换；会话（设备）管理；RBAC
- 用户管理、文件上传（`local` / `s3` / `s3-presigned`）与私有文件
- 通用模块：config、regions、notifications、addresses、favorites、feedbacks、consents、app-profile
- 健康检查、Prometheus 指标、审计、运营告警、账号注销清理、邮件（中英模板）、定时任务
- `/docs-json` 逐字节输出 nestjs 完整 OpenAPI 基线（含本模板未实现的考试业务接口，请求返回 404 信封），`/docs` 为 Scalar 页面；`SWAGGER_ENABLED` 控制，未设置时生产关闭
- 模块骨架生成器：按 nestjs 文档中的 tag 生成 controller 桩、service、repository

## 快速开始

前置：JDK 25、Docker；可选 [uv](https://docs.astral.sh/uv/)（`make lint` 的 typos、`make migrations`、`make openapi-sync` 需要）与 [prek](https://github.com/j178/prek)。

```bash
make install                      # 安装 Git 钩子（prek）
cp .env.example .env              # 开发用配置，AUTH_*_SECRET 为占位值
make infra                        # Postgres 18 :5432、Redis 8 :6379、Mailpit（SMTP :1025（MAIL_PORT）、网页 :8025）
make migrate                      # Flyway 迁移 + JobRunr 建表后退出
make seed                         # 角色、状态、权限、行政区划、配置与初始账号，幂等
make dev                          # http://localhost:3000
```

- `.env.example` 的四个 `AUTH_*_SECRET` 是开发用占位值（非生产对长度不作要求）；生产要求 ≥32 字符且互不相同，见 [docs/deploy.md](docs/deploy.md)
- 非生产环境 api 进程内运行 JobRunr（`JOBS_IN_PROCESS` 未设置时非生产视为 `true`），邮件、通知、账号清理无需单独起 worker；生产由 `APP_ROLE=worker` 进程执行
- `.env.example` 中 `REDIS_ENABLED=true`、`SMS_MOCK_MODE=true`（验证码只写日志）、邮件发往 Mailpit
- 非生产环境 seed 账号：`admin@example.com` / `john.doe@example.com`，密码 `Secret00`（接口传 MD5）

|                                路由                                 |                     说明                      |
|-------------------------------------------------------------------|---------------------------------------------|
| `GET /`                                                           | 应用信息                                        |
| `GET /api/health`                                                 | 简单健康检查                                      |
| `GET /api/v1/health`、`/api/v1/health/live`、`/api/v1/health/ready` | terminus 形状的健康检查与探针                         |
| `/api/v1/auth/*`                                                  | 邮箱 / 手机 / 微信 / 支付宝登录注册、刷新令牌、个人资料、改密、注销、会话管理 |
| `/api/v1/users`                                                   | 用户管理（管理员）                                   |
| `/api/v1/files/*`                                                 | 上传、私有文件、local 驱动下载                          |
| `GET /docs-json`、`GET /docs`                                      | OpenAPI 文档（与 nestjs 逐字节相同）、Scalar 页面        |
| `GET /metrics`                                                    | Prometheus 指标（监控令牌）                         |

## 与前端模板的关系

- vite-vue3-temp、uniapp-template 用 Orval 从 `/docs-json` 生成请求代码；本服务的 `/docs-json` 就是入库的 `openapi/nestjs-baseline.json` 原文，前端重新生成不会有差异
- 默认端口 3000、全局前缀 `api`、成功信封 `{code: 200, message: "success", data}`、错误信封与 422 校验错误形状、令牌格式、seed 账号都与 nestjs 相同，前端改 `baseURL` 即可切换
- 接口变更先在 nestjs 落地，再经 `make openapi-sync` / `make openapi-copy` 同步，见 [docs/contract.md](docs/contract.md)

## Make 目标

|                       目标                        |                                   说明                                   |
|-------------------------------------------------|------------------------------------------------------------------------|
| `make install`                                  | 安装 Git 钩子（pre-commit、commit-msg、pre-push）                              |
| `make infra`                                    | 启动本地 Postgres、Redis、Mailpit                                            |
| `make dev`                                      | 本地启动（`APP_ROLE` 默认 `api`，可传 `migrate` / `seed` / `worker`；读取 `.env`）   |
| `make migrate` / `make seed`                    | `APP_ROLE=migrate` / `APP_ROLE=seed` 执行一次后退出（读取 `.env`）                |
| `make fmt`                                      | Spotless：Java、pom、Markdown、YAML 等                                      |
| `make lint`                                     | `spotless:check`、enforcer、Checkstyle、typos                             |
| `make type-check`                               | `-Pci test-compile`：javac + Error Prone + NullAway                     |
| `make check`                                    | `spotless:check` + 编译 + Checkstyle + 单元测试 + ArchUnit（pre-push 钩子）      |
| `make test`                                     | 单元测试（含 ArchUnit）                                                       |
| `make it`                                       | 单元 + 集成测试（Testcontainers，需要 Docker）+ JaCoCo 聚合                         |
| `make test-ci`                                  | `-Pci verify`：在 `make it` 基础上校验覆盖率阈值与测试数量下限                            |
| `make test-contract`                            | 契约测试（含 4516 条 nestjs 校验 oracle 用例）                                     |
| `make e2e`                                      | 构建镜像，compose 起完整服务栈后跑 E2E，结束清理（拆开为 `e2e-up` / `test-e2e` / `e2e-down`） |
| `make test-parity`                              | parity：同一组请求发往本服务与 nestjs 参照服务逐个比对（先 `make nestjs-up`）                 |
| `make migrations`                               | 临时 Postgres 上 migrate → validate → 结构快照比对 → clean → migrate            |
| `make module NAME=orders TAG=Orders`            | 按 nestjs 文档 tag 生成模块骨架并加入 `scope.yaml`                                 |
| `make openapi-sync` / `make openapi-copy`       | 在 nestjs 固定提交上重新导出共享产物 / 从同级 fastapi-temp 复制                           |
| `make nestjs-up` / `make nestjs-down`           | 启动 / 停止 nestjs 参照服务（默认端口 3021）                                         |
| `make vuln` / `make gitleaks` / `make security` | trivy fs / gitleaks / 两者                                               |
| `make hadolint` / `make workflow-lint`          | Dockerfile 检查 / actionlint + zizmor                                    |
| `make build`                                    | `-Pci package`（含 SBOM），jar 对空库执行 `APP_ROLE=migrate` 冒烟                 |
| `make docker-build` / `make image-scan`         | 构建生产镜像 `springboot-template:local` / Trivy 扫描                          |
| `make ci`                                       | 本地运行 CI 中除 parity 外的全部 job                                             |
| `make clean`                                    | `./mvnw clean`                                                         |

`make help` 列出全部目标与说明。CI 的每个 job 调用同名 Makefile 目标，见 [docs/testing.md](docs/testing.md)。

## 目录结构

```
pom.xml                      父 pom：插件管理、质量门禁、profiles（ci / e2e / parity）
template-common/             与业务无关的 starter：core、json、web、redis、mybatis、security、oss、mail、observability
template-contract/           openapi-generator 产物（*Api 接口 + DTO，生成在 target/，不入库）
template-views/              手写响应视图 record，经 schemaMappings 替换生成类
template-app/                应用：core（auth、users、permissions、session）、infrastructure、integrations、modules、cli
coverage-report/             JaCoCo 聚合与 -Pci 门禁
tools/openapi-tools/         codegen 输入叠加（scope、可空性、schemaMappings）
tools/generator/             模块骨架生成器（FreeMarker 模板）
tests/                       e2e、parity、contract/fixtures、fixtures/nestjs_mail
openapi/                     nestjs 基线、validation.json、nest_handlers.json、SOURCE、scope.yaml、codegen-overlay.yaml
db/nestjs-schema.sql         nestjs 迁移后的表结构快照
ci/                          CiGate.java、test-floor.properties、flyway/
docker/、deploy/、scripts/   compose 文件与 HealthProbe、manage.sh、脚本
```

详见 [docs/architecture.md](docs/architecture.md)。

## 测试分层

|    层     |                          位置                           |          命令          |
|----------|-------------------------------------------------------|----------------------|
| 1 单元     | 各模块 `src/test/java`，类名 `*Test`                        | `make test`          |
| 2 集成     | `template-app/src/test/java`，类名 `*IT`（Testcontainers） | `make it`            |
| 3 E2E    | `tests/e2e`（compose 起完整服务栈，黑盒 HTTP）                   | `make e2e`           |
| 4 契约     | `@Tag("contract")` 的用例                                | `make test-contract` |
| 5 parity | `tests/parity`（与 nestjs 逐请求比对）                        | `make test-parity`   |

详见 [docs/testing.md](docs/testing.md)。

## 镜像与 Compose

`Dockerfile` 多阶段：builder（JDK，Maven 构建 + 预编译 `docker/HealthProbe.java`）→ extract（Boot layered jar 分层）→ `production`（`eclipse-temurin:25.0.5_8-jre-noble`，非 root）；另有 `development` 目标（JDK 镜像）。同一镜像按 `APP_ROLE` 运行 api、worker、migrate、seed。`docker/` 下的 compose 文件按组合使用：`docker-compose.base.yaml`（Mailpit、migrate、seed、api、worker）叠加 `docker-compose.postgres.yaml`、`docker-compose.redis.yaml`，再叠加 `dev` / `test` / `ci` 之一；服务器部署用独立的 `docker-compose.ecs.yaml` 与 `deploy/manage.sh`；`infra.compose.yaml` 只启动本地依赖。详见 [docs/deploy.md](docs/deploy.md)。

## 文档

|                      文档                      |                       内容                        |
|----------------------------------------------|-------------------------------------------------|
| [docs/architecture.md](docs/architecture.md) | 模块划分、分层与 ArchUnit 规则、接口声明、请求链路、进程模型、基础设施、配置     |
| [docs/auth.md](docs/auth.md)                 | 接口与限流、鉴权注解、JWT 与会话、刷新轮换、密码、登录方式、短信验证码、RBAC      |
| [docs/database.md](docs/database.md)         | 连接配置、模型与 MyBatis-Plus、事务、迁移、共库、seed             |
| [docs/deploy.md](docs/deploy.md)             | 镜像、健康检查、compose、deploy/manage.sh、GitHub Actions |
| [docs/testing.md](docs/testing.md)           | 五层测试、CI 门禁、本地运行 parity                          |
| [docs/cli.md](docs/cli.md)                   | `APP_ROLE` 进程角色、后台任务、代码生成与模块生成器、脚本              |
| [docs/contract.md](docs/contract.md)         | 未实现接口、与 nestjs 的行为差异、schemaMappings（由契约测试生成）    |
| [docs/benchmark.md](docs/benchmark.md)       | 与 nestjs 的压测对照                                  |
| [CLAUDE.md](CLAUDE.md)                       | 开发约定（AI 助手同样遵守）                                 |

## 许可证

[MIT](LICENSE)；参考 RuoYi-Vue-Plus 的说明见 [NOTICE](NOTICE)。
