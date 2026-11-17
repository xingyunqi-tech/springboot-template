# 进程角色与工具

## APP_ROLE

`template-app` 打包为单一可执行 jar（镜像中以 `org.springframework.boot.loader.launch.JarLauncher` 启动），不引入命令行框架：`cli/AppRole`（`EnvironmentPostProcessor`）在启动早期读取 `APP_ROLE`，覆盖对应的 Spring 属性；`cli/CliConfiguration` 中的 `ApplicationRunner` 在一次性角色完成后按执行结果设置退出码并结束进程（各命令的退出码见下文）。未知值启动失败。

|        `APP_ROLE`         |          Web          |                 JobRunr background server                  | 结束 |                                                                                  说明                                                                                  |
|---------------------------|-----------------------|------------------------------------------------------------|----|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `api`（默认）                 | 是（`APP_PORT`，默认 3000） | `JOBS_IN_PROCESS` 为真时运行（未设置时非生产为真、生产为假；生产显式为 `true` 时启动失败） | 常驻 | HTTP 服务；连接池 `DATABASE_MAX_CONNECTIONS`                                                                                                                               |
| `worker`                  | 否                     | 运行（含定时任务）                                                  | 常驻 | 连接池 `WORKER_DATABASE_MAX_CONNECTIONS`（默认 10）                                                                                                                         |
| `migrate`                 | 否                     | 否                                                          | 退出 | `spring.flyway.enabled=true` 执行 Flyway，再建 `jobrunr` schema 并执行 JobRunr 迁移；`DB_SCHEMA_OWNER=external` 时不执行 Flyway，只校验表结构（见 [database.md](database.md#与-nestjs-共用数据库)） |
| `seed`                    | 否                     | 否                                                          | 退出 | 先在事务外计算种子账号的密码哈希，再在一个事务内执行 `Seeder`，见 [database.md](database.md#seed)                                                                                                |
| `create-superuser`        | 否                     | 否                                                          | 退出 | 新建管理员，见下文                                                                                                                                                            |
| `permissions-clear-cache` | 否                     | 否                                                          | 退出 | 删除角色权限缓存，见下文                                                                                                                                                         |

本地运行（Makefile 加载 `.env`，命令行变量覆盖 `.env`）：

```bash
make dev                      # api
make migrate                  # APP_ROLE=migrate
make seed                     # APP_ROLE=seed
make dev APP_ROLE=worker      # 本地一般不需要：非生产 api 进程内已运行任务
```

打包后的 jar 与镜像同理：

```bash
./mvnw -pl template-app -am -DskipTests package
APP_ROLE=migrate java -jar template-app/target/template-app-*.jar     # 只读环境变量，不读 .env
docker compose --env-file .env --env-file .env.image -f docker/docker-compose.ecs.yaml run --rm migrate   # 部署目录；.env.image 由 CI/CD 写入
```

`api` 与 `migrate` 在 `DB_SCHEMA_OWNER=external` 时启动阶段校验表结构，缺列则启动失败。

配置有误时（如缺 `AUTH_*_SECRET`、生产密钥过短、`METRICS_TOKEN` 不足 16 个字符）进程启动失败并输出原因。收到 SIGTERM 后优雅停机：api 等待在途请求（`spring.lifecycle.timeout-per-shutdown-phase=30s`），worker 等待在途任务至多 20s。

## 管理命令

|             `APP_ROLE`             |                                                                                          说明                                                                                          |
|------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `create-superuser --email=<email>` | 新建一个状态为启用的管理员（role 1，姓名 `Super Admin`）；邮箱去空白、转小写后按登录接口相同的 `isEmail` 规则校验（需有顶级域名），密码取标准输入第一行（至少 6 个字符，按客户端做法先取 MD5 再按 `PASSWORD_HASHER` 哈希）。参数也可写作 `-email <email>`、`--email <email>` |
| `permissions-clear-cache`          | 删除角色权限缓存 `permissions:v2:role-map`（Redis 键 `cache::permissions:v2:role-map`），Redis 不可达时退出码 1；`REDIS_ENABLED=false` 时各进程在内存中缓存，最长 5 分钟后失效，命令只输出提示                                     |

退出码：0 成功；1 失败（邮箱已存在、Redis 不可达、配置有误等）；2 用法错误（缺少或多余参数、邮箱不合法、密码过短）。结果与错误写入日志。

```bash
printf '%s\n' "$PASSWORD" | APP_ROLE=create-superuser java -jar template-app/target/template-app-*.jar --email=root@example.com
printf '%s\n' "$PASSWORD" | docker compose --env-file .env --env-file .env.image -f docker/docker-compose.ecs.yaml run --rm -T -e APP_ROLE=create-superuser migrate --email=root@example.com
APP_ROLE=permissions-clear-cache java -jar template-app/target/template-app-*.jar
```

本地经 Maven 运行时参数用 `-Dspring-boot.run.arguments`：`printf '%s\n' "$PASSWORD" | APP_ROLE=create-superuser ./mvnw -pl template-app -am -DskipTests compile spring-boot:run -Dspring-boot.run.arguments=--email=root@example.com`（需先 `set -a; . ./.env; set +a`）。

## 后台任务

队列在 Postgres（JobRunr，`jobrunr` schema），与 `REDIS_ENABLED` 无关；任务在请求事务提交后（`AfterCommit.run`）入队。

|        队列         |                       任务                        | 尝试次数 |       退避        |                                          说明                                          |
|-------------------|-------------------------------------------------|------|-----------------|--------------------------------------------------------------------------------------|
| `mail`            | `send-mail`                                     | 3    | `1s × 2^(n-1)`  | 注册确认、确认新邮箱、找回密码；按入队时记录的请求语言渲染                                                        |
| `notification`    | `send-email`、`send-sms`、`send-wechat-subscribe` | 3    | `1s × 2^(n-1)`  | 每个进程并发 5、每秒最多 10 个（`JobLimiter`）                                                     |
| `account-cleanup` | `clean-account`                                 | 5    | `10s × 2^(n-1)` | 按 order 执行各模块 `AccountDataCleaner`；进程内串行并持 `lock:account-cleanup:user-<id>`，最后一次失败告警 |

- 重试由 `QueueJobFilter` 替代 JobRunr 默认的 `RetryFilter`；拿不到进程内锁的任务抛 `JobYieldException`，5 秒后重跑且不计入失败次数
- 每次结束（成功或最后一次失败）计 `queue_jobs_total{queue_name, status}`
- JobRunr 按轮询周期（5s）取到期任务，实际重试间隔与公式相差至多一个周期
- background server `worker-count` 为 5；JobRunr 看板关闭

定时任务由运行 background server 的进程在启动完成时登记（`infrastructure/scheduler/RecurringJobs`），JobRunr 保证集群内单实例执行，失败不重试：

|             任务 id              |            时间            |                              内容                              |
|--------------------------------|--------------------------|--------------------------------------------------------------|
| `cleanup-old-sessions`         | 每天 03:00 UTC             | 物理删除软删超过 7 天的会话                                              |
| `cleanup-read-notifications`   | 每天 04:00 UTC             | 软删除 `readAt` 早于 30 天的已读通知                                    |
| `account-cleanup-compensation` | 每小时第 25 分（Asia/Shanghai） | 已软删超过 30 分钟且没有 `account_cleanup` 记录的用户（按 id 升序，最多 200 个）重新入队 |

## 代码生成

生成代码不入库，每次构建重新生成：

|                             步骤                              |                                                         输入                                                          |                                  输出                                  |
|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `tools/openapi-tools` 的 `CodegenInput`                      | `openapi/nestjs-baseline.json`、`openapi/scope.yaml`（裁剪到本期 operation）、`openapi/codegen-overlay.yaml`（响应字段可空 / 非必填修正） | `template-contract/target/` 下的 codegen 输入                            |
| openapi-generator（`spring` 生成器，`template-contract/pom.xml`） | 上一步输出、`template-contract/src/main/templates/`（覆盖的 `api.mustache`）、`schemaMappings`                                  | `tech.xingyunqi.template.contract.api.*Api` 接口与 `contract.model` DTO |

新增接口的顺序：nestjs 已有该接口 → 把 operationId 加进 `openapi/scope.yaml` → 构建生成 `*Api` → controller 实现并加鉴权注解；需要权限的同时写 `scope.yaml` 的 `permissions`。契约测试会检查遗漏，见 [testing.md](testing.md#契约测试)。

## 脚本

|                                                       脚本                                                        |                                                          用途                                                          |
|-----------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `scripts/openapi-sync.sh`（`make openapi-sync`）                                                                  | 共享产物：在 nestjs-template 固定提交上构建并启动服务，导出基线、`validation.json`、`nest_handlers.json`、`SOURCE`、表结构快照与校验用例；需要 Docker、git、uv |
| `scripts/openapi-copy.sh`（`make openapi-copy`）                                                                  | 从同级 fastapi-temp 的已提交版本复制共享产物与脚本（`FASTAPI_DIR`、`FASTAPI_REF`，默认 `HEAD`）                                              |
| `scripts/schema_snapshot.py`、`scripts/gen_validation_cases.py`、`scripts/gen_nest_handlers.py`、`scripts/nestjs/` | 共享产物：`openapi-sync.sh` 调用的生成工具，与 fastapi-temp、go-template 逐字节相同，不手改                                                  |
| `scripts/nestjs-ref.sh`（`make nestjs-up` / `make nestjs-down`）                                                  | 启动 / 停止 parity 用的 nestjs 参照服务（compose 项目 `springboot-template-nestjs`，`NESTJS_PORT` 默认 3021，限流开启）                    |
| `scripts/migrations.sh`（`make migrations` / `make build`）                                                       | 迁移校验；`smoke` 模式只对空库执行一次 `APP_ROLE=migrate`                                                                           |
| `scripts/spotless-apply.sh`                                                                                     | pre-commit：对暂存文件执行 `spotless:apply`                                                                                  |
| `scripts/check-commit-msg.sh`                                                                                   | commit-msg：conventional commits，类型集合同 nestjs commitlint，标题 ≤72 字符                                                    |

`openapi-sync.sh` 变量：

|          变量           |                            默认                             |
|-----------------------|-----------------------------------------------------------|
| `NESTJS_DIR`          | `../nestjs-template`                                      |
| `NESTJS_REF`          | `openapi/SOURCE` 的 `commit`                               |
| `NESTJS_BRANCH`       | `release/exam-prep`                                       |
| `NESTJS_SCRIPT_REF`   | `openapi/SOURCE` 的 `validation_script`，否则 `NESTJS_BRANCH` |
| `NESTJS_PORT`         | `3001`（被占用时退出）                                            |
| `NESTJS_SYNC_PROJECT` | `nestjs-openapi-sync-<仓库目录名>`                             |

## 模块生成器

`tools/generator` 按 nestjs 文档中某个 tag 的全部接口生成模块骨架：

```bash
make module NAME=orders TAG=Orders
```

|   参数   |                               说明                               |
|--------|----------------------------------------------------------------|
| `NAME` | Java 包名：小写字母开头，只含小写字母与数字，不能是 Java 关键字                          |
| `TAG`  | `openapi/nestjs-baseline.json` 中的 tag，须至少有一个接口，且其 `*Api` 尚未被实现 |

执行分两步（同一条 make 命令完成）：

1. `scope`：把该 tag 尚未列出的 operationId 追加到 `openapi/scope.yaml` 的 `operations`（模块目录已存在或全部已列出时报错）
2. `scaffold`：重新生成 `*Api` 后，按 FreeMarker 模板（`tools/generator/src/main/resources/templates/module/`）写出下列文件，再对 `template-app` 执行 `spotless:apply`；目标文件已存在时报错、不覆盖

| 文件（`template-app/src/main/java/tech/xingyunqi/template/modules/<NAME>/`） |                      内容                       |
|--------------------------------------------------------------------------|-----------------------------------------------|
| `web/<Type>Controller.java`                                              | 实现 `<Tag>Api`，每个方法抛 501；鉴权注解按基线 `security` 预填 |
| `<Type>Service.java`                                                     | service 骨架                                    |
| `<Type>Repository.java`                                                  | 仓储端口                                          |
| `persistence/<Type>Mapper.java`、`persistence/<Type>RepositoryImpl.java`  | MyBatis Mapper 与端口实现                          |

命令结束时打印后续步骤：按 nestjs controller 在 `scope.yaml` 与 controller 上声明权限（`@PermissionsGuarded`、`@RequirePermissions`），实现 controller、service 与 Mapper 查询。

接口只能来自 nestjs 文档：契约测试要求每个 handler 都对应基线中的 operation，契约变更先在 nestjs 落地，再同步，见 [contract.md](contract.md)。
