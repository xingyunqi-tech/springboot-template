# 部署

## 镜像

`Dockerfile` 多阶段：

|      阶段       |                 基础镜像                 |                                                                                                    内容                                                                                                    |
|---------------|--------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `builder`     | `eclipse-temurin:25.0.5_8-jdk-noble` | 先只拷各模块 `pom.xml` 与 `.mvn` 做 `dependency:go-offline`（依赖缓存层），再拷源码 `./mvnw -pl template-app -am -DskipTests package`；BuildKit cache mount `/root/.m2`；同阶段 `javac --release 25` 编译 `docker/HealthProbe.java` |
| `extract`     | `eclipse-temurin:25.0.5_8-jre-noble` | `java -Djarmode=tools -jar app.jar extract --layers --launcher`                                                                                                                                          |
| `development` | `eclipse-temurin:25.0.5_8-jdk-noble` | 与 production 相同的分层，JDK 镜像，供 dev compose 使用                                                                                                                                                               |
| `production`  | `eclipse-temurin:25.0.5_8-jre-noble` | 按 dependencies / spring-boot-loader / snapshot-dependencies / application 分层拷贝，`/app/probe` 下为 HealthProbe，uid 10001 运行，`EXPOSE 3000`                                                                    |

production 的启动参数：`-XX:MaxRAMPercentage=60 -XX:+UseZGC -Duser.timezone=UTC`，按 2 CPU / 1GB 容器取值（堆上限约 614MB）；`ENV HEALTH_MEMORY_HEAP_THRESHOLD_MB=500`（代码默认 300，与 nestjs 相同，ZGC 负载下常态即可超过 300）。`.dockerignore` 为白名单写法，不进入构建上下文的文件不影响缓存。

```bash
make docker-build      # docker build -t springboot-template:local .（IMAGE 可覆盖）
make image-scan        # 构建后 Trivy 扫描（HIGH / CRITICAL，仅有修复版本的）
make hadolint          # Dockerfile 检查
```

同一镜像按 `APP_ROLE` 运行不同进程，见 [cli.md](cli.md)：

|   进程    |                                    `APP_ROLE`                                    |
|---------|----------------------------------------------------------------------------------|
| api     | `api`（默认）                                                                        |
| worker  | `worker`（JobRunr 队列与定时任务，无 HTTP 端口）                                              |
| migrate | `migrate`（Flyway + JobRunr 建表后退出；`DB_SCHEMA_OWNER=external` 时只校验表结构并建 JobRunr 表） |
| seed    | `seed`（写入种子数据后退出）                                                                |

### 健康检查

镜像不写 Dockerfile `HEALTHCHECK`：worker / migrate / seed 与 api 共用镜像且没有 HTTP 端口，而在 compose 里关闭 worker 的 healthcheck 会让 `docker compose up --wait` 报错。健康检查只配在 compose 的 api 服务上：

|            位置             |                                                     检查                                                     |
|---------------------------|------------------------------------------------------------------------------------------------------------|
| compose `api.healthcheck` | `java -cp /app/probe HealthProbe "http://localhost:${APP_PORT:-3000}/api/v1/health/live"`（存活）              |
| `deploy/manage.sh`        | `docker compose exec -T api java -cp /app/probe HealthProbe http://localhost:3000/api/v1/health/ready`（就绪） |

`HealthProbe` 是一个只用 JDK `HttpClient` 的类，2xx 时退出码 0，否则 1。JRE 镜像不含 `jdk.compiler`，不能用单文件源码启动器，所以在 builder 阶段预编译；镜像不额外安装 curl。

## Compose

文件在 `docker/`，从仓库根以 `-f` 组合：

|    场景    |               组合               |             项目名             |         入口         |
|----------|--------------------------------|-----------------------------|--------------------|
| 本地依赖     | `infra.compose.yaml`           | `springboot-template-infra` | `make infra`       |
| 开发服务栈    | base + postgres + redis + dev  | `springboot-template-dev`   | 见下                 |
| 测试服务栈    | base + postgres + redis + test | `springboot-template-test`  | 见下                 |
| e2e / CI | base + postgres + redis + ci   | `springboot-template-e2e`   | `make e2e`         |
| 服务器      | `docker-compose.ecs.yaml`      | `springboot-template`       | `deploy/manage.sh` |

|               文件               |                                                                                                         服务 / 作用                                                                                                         |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `docker-compose.base.yaml`     | `mailpit`（网页端口 `MAIL_CLIENT_PORT`，默认 8025）、`migrate`、`seed`（依赖 migrate 成功）、`api`（从源码构建 `springboot-template:local`，宿主端口 `APP_PORT`，默认 3000，带 healthcheck）、`worker`；配置取 `.env.example`，另设开发用 JWT 密钥与 `MAIL_HOST=mailpit` |
| `docker-compose.postgres.yaml` | `postgres`（宿主端口 `DATABASE_PORT`，默认 5432），为各服务设置 `DATABASE_*`                                                                                                                                                            |
| `docker-compose.redis.yaml`    | `redis`（宿主端口 `REDIS_PORT`，默认 6379），为各服务设置 `REDIS_ENABLED=true`；`redisinsight` 在 profile `tools`                                                                                                                         |
| `docker-compose.dev.yaml`      | `development` 目标镜像 `springboot-template:dev`、`APP_ENV=development`；`develop.watch` 把宿主编译出的 `template-app/target/classes` 同步进 api 与 worker 后重启，其它模块、`openapi/`、pom 变更时重新构建                                               |
| `docker-compose.test.yaml`     | `APP_ENV=test`；postgres / redis 不映射宿主端口                                                                                                                                                                                 |
| `docker-compose.ci.yaml`       | 生产镜像以 `APP_ENV=production` 运行，CI 用密钥、`METRICS_TOKEN`、`THROTTLE_ENABLED=true`、`TRUST_PROXY=1`；`seed` 以 `APP_ENV=test` 写入演示账号；api 与 Mailpit 只绑定 `127.0.0.1`                                                               |
| `docker-compose.ecs.yaml`      | 服务器：migrate、seed（profile `seed`）、api、worker、postgres、redis；镜像 `IMAGE_FULL_NAME`，配置取部署目录 `.env`                                                                                                                          |

开发服务栈：

```bash
docker compose -f docker/docker-compose.base.yaml -f docker/docker-compose.postgres.yaml \
  -f docker/docker-compose.redis.yaml -f docker/docker-compose.dev.yaml up --build --watch
./mvnw -pl template-app -am compile          # 另一终端，改代码后执行
```

本机已有服务占用默认端口时，通过 `APP_PORT`、`DATABASE_PORT`、`REDIS_PORT`、`MAIL_CLIENT_PORT` 改宿主端口；`make infra` 的 Mailpit SMTP 宿主端口取 `MAIL_PORT`（默认 1025）。也可以只用 `make infra` 起依赖，再 `make dev` 在本机运行。

测试服务栈，宿主机对其运行 E2E：

```bash
docker compose -f docker/docker-compose.base.yaml -f docker/docker-compose.postgres.yaml \
  -f docker/docker-compose.redis.yaml -f docker/docker-compose.test.yaml up --build --wait
make test-e2e APP_URL=http://localhost:3000 MAILPIT_URL=http://localhost:8025
```

## 服务器部署

### 目录布局

```
<部署目录>/
├── .env                          # 参考 docker/.env.ecs.example
├── .env.image                    # 可选：IMAGE_FULL_NAME=<镜像>，由 CI/CD 写入
├── deploy/manage.sh
└── docker/docker-compose.ecs.yaml
```

compose 文件写死项目名 `springboot-template`，所有调用都是 `docker compose --env-file .env [--env-file .env.image] -f docker/docker-compose.ecs.yaml …`：部署目录的 `.env` 同时用于变量替换（`APP_PORT`、`DATABASE_*`、`IMAGE_FULL_NAME`）与容器环境变量（`env_file: ../.env`）。不带 `--env-file` 时项目名与变量替换都会取错目录。

### 首次准备

1. 服务器安装 Docker 与 Compose 插件
2. 创建部署目录，`cp docker/.env.ecs.example .env` 后填写；首次部署设 `RUN_SEEDS=true`，写入后改回 `false`
3. 运行一次 deploy workflow：推送镜像、同步 `deploy/manage.sh` 与 `docker/docker-compose.ecs.yaml`、写入 `.env.image`（`IMAGE_FULL_NAME`）并启动；之后同样由 CI/CD 同步并重启。手动启动（`./deploy/manage.sh start`）前须已有 `.env.image`，或在 `.env` 中设 `IMAGE_FULL_NAME`，并已 `docker login` 到镜像仓库

`docker-compose.ecs.yaml` 固定以下变量，`.env` 中的值不生效：`APP_ENV=production`、容器内 `APP_PORT=3000`（宿主端口取 `.env` 的 `APP_PORT`）、`DATABASE_HOST=postgres`、`DATABASE_PORT=5432`、`REDIS_ENABLED=true`、`REDIS_HOST=redis`、`REDIS_PORT=6379`、`HEALTH_MEMORY_HEAP_THRESHOLD_MB=500`。

生产 `.env` 检查项（不满足时进程启动失败）：

|                                            变量                                            |                                                           要求                                                           |
|------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `AUTH_JWT_SECRET`、`AUTH_REFRESH_SECRET`、`AUTH_FORGOT_SECRET`、`AUTH_CONFIRM_EMAIL_SECRET` | ≥32 字符且互不相同（可用 `openssl rand -hex 32` 分别生成）                                                                            |
| `MAIL_HOST`、`MAIL_DEFAULT_EMAIL`、`MAIL_DEFAULT_NAME`                                     | 必填                                                                                                                     |
| `METRICS_TOKEN`                                                                          | 设置时 ≥16 字符；未设置时生产拒绝访问 `/metrics` 与完整 `/api/v1/health`                                                                  |
| `JOBS_IN_PROCESS`                                                                        | 不得为 `true`，任务由 worker 容器执行                                                                                             |
| `FILE_DRIVER` 及 S3 变量                                                                    | 非 `local` 时 `ACCESS_KEY_ID`、`SECRET_ACCESS_KEY`、`AWS_DEFAULT_S3_BUCKET`、`AWS_S3_REGION` 必填                             |
| `FRONTEND_DOMAIN`                                                                        | CORS 白名单与邮件链接域名，逗号分隔                                                                                                   |
| `SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD`                                               | `RUN_SEEDS=true` 时两项都设置才创建管理员                                                                                          |
| `TRUST_PROXY`                                                                            | 反向代理层数；API 端口直接对公网开放时保持 0                                                                                              |
| `DB_SCHEMA_OWNER`                                                                        | 默认 `flyway`；与 nestjs 共库时设 `external`，migrate 不执行 Flyway、只校验表结构，api 启动时同样校验，见 [database.md](database.md#与-nestjs-共用数据库) |
| `DATABASE_MAX_CONNECTIONS` / `WORKER_DATABASE_MAX_CONNECTIONS`                           | 两者之和小于 Postgres `max_connections`                                                                                      |

数据卷：`postgres-data`、`redis-data`、`uploaded-files`（挂载到 api 与 worker 的 `/app/files`，local 驱动的文件）。

### deploy/manage.sh

|       命令        |                                                                                                                   作用                                                                                                                   |
|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `start`         | `compose run --rm migrate`；`.env` 中 `RUN_SEEDS=true` 时再 `compose run --rm seed`；然后 `up -d --remove-orphans`，用 `docker compose exec -T api java -cp /app/probe HealthProbe …/health/ready` 轮询就绪（30 次 × 5s），失败时输出 api / worker 最近日志并退出 1 |
| `stop`          | `compose stop`                                                                                                                                                                                                                         |
| `restart`       | `compose restart`，3 秒后检查 `springboot-api` 在运行                                                                                                                                                                                          |
| `remove` / `rm` | `compose --profile seed down -v --remove-orphans`（删除数据卷）                                                                                                                                                                               |
| `status` / `ps` | `compose ps`                                                                                                                                                                                                                           |
| `logs`          | `compose logs -f`                                                                                                                                                                                                                      |
| `health`        | `springboot-api` 运行且就绪探针成功时退出 0                                                                                                                                                                                                        |
| `cleanup <镜像>`  | 按创建时间保留该镜像最新 3 个                                                                                                                                                                                                                       |
| `help`          | 帮助                                                                                                                                                                                                                                     |

容器名：`springboot-api`、`springboot-worker`、`springboot-migrate`、`springboot-seed`、`springboot-postgres`、`springboot-redis`。

## GitHub Actions

|        workflow        |                       触发                        |                                                            内容                                                             |
|------------------------|-------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `check.yml`            | push / PR 到 main、master、`release/**`，手动         | lint、workflow-lint、type-check、test、migrations、contract、security、build、image-scan、e2e、parity，见 [testing.md](testing.md#ci) |
| `deploy.yml`           | 手动（输入 `deploy`、`target`：aliyun / tencent / all） | 见下                                                                                                                        |
| `cleanup-registry.yml` | 每周一 03:00（北京时间）、手动（`dry_run`）、被 deploy 调用       | GHCR 保留 latest 与最近 2 个带 tag 的镜像并删除无 tag 镜像；TCR 保留 2 个                                                                     |
| `drift.yml`            | 每周一、手动                                          | 对 nestjs-template release 分支 HEAD 重跑 `make openapi-sync`，与入库共享产物不一致时失败并在 summary 中列出                                      |

`deploy.yml`：

1. `build-and-push`：buildx 构建 `production` 目标的 `linux/amd64`、`linux/arm64` 镜像，带 SBOM 与 provenance（`mode=max`），推送 GHCR（`deploy=true` 时同时推腾讯云 TCR 个人版）；标签为 git hash、分支名、semver，master 上另打 `latest`
2. `deploy`（`deploy=true`，按 `target` 矩阵，environment `aliyun-production` / `tencent-production`）：scp 同步 `docker/docker-compose.ecs.yaml` 与 `deploy/manage.sh` → SSH 登录 TCR、拉镜像（3 次重试）→ 写 `.env.image` → `compose run --rm migrate` → `up -d --remove-orphans` → `deploy/manage.sh health` 轮询 30 次 → 删除该仓库最新 3 个之外的旧镜像
3. `cleanup`：调用 `cleanup-registry.yml`；`notify` 输出部署摘要

仓库需要配置：

|     类型      |                                                              名称                                                              |                 用途                  |
|-------------|------------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| Secret      | `<PREFIX>_HOST`、`<PREFIX>_PORT`（默认 22）、`<PREFIX>_USER`、`<PREFIX>_SSH_KEY`、`<PREFIX>_PATH`（`<PREFIX>` 为 `ALIYUN` 或 `TENCENT`） | SSH 部署目标与部署目录                       |
| Secret      | `TENCENT_TCR_PASSWORD`                                                                                                       | 推送与拉取 TCR 镜像                        |
| Variable    | `TENCENT_TCR_REGISTRY`、`TENCENT_TCR_NAMESPACE`、`TENCENT_TCR_USERNAME`                                                        | TCR 仓库 `<命名空间>/springboot-template` |
| Secret      | `TENCENTCLOUD_SECRET_ID`、`TENCENTCLOUD_SECRET_KEY`；Variable `TENCENT_TCR_REGION`（默认 ap-guangzhou）                            | 清理 TCR 旧镜像，未配置时跳过                   |
| Environment | `aliyun-production`、`tencent-production`                                                                                     | 部署 job 的环境                          |

所有 action 按 commit SHA 固定并注释版本号，由 Renovate（`.github/renovate.json5`）更新；checkout 一律 `persist-credentials: false`；`make workflow-lint` 运行 actionlint 与 zizmor（`.github/zizmor.yml`，门槛 medium）。
