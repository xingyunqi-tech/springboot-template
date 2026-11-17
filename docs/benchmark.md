# 压测（k6）

登录、刷新、`auth/me`、用户列表四个接口在同一台机器、同样资源限制下对比 springboot-template 与 nestjs-template（`02fdb4d`），并复核存活探针的堆阈值 `HEALTH_MEMORY_HEAP_THRESHOLD_MB=500`。测试日期 2026-11-17。

## 环境

|    项     |                                                                                    值                                                                                    |
|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 机器       | MacBook Pro（MacBookPro18,2），Apple M1 Max，10 核，32 GB                                                                                                                     |
| Docker   | Docker Desktop，Engine 28.5.1，Compose 2.40.2，VM 分配 10 CPU、7.75 GiB 内存                                                                                                    |
| 压测工具     | `grafana/k6:2.3.0`，容器接入被测栈的 compose 网络，直连 `http://api:3000`                                                                                                             |
| api 容器限制 | 两边都是 `cpus: 2`、`mem_limit: 1g`（swap 上限随 Docker 默认，即另有 1 GiB swap）；Postgres、Redis 等不限                                                                                    |
| 机器负载     | 压测期间同机还有其它项目的 12 个容器（nestjs 参照服务、create-t3-turbo、website、mongo 等，基本空闲）和其它会话的构建任务；三遍开始 / 结束时 5 分钟 load average 分别为 35.8 / 31.7、31.5 / 24.9、13.5 / 24.6，宿主 CPU 空闲 11%–68% |

|                  |                                                                            springboot-template                                                                             |                                  nestjs-template                                  |
|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| 镜像               | `springboot-template:bench`（本仓库 `Dockerfile` 的 `production` target，`make docker-build` 后打标签）                                                                               | `fastapi-temp/nestjs-ref:02fdb4def2df`（nestjs `Dockerfile` 的 `production` target） |
| 运行时              | Temurin JRE 25.0.5，Spring Boot 4.1.2，Tomcat 11.0.27，MyBatis-Plus 3.5.18.1，HikariCP 7.0.2，pgjdbc 42.7.14，Lettuce 7.5.2                                                      | Node 24.22.2，NestJS 12.4.2（Express），TypeORM 1.1.2 + pg                            |
| 并发模型             | 单 JVM 进程，Tomcat 请求跑在虚拟线程上（`spring.threads.virtual.enabled=true`）；`-XX:+UseZGC -XX:MaxRAMPercentage=60`，1 GiB 限制下最大堆 616 MiB；密码哈希进程内最多 2 个并发（`PASSWORD_HASH_CONCURRENCY=2`） | 单个 Node 进程（未启用 cluster），一个事件循环；bcryptjs 在主线程上分段执行                                 |
| 密码哈希             | argon2id（`PASSWORD_HASHER=argon2id`，Spring Security `Argon2PasswordEncoder` + BouncyCastle 1.86）：`t=2`、`m=19456`（19 MiB）、`p=1`                                             | bcryptjs 3.0.3，10 轮                                                               |
| 数据库连接池           | `DATABASE_MAX_CONNECTIONS=80`（Hikari）                                                                                                                                      | `DATABASE_MAX_CONNECTIONS=100`                                                    |
| Postgres / Redis | `postgres:18.6-alpine`、`redis:8.10.3-alpine`（项目 `bench-springboot`）                                                                                                        | `postgres:17.11-alpine`、`redis:7.4.12-alpine`（项目 `bench-springboot-nestjs`）       |
| 模式               | `APP_ENV=production`（ci overlay）                                                                                                                                           | `NODE_ENV=production`                                                             |
| 日志               | info 级 JSON（logback），每个请求一条访问日志                                                                                                                                            | info 级 JSON（pino），每个请求一条访问日志                                                      |
| 限流               | `THROTTLE_ENABLED=false`                                                                                                                                                   | `THROTTLE_ENABLED=false`                                                          |
| 其他进程             | 同栈另有 worker、mailpit（不限资源）                                                                                                                                                  | 同栈另有 mailpit                                                                      |

springboot 栈由 `docker/docker-compose.{base,postgres,redis,ci}.yaml` 加一个 override 组成，override 只替换镜像、关闭限流、宿主端口改为 `127.0.0.1` 随机端口，并给 api 加资源限制；migrate 跑迁移，seed 写入演示账号。`HEALTH_MEMORY_HEAP_THRESHOLD_MB=500`、`METRICS_TOKEN` 沿用 ci overlay。nestjs 栈由 `scripts/nestjs/nestjs.compose.yaml` 加 override 起，env 来自 nestjs `02fdb4d` 的 `env-example-relational`，替换项为 `THROTTLE_ENABLED=false`、各 `*_SECRET` 补足 32 位（生产模式启动校验要求），override 设 `NODE_ENV=production`、`LOG_LEVEL=info`、`LOG_PRETTY_PRINT=false`。两边配置与 fastapi-temp、go-template 的 `docs/benchmark.md` 相同，k6 脚本 `scripts/bench/auth.js` 与它们逐字节相同。

## 方法

- 接口与账号：
  - `POST /api/v1/auth/email/login`：`john.doe@example.com`，密码字段为 `md5("Secret00")`。
  - `POST /api/v1/auth/refresh`：setup 阶段为每个 VU 登录一次拿到独立会话，每次请求带上一次返回的新 refreshToken。
  - `GET /api/v1/auth/me`：`john.doe@example.com` 的 access token。
  - `GET /api/v1/users?limit=10`：`admin@example.com` 的 access token。
- 每个接口单独跑一次 k6：`constant-vus`，总时长 40 s，前 10 s 为预热，只统计之后 30 s 内发出的请求（按 `phase` 标签过滤）；VU 无思考时间。
- VU 分 10、50、100 三档。按档位、接口顺序执行，每组内两个后端交替先后（第一组 springboot 先，第二组 nestjs 先，依此类推）。全部组合连续跑三遍，两个栈在三遍之间不重启。
- 指标：RPS = 统计窗口内完成请求数 / 30 s；p50、p95、p99 为 `http_req_duration`；错误率为 `http_req_failed`（非 2xx/3xx 或网络错误）。
- 资源：每次 k6 运行期间循环执行 `docker stats --no-stream` 取 api 容器 CPU、内存（每 2–3 s 一个点）；springboot 另每秒取一次 `/metrics` 中 `jvm_memory_used_bytes{area="heap"}` 各分区之和与 `/api/v1/health/live` 状态码（第三遍）。CPU 为 `docker stats` 的瞬时值，单点可超过 200%。

## 结果

第三遍（`scripts/bench/run.sh bench` 原样输出）：

|   接口    | VU  |     后端     |  请求数   |  RPS   | p50 (ms) | p95 (ms) | p99 (ms) | 错误率 |
|---------|-----|------------|--------|--------|----------|----------|----------|-----|
| login   | 10  | springboot | 1305   | 43.5   | 206.9    | 423.9    | 597      | 0%  |
| login   | 10  | nestjs     | 366    | 12.2   | 775.7    | 1102.6   | 1630.6   | 0%  |
| login   | 50  | springboot | 896    | 29.9   | 1460.1   | 2660.9   | 3986.8   | 0%  |
| login   | 50  | nestjs     | 201    | 6.7    | 5044.6   | 9230.3   | 10542.9  | 0%  |
| login   | 100 | springboot | 1476   | 49.2   | 2053.8   | 3793.2   | 4675.8   | 0%  |
| login   | 100 | nestjs     | 362    | 12.1   | 4555.5   | 11624.3  | 14711.5  | 0%  |
| refresh | 10  | springboot | 85669  | 2855.6 | 2.7      | 6.5      | 17.8     | 0%  |
| refresh | 10  | nestjs     | 17543  | 584.8  | 15.6     | 28.8     | 59.8     | 0%  |
| refresh | 50  | springboot | 90616  | 3020.5 | 11.9     | 44.7     | 83.6     | 0%  |
| refresh | 50  | nestjs     | 20640  | 688    | 72.6     | 143.9    | 253.1    | 0%  |
| refresh | 100 | springboot | 115112 | 3837.1 | 23       | 66       | 96.8     | 0%  |
| refresh | 100 | nestjs     | 26847  | 894.9  | 127.4    | 205.9    | 346.8    | 0%  |
| me      | 10  | springboot | 60078  | 2002.6 | 2.5      | 15.6     | 37.6     | 0%  |
| me      | 10  | nestjs     | 39687  | 1322.9 | 7.1      | 10.3     | 16.1     | 0%  |
| me      | 50  | springboot | 148973 | 4965.8 | 6.5      | 30.7     | 49.3     | 0%  |
| me      | 50  | nestjs     | 40193  | 1339.8 | 33.4     | 57.3     | 120.8    | 0%  |
| me      | 100 | springboot | 133708 | 4456.9 | 15.5     | 56.4     | 82.7     | 0%  |
| me      | 100 | nestjs     | 27749  | 925    | 83.1     | 274.1    | 485.1    | 0%  |
| users   | 10  | springboot | 88142  | 2938.1 | 1.8      | 10.7     | 32.3     | 0%  |
| users   | 10  | nestjs     | 34845  | 1161.5 | 8.2      | 11.7     | 17.1     | 0%  |
| users   | 50  | springboot | 87229  | 2907.6 | 9.2      | 50.3     | 102.7    | 0%  |
| users   | 50  | nestjs     | 37321  | 1244   | 36.8     | 56.3     | 110      | 0%  |
| users   | 100 | springboot | 100661 | 3355.4 | 20       | 70.2     | 131.9    | 0%  |
| users   | 100 | nestjs     | 30648  | 1021.6 | 85.1     | 174.3    | 282.4    | 0%  |

|   接口    | VU  |     后端     | CPU 峰值 | 内存峰值 (MiB) | 堆峰值 (MiB) | live 非 200 / 探测次数 |
|---------|-----|------------|--------|------------|-----------|-------------------|
| login   | 10  | springboot | 208%   | 933        | 414       | 0 / 30            |
| login   | 10  | nestjs     | 119%   | 203        | -         | -                 |
| login   | 50  | springboot | 252%   | 944        | 342       | 0 / 17            |
| login   | 50  | nestjs     | 125%   | 254        | -         | -                 |
| login   | 100 | springboot | 221%   | 921        | 394       | 0 / 15            |
| login   | 100 | nestjs     | 123%   | 270        | -         | -                 |
| refresh | 10  | springboot | 206%   | 936        | 514       | 3 / 40            |
| refresh | 10  | nestjs     | 127%   | 253        | -         | -                 |
| refresh | 50  | springboot | 245%   | 918        | 536       | 0 / 39            |
| refresh | 50  | nestjs     | 137%   | 261        | -         | -                 |
| refresh | 100 | springboot | 232%   | 897        | 472       | 1 / 42            |
| refresh | 100 | nestjs     | 124%   | 298        | -         | -                 |
| me      | 10  | springboot | 226%   | 923        | 514       | 0 / 35            |
| me      | 10  | nestjs     | 118%   | 251        | -         | -                 |
| me      | 50  | springboot | 208%   | 944        | 490       | 0 / 39            |
| me      | 50  | nestjs     | 131%   | 266        | -         | -                 |
| me      | 100 | springboot | 217%   | 898        | 408       | 0 / 38            |
| me      | 100 | nestjs     | 144%   | 311        | -         | -                 |
| users   | 10  | springboot | 243%   | 942        | 418       | 0 / 37            |
| users   | 10  | nestjs     | 118%   | 252        | -         | -                 |
| users   | 50  | springboot | 381%   | 930        | 432       | 0 / 37            |
| users   | 50  | nestjs     | 136%   | 269        | -         | -                 |
| users   | 100 | springboot | 228%   | 897        | 466       | 0 / 37            |
| users   | 100 | nestjs     | 144%   | 345        | -         | -                 |

三遍 RPS：

|   接口    | VU  |     后端     |  第一遍   |  第二遍   |  第三遍   |
|---------|-----|------------|--------|--------|--------|
| login   | 10  | springboot | 38.4   | 34.2   | 43.5   |
| login   | 10  | nestjs     | 12.9   | 12.9   | 12.2   |
| login   | 50  | springboot | 40     | 44.8   | 29.9   |
| login   | 50  | nestjs     | 13.4   | 13.5   | 6.7    |
| login   | 100 | springboot | 41.5   | 44.4   | 49.2   |
| login   | 100 | nestjs     | 12.4   | 13.4   | 12.1   |
| refresh | 10  | springboot | 2686.8 | 2290   | 2855.6 |
| refresh | 10  | nestjs     | 611.8  | 651.1  | 584.8  |
| refresh | 50  | springboot | 2869.2 | 2634.3 | 3020.5 |
| refresh | 50  | nestjs     | 716    | 884.8  | 688    |
| refresh | 100 | springboot | 3133.5 | 2794.9 | 3837.1 |
| refresh | 100 | nestjs     | 816.6  | 887    | 894.9  |
| me      | 10  | springboot | 4271.1 | 3548.7 | 2002.6 |
| me      | 10  | nestjs     | 1399.3 | 1112.2 | 1322.9 |
| me      | 50  | springboot | 3850.5 | 2719.8 | 4965.8 |
| me      | 50  | nestjs     | 1322   | 1343.2 | 1339.8 |
| me      | 100 | springboot | 3121.6 | 3346.9 | 4456.9 |
| me      | 100 | nestjs     | 992.5  | 1199.7 | 925    |
| users   | 10  | springboot | 3012.4 | 2531.8 | 2938.1 |
| users   | 10  | nestjs     | 1190.2 | 1043.3 | 1161.5 |
| users   | 50  | springboot | 2591.5 | 2137.5 | 2907.6 |
| users   | 50  | nestjs     | 1145   | 1072.2 | 1244   |
| users   | 100 | springboot | 2333.2 | 2806.6 | 3355.4 |
| users   | 100 | nestjs     | 1104.7 | 800.2  | 1021.6 |

- 三遍之间 springboot 非登录接口的 RPS 相差最多约 2.1 倍（`me` 10 VU：4271 / 3549 / 2003），nestjs 相差在 40% 以内（`login` 50 VU 第三遍 6.7 RPS 除外）。springboot 用满 2 CPU 配额，对宿主上其它负载更敏感；第二遍开始时宿主 CPU 空闲只有 11%，该遍 springboot 数值整体偏低。nestjs 单线程只用到 1.1–1.5 个 CPU，受影响较小。

### 错误与资源

- 三遍全部场景 k6 错误率为 0，状态码检查全部通过；两边 api 容器未 OOM、未重启；springboot api 日志中 error 级记录只有下面的存活探针 503。
- api 容器内存：springboot 峰值 897–944 MiB（上限 1 GiB），nestjs 203–356 MiB。springboot 的占用主要是 ZGC 堆（最大 616 MiB）加元空间、代码缓存、线程栈与 ZGC 自身开销。
- springboot GC 后存活数据约 88 MiB（`jvm_gc_live_data_size_bytes`）。

### 堆阈值复核（`HEALTH_MEMORY_HEAP_THRESHOLD_MB=500`）

压测镜像中存活探针 `/api/v1/health/live` 比较的是 `MemoryMXBean` 的已用堆，即包含尚未回收的垃圾。ZGC 下这个值呈锯齿：在两次回收之间持续上升，触发回收后回落到约 100 MiB。以下数据均按这种判定方式采集。

- 第三遍每秒采样：压测期间堆峰值 342–536 MiB，406 次探测中 4 次超过 500 MiB、返回 503（均在 refresh 场景）。
- 第二遍另以 0.2 s 间隔同时采 `/metrics` 与 `/live`（3092 次）：堆峰值 598 MiB；有压测流量的秒内 1195 次探测 10 次 503，springboot 空闲（只有采样请求、nestjs 在被压测）的秒内 1897 次探测 275 次 503，峰值 566 MiB。空闲时只有每秒约 5 次 `/metrics` 请求（空闲时按 1 s 间隔观察，每次 `/metrics` 使已用堆增加约 18 MiB），分配速率低，ZGC 推迟回收，堆一直涨到接近上限才回收；压测时分配速率高，ZGC 回收更频繁，反而少有越过 500 MiB 的时刻。
- 只以 0.2 s 间隔请求 `/live`（不请求 `/metrics`）连续 600 次，0 次 503。
- compose healthcheck（10 s 间隔、3 次重试）在三遍中出现过单次失败，未出现连续 3 次失败，容器始终 healthy。

结论：最大堆 616 MiB 时，500 MiB 阈值约为最大堆的 81%，而 ZGC 的已用堆在正常运行中就会到 550–600 MiB，存活探针会偶发 503；是否越线取决于分配速率与 ZGC 回收时机，不只在高负载时出现。按当前判定方式，500 MiB 在 1 GiB 容器里偏低，不能作为内存泄漏信号；连续失败才重启的探针配置（如 compose 的 3 次重试）下本次未导致重启。

据此，存活探针按最近一次 GC 结束时的整堆占用（`com.sun.management.GarbageCollectorMXBean.getLastGcInfo()` 中结束时间最晚、堆占用非 0 的一次，对全部堆内存池的 `memoryUsageAfterGc` 求和；young GC 后同样包含老年代；尚无 GC 记录时取当前已用量）与阈值比较：正常运行时约等于存活数据（约 88 MiB），不受上述锯齿影响，超过 500 MiB 才反映存活数据异常增长。

## 结论

- 登录：两边都受密码哈希 CPU 成本主导，算法不同（argon2id `t=2,m=19MiB,p=1` 对 bcrypt 10 轮）。springboot 29.9–49.2 RPS，nestjs 6.7–13.5 RPS；100 VU 时 p99 分别为 4.7–6.6 s、13.0–14.7 s。
- 刷新：springboot 2290–3837 RPS，nestjs 585–895 RPS。
- `auth/me`：springboot 2003–4966 RPS，nestjs 925–1399 RPS。
- 用户列表：springboot 2138–3355 RPS，nestjs 800–1244 RPS。
- 除登录外，springboot 吞吐约为 nestjs 的 1.5–4.9 倍，p50 约为 nestjs 的 1/3–1/6；10 VU 时 springboot 的 p99（18–38 ms）是 p50（1.4–2.8 ms）的 10 倍以上，nestjs 的 p99（11–60 ms）约为 p50（7–9 ms）的 2–4 倍；springboot 的 p99 在 10 VU 下与 nestjs 相当或更高。
- 内存：springboot api 容器用到 0.9 GiB 左右（1 GiB 上限的约 90%），nestjs 约 0.2–0.35 GiB。
- 存活探针堆阈值见上节：ZGC 下已用堆正常可达 550–600 MiB，按已用堆判定会偶发 503，因此探针按 GC 后的堆占用判定。

## 复现

前置：本地有 nestjs-template 仓库（默认 `../nestjs-template`，可用 `NESTJS_DIR` 指定，只读取 `openapi/SOURCE` 中提交的 `env-example-relational`）和 `fastapi-temp/nestjs-ref:02fdb4def2df` 镜像（`make openapi-sync` 会构建），并装有 `jq`、`curl`。

```bash
make docker-build && docker tag springboot-template:local springboot-template:bench
docker pull grafana/k6:2.3.0

export WORK_DIR="$(mktemp -d)"
scripts/bench/run.sh up
VU_LEVELS="10 50 100" scripts/bench/run.sh bench
scripts/bench/run.sh down
```

- `up` 生成两份 override 和 nestjs env 到 `WORK_DIR`，以项目名 `bench-springboot`、`bench-springboot-nestjs` 起栈，宿主端口均为 `127.0.0.1` 随机端口。
- `bench` 依次跑完所有组合，每次结果写入 `$WORK_DIR/results/<后端>-<接口>-<VU>.json`，资源采样写入同名 `.stats`（CPU、内存）与 `.jvm`（已用堆、live 状态码），最后输出上面两张 Markdown 表；`report` 可单独重新生成表格。
- `down` 以 `down --volumes --remove-orphans` 删除两个栈。
- 变量：`API_CPUS`（默认 2）、`API_MEM`（默认 1g）、`ENDPOINTS`、`SPRING_IMAGE`、`K6_IMAGE`、`OUT_DIR`。

