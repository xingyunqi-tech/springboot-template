# 测试

JUnit Jupiter 6 + AssertJ + Mockito；集成测试用 Spring Boot Test + Testcontainers。surefire 跑 `*Test`（单元），failsafe 跑 `*IT`（集成），两者都开启类级并行。Testcontainers 起不来时测试失败，不跳过。

## 分层

|    层     |                                    位置                                    |                    依赖                    |          运行          |
|----------|--------------------------------------------------------------------------|------------------------------------------|----------------------|
| 1 单元     | 各模块 `src/test/java`，类名 `*Test`                                           | 无                                        | `make test`          |
| 架构       | `template-app/src/test/.../architecture/ArchitectureTest.java`（ArchUnit） | 无                                        | 随 `make test`        |
| 2 集成     | `template-app/src/test/java`，类名 `*IT`                                    | Docker（Testcontainers）                   | `make it`            |
| 3 E2E    | `tests/e2e`（Maven 模块，`-Pe2e`）                                            | Docker Compose                           | `make e2e`           |
| 4 契约     | `@Tag("contract")` 的用例                                                   | Docker（`OpenApiContractIT` 起 Spring 上下文） | `make test-contract` |
| 5 parity | `tests/parity`（Maven 模块，`-Pparity`）                                      | Docker；nestjs-template 本地仓库              | `make test-parity`   |

`make check` = `spotless:check` + 编译（Error Prone / NullAway）+ Checkstyle + 单元测试 + ArchUnit，是 pre-push 钩子。宣称改动完成前，`make check`、`make it`、`make test-contract` 必须通过；涉及部署形态的改动再跑 `make e2e` 与 `make docker-build`。

## 单元测试

- 不起 Spring 上下文；`@ExtendWith(MockitoExtension.class)` + AssertJ；时间依赖注入 `Clock`
- service 依赖端口接口，用 Mockito 或手写 fake（如 `InMemoryConfigRepository`）
- `ClassValidatorEngine` 的 validator.js 移植函数、GCRA 计算、`Ms` 解析与 `exp` 计算、JWT 两种 `role` 形状、密码哈希识别与重哈希、错误翻译各有单测
- 邮件模板以 `tests/fixtures/nestjs_mail/*.json`（nestjs 渲染的 subject / text / html）为 golden 逐字节比对

## 集成测试

|                  夹具                   |                                                        说明                                                         |
|---------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `support/TestDatabase`                | 单例 `postgres:18.6-alpine` 容器，启动后在模板库 `template_seeded` 上执行 Flyway 迁移、JobRunr 建表与 seed；单例 `redis:8.10.3-alpine` 容器 |
| `support/TestcontainersConfiguration` | 每个 Spring 测试上下文 `CREATE DATABASE ctx_<n> TEMPLATE template_seeded` 克隆独立库，并指向共用的 Redis                             |
| `support/TestMinio`                   | MinIO 容器，覆盖 `s3` / `s3-presigned` 驱动                                                                              |
| `support/StubServer`                  | 本地 HTTP 桩，替代微信、支付宝、短信等外部接口                                                                                        |

- `@SpringBootTest(webEnvironment = MOCK)` + MockMvc；测试按真实提交运行（不加 `@Transactional`），同一类内的用例各自注册临时用户，不依赖彼此留下的数据
- 测试配置 `template-app/src/test/resources/config/application.yml`：限流关闭、local 文件写 `target/test-files`、固定测试密钥、Hikari 池 10、不运行 JobRunr background server；需要的测试类用 `@SpringBootTest(properties = ...)` 打开（如 `REDIS_ENABLED=true`、限流、background server）
- 依赖真实提交语义的场景（刷新 CAS 与重放吊销、4xx 提交 / SQL 错误回滚、提交前后清缓存、afterCommit 投递、`lastActiveAt` 节流）都在克隆库上直接测
- `SchemaSnapshotIT` 比对迁移结果与 `db/nestjs-schema.sql`

## 契约测试

`make test-contract` 运行全部模块中 `@Tag("contract")` 的用例：

|                     用例                     |                                                                                                                                                             断言                                                                                                                                                              |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ValidationCasesTest`（template-common-web） | `tests/contract/fixtures/validation-cases.json` 的 4516 条 nestjs ValidationPipe 用例逐条通过                                                                                                                                                                                                                                       |
| `CodegenInputTest`（tools/openapi-tools）    | codegen 输入叠加与 scope 裁剪                                                                                                                                                                                                                                                                                                      |
| `OpenApiContractIT`（template-app）          | `/docs-json` 与基线逐字节一致；`scope.yaml` 的 operation 都在基线中且都有 handler、scope 外没有；handler 名与 `nest_handlers.json` 一致；鉴权注解与基线 `security`、权限注解与 `scope.yaml` 的 `permissions` 一致；没有契约外端点；生成代码无 `@ResponseStatus`；请求 DTO 都在 `validation.json` 中；`openapi/SOURCE` 字段齐全；同级 fastapi-temp 存在时共享产物与其 `HEAD` 逐字节相同；`docs/contract.md` 与当前实现一致 |

`docs/contract.md` 由 `OpenApiContractIT` 生成，不要手改：实现变更后执行 `UPDATE_CONTRACT_DOC=1 make test-contract` 重新生成。

## E2E

```bash
make e2e                                   # = e2e-up → test-e2e → e2e-down
make e2e-up                                # 构建镜像，以 base + postgres + redis + ci 起 migrate → seed → api + worker + Mailpit
make test-e2e APP_URL=http://localhost:3000 MAILPIT_URL=http://localhost:8025
make e2e-down
```

服务栈为生产镜像、`APP_ENV=production`、限流开启（`TRUST_PROXY=1`）。用例经 `APP_URL` 黑盒访问：

|         用例         |                               覆盖                               |
|--------------------|----------------------------------------------------------------|
| `UserJourneyIT`    | 注册 → 读 Mailpit 邮件确认 → 登录 → 刷新 → 我的设备 → 下线其他设备 → 改密 → 上传文件 → 退出 |
| `AccountCleanupIT` | 注销账号后 worker 清理该用户文件                                           |
| `ProbesIT`         | 存活 / 就绪探针、`/api/health`、未匹配路由的 404 信封、`/docs-json` 与基线一致       |

## parity

同一组请求分别发往本服务与 nestjs 参照服务，比对状态码、契约内的响应头与响应体（忽略 id、时间、令牌等易变字段，只校验类型与格式）。用例与 fastapi-temp `tests/parity` 逐条对应，同一套用例对 nestjs 也必须通过。

- 两侧都 `THROTTLE_ENABLED=true`、`TRUST_PROXY=1`；每个请求带不同的 `X-Forwarded-For`；需要登录的接口用本次运行新建的用户
- 任一侧返回 429 即失败（专门的 429 用例除外）；限流头只要求两侧头集合一致、取值为整数且 `X-RateLimit-Limit` 相等
- 需要直接读写数据的用例经 `docker exec … psql` 访问两侧 Postgres，容器名默认 `springboot-template-infra-postgres-1` / `springboot-template-nestjs-postgres-1`（系统属性 `PARITY_JAVA_PG_CONTAINER` / `PARITY_NESTJS_PG_CONTAINER` 覆盖）

本地运行：

```bash
# 1. nestjs 参照服务：需要 ../nestjs-template（NESTJS_DIR）含 openapi/SOURCE 的提交
make openapi-sync         # 本机没有 fastapi-temp/nestjs-ref:<commit> 镜像时执行，会构建镜像并重新导出共享产物（应无 diff）
make nestjs-up            # compose 项目 springboot-template-nestjs，http://localhost:3021（NESTJS_PORT），限流开启

# 2. 本服务：.env 中 APP_NAME 与 AUTH_* 取 nestjs env-example-relational 的值，
#    并设 REDIS_ENABLED=true、THROTTLE_ENABLED=true、TRUST_PROXY=1
make infra
make migrate && make seed
make dev                  # 另开终端保持运行（:3000）

# 3. 比对
make test-parity          # PARITY_TARGETS 默认 java=http://localhost:3000,nestjs=http://localhost:3021
make nestjs-down
```

CI 的 parity job 另外在 3002 端口起一个关闭限流的本服务实例，用 nestjs builder 镜像以 vitest 运行 nestjs 的 `test/user/auth.e2e-spec.ts`（`APP_PORT=3002`）。

## 编写约定

- 测试名（方法名与 `@DisplayName`）只陈述行为，不写「回归 / 防止 / 修复」；Checkstyle 检查
- 新接口至少有集成测试覆盖成功路径与各类错误形状；涉及契约的接口补 parity 用例
- 需要真实提交语义的用例直接在克隆库上运行，不用外层事务包裹

## CI

`.github/workflows/check.yml` 的每个 job 调用同名 Makefile 目标，`make ci` 在本地依次运行除 parity 外的全部 job：

|      job      |           Make 目标           |                                     内容                                      |
|---------------|-----------------------------|-----------------------------------------------------------------------------|
| lint          | `make lint`、`make hadolint` | `spotless:check`、enforcer、Checkstyle、typos；hadolint                         |
| workflow-lint | `make workflow-lint`        | actionlint、zizmor（门槛 medium）                                                |
| type-check    | `make type-check`           | `-Pci test-compile`：javac + Error Prone + NullAway（main 与 test）             |
| test          | `make test-ci`              | `-Pci verify`：单元 + ArchUnit + 集成 + 契约，覆盖率与测试数量门禁；上传覆盖率报告                    |
| migrations    | `make migrations`           | 迁移 → validate → 快照比对 → clean → 再迁移（[database.md](database.md#迁移)）           |
| contract      | `make test-contract`        | 契约测试                                                                        |
| security      | `make vuln`、`make gitleaks` | `trivy fs`（HIGH / CRITICAL 失败）；gitleaks 只扫本次 push / PR 引入的提交                |
| build         | `make build`                | `-Pci package`（CycloneDX SBOM，上传为 artifact），jar 对空库执行 `APP_ROLE=migrate` 冒烟 |
| image-scan    | `make image-scan`           | 构建生产镜像 + Trivy（`--ignore-unfixed`）                                          |
| e2e           | `make e2e`                  | 依赖 lint、type-check、test、build、migrations、workflow-lint、security；失败上传日志      |
| parity        | 见上                          | 依赖 build、migrations；重跑 `make openapi-sync` 要求共享产物无 diff                     |

### -Pci 门禁

`coverage-report` 模块在 `verify` 阶段执行 JaCoCo `report-aggregate`（合并各模块单元与集成测试，排除生成的 `contract/api`、`contract/model` 与 `cli`），`-Pci` 下再用 exec-maven-plugin 运行 `java ci/CiGate.java`：

|           检查           |                      阈值                      |
|------------------------|----------------------------------------------|
| 聚合行覆盖率                 | ≥ 80%                                        |
| 聚合分支覆盖率                | ≥ 70%                                        |
| surefire 报告中实际执行的单元测试数 | ≥ `ci/test-floor.properties` 的 `unit`        |
| failsafe 报告中实际执行的集成测试数 | ≥ `ci/test-floor.properties` 的 `integration` |

数量下限防止整类测试被静默跳过或过滤；新增大量用例后同步上调 `ci/test-floor.properties`。
