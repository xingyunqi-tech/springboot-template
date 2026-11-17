# 数据库

PostgreSQL 18（镜像 `postgres:18.6-alpine`；nestjs 参照服务 `scripts/nestjs/nestjs.compose.yaml` 为 17.11）、PostgreSQL JDBC + HikariCP、MyBatis-Plus 3.5.18（`mybatis-plus-spring-boot4-starter`）、Flyway；队列 JobRunr 的表在同一个库的 `jobrunr` schema。

## 连接配置

|                                               变量                                                |                        默认                        |                             说明                             |
|-------------------------------------------------------------------------------------------------|--------------------------------------------------|------------------------------------------------------------|
| `DATABASE_HOST` / `DATABASE_PORT` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` / `DATABASE_NAME` | `localhost` / `5432` / `root` / `secret` / `api` | 拼成 `jdbc:postgresql://host:port/name`                      |
| `DATABASE_MAX_CONNECTIONS`                                                                      | `80`                                             | api 进程 Hikari `maximumPoolSize`；进程内 JobRunr 的存储读写共用该池      |
| `WORKER_DATABASE_MAX_CONNECTIONS`                                                               | `10`                                             | worker 进程（`APP_ROLE=worker`）的连接池上限                         |
| `DATABASE_CONNECTION_TIMEOUT_MS`                                                                | `10000`                                          | 从连接池获取连接的超时（Hikari `connectionTimeout`）；低于 250（含 0、负数）启动失败 |

- 每个连接以 `SET TIME ZONE 'UTC'` 初始化，`timestamp` 列一律按 UTC 读写
- 部署时须满足：所有 api 副本 + worker 副本 + migrate / seed ≤ Postgres `max_connections`（默认 100，另扣超级用户保留 3 个）；多副本时每个副本的 `DATABASE_MAX_CONNECTIONS` 设为合计值 / 副本数
- 虚拟线程下连接池就是数据库并发上限：同一请求持有连接时不得再申请第二个（不用 `REQUIRES_NEW`、不另开连接），否则并发达到池大小时请求互相等待直到超时

## 模型

- 表名、列名、约束名、索引与 nestjs 一致（camelCase 列名），`db/nestjs-schema.sql` 为对照快照；`user` 是保留字，camelCase 列也必须加引号，手写 SQL 一律写 `"user"`、`"firstName"`
- MyBatis-Plus 设置 `map-underscore-to-camel-case: false`；Mapper 为 `@Mapper` 接口，SQL 写在 `@Select` / `@Insert` / `@Update` 注解中（动态 SQL 用 `<script>`），结果映射到 Mapper 内的 `Row` record，再在 `XxxRepositoryImpl` 中转成 domain 对象
- 动态排序只取白名单映射的列名，值走占位参数
- 时间列为 `timestamp without time zone`，存 UTC；API 输出为 `toISOString` 格式（毫秒、`Z`）
- 软删除列 `deletedAt`；已删除用户的唯一字段由部分索引释放
- `updatedAt` 由 `template-common-mybatis` 自动填充

表：`user`、`role`、`status`、`session`、`permission`、`role_permission`、`file`、`audit_logs`、`config`、`region`、`notification`、`notification_setting`、`address`、`favorite`、`feedback`、`user_consent`、`account_cleanup`、`admin_alert`。

唯一约束冲突按约束名查 `template-common-core` 的 `UniqueConstraints`（复制自 nestjs `UNIQUE_CONSTRAINT_RESPONSES`）翻译成 422 / 409，在事务边界之外的 `GlobalExceptionHandler` 中完成。

## 事务

service 写方法用组合注解 `@WriteTransaction`（`template-common-mybatis`），ArchUnit 禁止直接使用 `@Transactional`。

|                               方法结果                               |                    事务                    |
|------------------------------------------------------------------|------------------------------------------|
| 成功                                                               | 提交                                       |
| `HttpStatusException`（`BusinessException`、401、403、404、422 等 4xx） | 提交已写入的内容后再抛出，丢弃 `AfterCommit.run` 登记的副作用 |
| 其它异常（含数据访问异常，如唯一约束冲突）                                            | 回滚                                       |

- 4xx 提交是有意的：刷新令牌重放时的会话吊销与 401 在同一请求内，按 Spring 默认规则回滚会让吊销失效
- 数据库语句失败后事务已处于失败状态：service 内不得捕获 `DataIntegrityViolationException` 等异常后改抛 `HttpStatusException`，交给 `GlobalExceptionHandler` 在事务外翻译
- 内层参与事务的方法同样标 `@WriteTransaction`，否则内层异常会把事务标成 rollback-only，外层提交时抛 `UnexpectedRollbackException`
- `AfterCommit.run(callback)`：提交成功后执行副作用（发邮件、投递任务），4xx 提交路径上丢弃；`AfterCommit.always(callback)`：只要提交就执行（清缓存）；无活动事务时立即执行
- `WriteTransactions.run(...)`：以同样语义执行一段代码，用于事务前要先做耗时准备（如密码哈希）的写操作，避免哈希期间占用连接
- 调用外部 HTTP（微信、支付宝、短信）不放在事务内

## 迁移

```bash
make migrate                   # 本地：Flyway 迁移 + JobRunr 建表（读取 .env）
make migrations                # 临时 Postgres 上 migrate → flyway validate → 结构快照比对 → flyway clean → migrate（需要 Docker 与 uv）
```

- 迁移文件在 `template-app/src/main/resources/db/migration/`，当前只有 `V1__init.sql`；已合并的迁移不修改，变更通过新的 `V<n>__<name>.sql` 完成
- api / worker 设 `spring.flyway.enabled=false`，只有 `APP_ROLE=migrate` 执行 Flyway，随后建 `jobrunr` schema 并执行 JobRunr 迁移；api / worker 不建表（`jobrunr.database.skip-create=true`）
- compose 中由一次性 `migrate` 服务执行，api 与 worker 依赖其成功完成，多副本不会并发迁移
- Flyway 社区版没有 undo，`make migrations`（CI `migrations` job，`scripts/migrations.sh`）用「迁移 → validate → 快照比对 → clean → 再迁移，两次快照相同」代替回滚校验；`clean` 只在 `ci/flyway/pom.xml` 中开启
- 结构快照用共享脚本 `scripts/schema_snapshot.py`（只比较上述 18 张表），与 `db/nestjs-schema.sql` 相比只允许缺少引用本期未实现表的两个外键（`FK_feedback_errata`、`FK_feedback_question`）；集成测试 `SchemaSnapshotIT` 做同样的比对

## 与 nestjs 共用数据库

表结构、列名、约束名与 nestjs 一致，两个后端理论上可指向同一个库，但两套迁移工具（TypeORM migrations、Flyway）不能同时管理同一个库：

|    部署方式     |           表结构归属            |                                    设置                                     |
|-------------|----------------------------|---------------------------------------------------------------------------|
| 独立部署（默认）    | Flyway（`APP_ROLE=migrate`） | `DB_SCHEMA_OWNER=flyway`、`PASSWORD_HASHER=argon2id`                       |
| 与 nestjs 共库 | nestjs（TypeORM 迁移）         | `DB_SCHEMA_OWNER=external`、`PASSWORD_HASHER=bcrypt`（nestjs 无法验证 argon2id） |

`DB_SCHEMA_OWNER`（默认 `flyway`，只接受 `flyway` / `external`）为 `external` 时：

- `APP_ROLE=migrate` 不执行 Flyway，校验当前 schema 包含 nestjs 表结构快照 `db/nestjs-schema.sql`（打包进 jar）中的每一列，缺列时列出缺少的 `表.列` 并以非 0 退出；`jobrunr` schema 照常建表与迁移
- `APP_ROLE=api` 启动阶段做同样的校验，缺列时启动失败；worker、seed 与管理命令不校验

共库时 Redis 键名与 nestjs 逐字相同（`jwt:session:`、`session:rotated:`、`sms:code:`、`lock:`、缓存键等），可互认会话缓存；限流键带 `:gcra` 后缀，与 nestjs 的固定窗口键分开计数；JobRunr 与 BullMQ 不互通，两边各自处理自己的任务。

## Seed

`APP_ROLE=seed`（`cli/Seeder`）在一个事务内写入，已存在的数据不覆盖，可重复执行：

|        数据         |                                                                      来源                                                                       |
|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 角色、状态、权限、角色权限、配置项 | `template-app/src/main/resources/seed/seed.json`                                                                                              |
| 行政区划              | `seed/region-data.json`（`region` 表为空时写入）                                                                                                      |
| 管理员               | 没有 admin 角色用户时创建：非生产为 `admin@example.com` / `Secret00`；生产（`APP_ENV=production`）取 `SEED_ADMIN_EMAIL`（去空白、转小写）/ `SEED_ADMIN_PASSWORD`，任一未设置则不创建 |
| 示例用户              | 非生产且没有 user 角色用户时创建 `john.doe@example.com` / `Secret00`                                                                                       |

密码以 MD5 后按 `PASSWORD_HASHER` 哈希存储。账号与 nestjs seed 一致，前端模板可直接登录。角色权限缓存（`permissions:v2:role-map`，5 分钟）不由 seed 清除，需要立即生效时执行 `APP_ROLE=permissions-clear-cache`（[cli.md](cli.md#管理命令)）。
