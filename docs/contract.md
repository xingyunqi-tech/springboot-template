# 与 nestjs-template 的契约差异

本文件由 `template-app` 的 `OpenApiContractIT` 生成（`UPDATE_CONTRACT_DOC=1 make test-contract`），与实际不符时测试失败；「与 nestjs 的行为差异」一节为手写，生成时原样保留。

## 基线中未实现的接口（148 个）

请求这些接口返回 404 信封 `Cannot <METHOD> <path>`。

| 方法 | 路径 | operationId | tag |
|---|---|---|---|
| GET | `/api/v1/practice/sessions` | getPracticeSessions | Practice |
| POST | `/api/v1/practice/sessions` | createPracticeSession | Practice |
| GET | `/api/v1/practice/sessions/ongoing` | getOngoingPracticeSessions | Practice |
| GET | `/api/v1/practice/sessions/{id}` | getPracticeSession | Practice |
| GET | `/api/v1/practice/sessions/{id}/questions` | getPracticeSessionQuestions | Practice |
| PUT | `/api/v1/practice/sessions/{id}/answers` | savePracticeAnswers | Practice |
| POST | `/api/v1/practice/sessions/{id}/self-assessment` | selfAssessPracticeAnswer | Practice |
| POST | `/api/v1/practice/sessions/{id}/progress` | updatePracticeProgress | Practice |
| GET | `/api/v1/practice/sessions/{id}/report` | getPracticeSessionReport | Practice |
| POST | `/api/v1/practice/sessions/{id}/submit` | submitPracticeSession | Practice |
| POST | `/api/v1/practice/sessions/{id}/abandon` | abandonPracticeSession | Practice |
| GET | `/api/v1/questions/{id}` | getQuestion | Questions |
| GET | `/api/v1/exams` | getExams | Exams |
| GET | `/api/v1/exams/{id}/subjects` | getExamSubjects | Exams |
| GET | `/api/v1/me/exam-profiles` | getMyExamProfiles | Exam Profiles |
| PUT | `/api/v1/me/exam-profiles/{examId}` | upsertMyExamProfile | Exam Profiles |
| DELETE | `/api/v1/me/exam-profiles/{examId}` | deleteMyExamProfile | Exam Profiles |
| POST | `/api/v1/me/exam-profiles/{examId}/current` | setMyCurrentExam | Exam Profiles |
| GET | `/api/v1/admin/exams` | adminGetExams | Admin Exams |
| POST | `/api/v1/admin/exams` | adminCreateExam | Admin Exams |
| GET | `/api/v1/admin/exams/{id}` | adminGetExam | Admin Exams |
| PATCH | `/api/v1/admin/exams/{id}` | adminUpdateExam | Admin Exams |
| DELETE | `/api/v1/admin/exams/{id}` | adminDeleteExam | Admin Exams |
| POST | `/api/v1/admin/exams/{id}/publish` | adminPublishExam | Admin Exams |
| POST | `/api/v1/admin/exams/{id}/offline` | adminOfflineExam | Admin Exams |
| GET | `/api/v1/admin/subjects` | adminGetSubjects | Admin Subjects |
| POST | `/api/v1/admin/subjects` | adminCreateSubject | Admin Subjects |
| GET | `/api/v1/admin/subjects/{id}` | adminGetSubject | Admin Subjects |
| PATCH | `/api/v1/admin/subjects/{id}` | adminUpdateSubject | Admin Subjects |
| DELETE | `/api/v1/admin/subjects/{id}` | adminDeleteSubject | Admin Subjects |
| POST | `/api/v1/admin/subjects/{id}/publish` | adminPublishSubject | Admin Subjects |
| POST | `/api/v1/admin/subjects/{id}/offline` | adminOfflineSubject | Admin Subjects |
| GET | `/api/v1/admin/subjects/{id}/knowledge-points` | adminGetSubjectKnowledgePoints | Admin Subjects |
| POST | `/api/v1/admin/knowledge-points` | adminCreateKnowledgePoint | Admin Knowledge Points |
| PATCH | `/api/v1/admin/knowledge-points/{id}` | adminUpdateKnowledgePoint | Admin Knowledge Points |
| DELETE | `/api/v1/admin/knowledge-points/{id}` | adminDeleteKnowledgePoint | Admin Knowledge Points |
| POST | `/api/v1/admin/knowledge-points/{id}/move` | adminMoveKnowledgePoint | Admin Knowledge Points |
| POST | `/api/v1/admin/knowledge-points/{id}/migrate-questions` | adminMigrateKnowledgePointQuestions | Admin Knowledge Points |
| GET | `/api/v1/admin/questions` | adminGetQuestions | Admin Questions |
| POST | `/api/v1/admin/questions` | adminCreateQuestion | Admin Questions |
| POST | `/api/v1/admin/questions/batch-publish` | adminBatchPublishQuestions | Admin Questions |
| POST | `/api/v1/admin/questions/batch-submit` | adminBatchSubmitQuestions | Admin Questions |
| POST | `/api/v1/admin/questions/batch-reject` | adminBatchRejectQuestions | Admin Questions |
| POST | `/api/v1/admin/questions/batch-offline` | adminBatchOfflineQuestions | Admin Questions |
| POST | `/api/v1/admin/questions/batch-delete` | adminBatchDeleteQuestions | Admin Questions |
| PATCH | `/api/v1/admin/questions/batch-update` | adminBatchUpdateQuestions | Admin Questions |
| GET | `/api/v1/admin/questions/{id}` | adminGetQuestion | Admin Questions |
| PATCH | `/api/v1/admin/questions/{id}` | adminUpdateQuestion | Admin Questions |
| DELETE | `/api/v1/admin/questions/{id}` | adminDeleteQuestion | Admin Questions |
| GET | `/api/v1/admin/questions/{id}/versions` | adminGetQuestionVersions | Admin Questions |
| POST | `/api/v1/admin/questions/{id}/submit` | adminSubmitQuestion | Admin Questions |
| POST | `/api/v1/admin/questions/{id}/publish` | adminPublishQuestion | Admin Questions |
| POST | `/api/v1/admin/questions/{id}/reject` | adminRejectQuestion | Admin Questions |
| POST | `/api/v1/admin/questions/{id}/offline` | adminOfflineQuestion | Admin Questions |
| GET | `/api/v1/subjects/{id}/papers` | getSubjectPapers | Papers |
| GET | `/api/v1/papers/{id}` | getPaper | Papers |
| GET | `/api/v1/subjects/{id}/paper-rules` | getSubjectPaperRules | Papers |
| POST | `/api/v1/paper-rules/{id}/generate` | generatePaper | Papers |
| GET | `/api/v1/subjects/{id}/daily` | getSubjectDaily | Papers |
| GET | `/api/v1/subjects/{id}/daily/trial` | getSubjectDailyTrial | Papers |
| POST | `/api/v1/subjects/{id}/daily/trial/check` | checkSubjectDailyTrial | Papers |
| GET | `/api/v1/admin/papers` | adminGetPapers | Admin Papers |
| POST | `/api/v1/admin/papers` | adminCreatePaper | Admin Papers |
| GET | `/api/v1/admin/papers/{id}` | adminGetPaper | Admin Papers |
| PATCH | `/api/v1/admin/papers/{id}` | adminUpdatePaper | Admin Papers |
| DELETE | `/api/v1/admin/papers/{id}` | adminDeletePaper | Admin Papers |
| PUT | `/api/v1/admin/papers/{id}/questions` | adminSetPaperQuestions | Admin Papers |
| POST | `/api/v1/admin/papers/{id}/publish` | adminPublishPaper | Admin Papers |
| POST | `/api/v1/admin/papers/{id}/offline` | adminOfflinePaper | Admin Papers |
| POST | `/api/v1/admin/papers/{id}/copy` | adminCopyPaper | Admin Papers |
| GET | `/api/v1/admin/paper-rules` | adminGetPaperRules | Admin Paper Rules |
| POST | `/api/v1/admin/paper-rules` | adminCreatePaperRule | Admin Paper Rules |
| GET | `/api/v1/admin/paper-rules/{id}` | adminGetPaperRule | Admin Paper Rules |
| PATCH | `/api/v1/admin/paper-rules/{id}` | adminUpdatePaperRule | Admin Paper Rules |
| DELETE | `/api/v1/admin/paper-rules/{id}` | adminDeletePaperRule | Admin Paper Rules |
| POST | `/api/v1/admin/paper-rules/{id}/preview` | adminPreviewPaperRule | Admin Paper Rules |
| GET | `/api/v1/admin/subjects/{id}/daily-sets` | adminGetDailyQuestionSets | Admin Daily Question Sets |
| POST | `/api/v1/admin/subjects/{id}/daily-sets/{date}/regenerate` | adminRegenerateDailyQuestionSet | Admin Daily Question Sets |
| GET | `/api/v1/orders` | getOrders | Orders |
| POST | `/api/v1/orders` | createOrder | Orders |
| GET | `/api/v1/orders/{id}` | getOrder | Orders |
| DELETE | `/api/v1/orders/{id}` | cancelOrder | Orders |
| POST | `/api/v1/wechat/payment/jsapi` | WechatPayController_createJsapiPayment_v1 | Wechat Pay |
| POST | `/api/v1/wechat/payment/app` | WechatPayController_createAppPayment_v1 | Wechat Pay |
| GET | `/api/v1/wechat/order/{outTradeNo}` | WechatPayController_queryOrder_v1 | Wechat Pay |
| POST | `/api/v1/wechat/refund` | WechatPayController_refund_v1 | Wechat Pay |
| POST | `/api/v1/wechat/notify` | WechatPayController_notify_v1 | Wechat Pay |
| POST | `/api/v1/alipay/payment/mobile` | AlipayController_createMobilePayment_v1 | Alipay |
| POST | `/api/v1/alipay/payment/mini` | AlipayController_createMiniPayment_v1 | Alipay |
| POST | `/api/v1/alipay/payment/web` | AlipayController_createWebPayment_v1 | Alipay |
| GET | `/api/v1/alipay/order/{outTradeNo}` | AlipayController_queryOrder_v1 | Alipay |
| POST | `/api/v1/alipay/refund` | AlipayController_refund_v1 | Alipay |
| POST | `/api/v1/alipay/order/close/{outTradeNo}` | AlipayController_closeOrder_v1 | Alipay |
| POST | `/api/v1/alipay/notify` | AlipayController_notify_v1 | Alipay |
| GET | `/api/v1/shares` | getShares | Shares |
| POST | `/api/v1/shares` | createShare | Shares |
| GET | `/api/v1/shares/code/{code}` | getShareByCode | Shares |
| GET | `/api/v1/shares/{id}` | getShareById | Shares |
| DELETE | `/api/v1/shares/{id}` | deleteShare | Shares |
| GET | `/api/v1/shares/{id}/stats` | getShareStats | Shares |
| POST | `/api/v1/shares/code/{code}/track` | trackShare | Shares |
| POST | `/api/v1/shares/wechat/config` | getWeChatConfig | Shares |
| POST | `/api/v1/shares/alipay/config` | getAlipayConfig | Shares |
| GET | `/api/v1/admin/monitor/queues` | getMonitorQueues | Monitor |
| GET | `/api/v1/admin/monitor/alerts` | getMonitorAlerts | Monitor |
| POST | `/api/v1/admin/monitor/alerts/{id}/ack` | acknowledgeMonitorAlert | Monitor |
| GET | `/api/v1/admin/imports` | adminGetImports | Admin Imports |
| POST | `/api/v1/admin/imports` | adminCreateImport | Admin Imports |
| POST | `/api/v1/admin/imports/precheck` | adminPrecheckImport | Admin Imports |
| GET | `/api/v1/admin/imports/{id}` | adminGetImport | Admin Imports |
| GET | `/api/v1/admin/imports/{id}/errors.json` | adminGetImportErrors | Admin Imports |
| GET | `/api/v1/admin/imports/{id}/errors` | adminDownloadImportErrors | Admin Imports |
| GET | `/api/v1/wrong-book` | getWrongBook | Wrong Book |
| GET | `/api/v1/wrong-book/summary` | getWrongBookSummary | Wrong Book |
| POST | `/api/v1/wrong-book/{questionId}/master` | masterWrongQuestion | Wrong Book |
| POST | `/api/v1/wrong-book/{questionId}/restore` | restoreWrongQuestion | Wrong Book |
| GET | `/api/v1/me/study/overview` | getStudyOverview | Study Stats |
| GET | `/api/v1/me/study/daily` | getStudyDaily | Study Stats |
| GET | `/api/v1/me/study/knowledge` | getStudyKnowledge | Study Stats |
| GET | `/api/v1/me/study/weak-points` | getStudyWeakPoints | Study Stats |
| GET | `/api/v1/me/checkins` | getCheckins | Study Stats |
| GET | `/api/v1/subjects/{id}/knowledge-points` | getSubjectKnowledgePoints | Exams |
| PUT | `/api/v1/questions/{id}/note` | saveQuestionNote | Question Notes |
| DELETE | `/api/v1/questions/{id}/note` | deleteQuestionNote | Question Notes |
| GET | `/api/v1/me/question-notes` | getMyQuestionNotes | Question Notes |
| POST | `/api/v1/admin/questions/{id}/errata` | adminCreateErrata | Admin Errata |
| GET | `/api/v1/admin/errata` | adminGetErrataList | Admin Errata |
| GET | `/api/v1/admin/errata/{id}` | adminGetErrata | Admin Errata |
| POST | `/api/v1/admin/errata/{id}/retry` | adminRetryErrata | Admin Errata |
| POST | `/api/v1/questions/{id}/reports` | createQuestionReport | Question Reports |
| GET | `/api/v1/me/question-reports` | getMyQuestionReports | Question Reports |
| GET | `/api/v1/admin/question-reports` | adminGetQuestionReports | Admin Question Reports |
| GET | `/api/v1/admin/question-reports/{id}` | adminGetQuestionReport | Admin Question Reports |
| PATCH | `/api/v1/admin/question-reports/{id}` | adminUpdateQuestionReport | Admin Question Reports |
| GET | `/api/v1/ai/conversations` | getAiConversations | AI |
| POST | `/api/v1/ai/conversations` | createAiConversation | AI |
| GET | `/api/v1/ai/conversations/{id}/messages` | getAiMessages | AI |
| POST | `/api/v1/ai/conversations/{id}/messages` | sendAiMessage | AI |
| DELETE | `/api/v1/ai/conversations/{id}` | deleteAiConversation | AI |
| GET | `/api/v1/ai/messages/{id}` | getAiMessage | AI |
| GET | `/api/v1/ai/messages/{id}/events` | getAiMessageEvents | AI |
| POST | `/api/v1/ai/messages/{id}/stop` | stopAiMessage | AI |
| POST | `/api/v1/ai/messages/{id}/feedback` | setAiMessageFeedback | AI |
| POST | `/api/v1/ai/messages/{id}/report` | reportAiMessage | AI |
| GET | `/api/v1/ai/quota` | getAiQuota | AI |
| GET | `/api/v1/admin/ai/usage` | adminGetAiUsage | Admin AI |
| POST | `/api/v1/game/auth/login` | gameLogin | Game Auth |
| POST | `/api/v1/game/auth/refresh` | gameRefreshToken | Game Auth |

## 白名单（实现与基线不同）

- `GET /docs`、`GET /docs-json`：文档端点，不在基线中
- `GET /metrics`（`METRICS_PATH`）：Prometheus 指标端点，nestjs 同样不进文档
- 不进文档的路由（nestjs `@ApiExcludeEndpoint`，`openapi/scope.yaml` 的 `undocumentedOperations`）：`GET /api/v1/files/private/{id}`（readPrivateFile）、`GET /api/v1/files/{path}`（downloadFile）
- `FILE_DRIVER=s3-presigned` 时 `POST /api/v1/files/upload`（uploadFile）由手写 controller 实现，请求体为 `application/json` 的 `FileUploadDto`，响应 `{file, uploadSignedUrl}`；local / s3 驱动为基线中的 multipart 接口
- 生成代码的输入经 `openapi/codegen-overlay.yaml` 修正：[operations.getAppInfo, operations.healthCheck, operations.livenessProbe, operations.readinessProbe, operations.uploadFile]

## 与 nestjs 的行为差异

- 容器层拒绝的请求：路径中含非法百分号转义（如 `%G1`、结尾单独的 `%`、非 UTF-8 的 `%FF`）时，Tomcat 在进入应用前拒绝，本服务输出 400 `{code: 400, message: "Bad request", data: null}`（回落语言的默认消息），并带 X-Request-Id、安全头与 CORS 头；nestjs 对路径参数位置返回 400 `Failed to decode param '<raw>'`、对静态段返回 404。编码的斜杠 `%2F` 交给应用处理，与 nestjs 一致。
- 请求体 `Content-Encoding: br` 返回 415 `unsupported content encoding "br"`；nestjs（body-parser 2）支持 brotli 解压。`gzip`、`deflate`、`identity` 与 nestjs 一致。
- 请求体 JSON 语法错误时 400 的 `message` 为 Jackson 的错误文本，nestjs 为 V8 `JSON.parse` 的错误文本（方案约定 parity 只比对 `code` 与 `data`）。
- 204 预检响应不带 `Content-Length: 0` 与 `X-Powered-By`（Tomcat 对 204 不输出前者）；HTTP 状态行不带原因短语。三者都不在契约比对范围内。
- `GET /api/health` 的 `memory` 取 JVM 堆已用 / 已提交与进程常驻内存，`node` 为 `Java <版本>`；键名与类型与 nestjs 一致，数值不属于契约。
- 邮件在事务提交后入 `mail` 队列，由 worker 异步发送，SMTP 失败不影响接口响应；nestjs 在请求内同步发送，SMTP 失败时接口 500。
- 队列在 PostgreSQL（JobRunr，schema `jobrunr`），与 nestjs 的 BullMQ 不互通；尝试次数与退避公式相同，实际重试时刻按 5 秒轮询周期取整（可能提前或推迟至多一个周期）。账号清理执行时另持 `lock:account-cleanup:user-<id>`，nestjs 只靠 jobId 去重。
- 会话列表的 `ip` 取 Tomcat 的远端地址（IPv4 为点分形式）；nestjs 在双栈监听时为 `::ffff:` 前缀形式。parity 将 `ip` 视为易变字段。
- 指标 `path` 标签：未匹配路由一律为 `<unmatched>`；nestjs 对 `API_PREFIX` 下的未匹配路由记为其兜底路由模板 `/api*path`，只有请求体解析失败等未进入路由的请求记为 `<unmatched>`。
- 指标 `app` 标签在 `APP_NAME` 未设置时为 `app`（本服务应用名的默认值）；nestjs 为 `nestjs-app`。
- `METRICS_PATH` 的默认指标为 Micrometer 的 JVM / 进程 / 连接池指标（含 `http_server_requests_seconds` 等），nestjs 为 prom-client 的 `process_*`、`nodejs_*`；十个自定义指标的名称、类型、标签、桶、HELP 与样本行格式与 nestjs 相同，实际输出哪些样本行见下一条。
- `database_query_duration_seconds`（MyBatis 拦截器按 SQL 解析出的 `operation`、`table`）、`cache_hits_total` / `cache_misses_total`、`queue_jobs_total`（任务成功或最后一次失败时）由本服务实际记录，`/metrics` 输出对应样本行；nestjs 02fdb4d 只注册这四个指标、不记录，只输出 `# HELP` / `# TYPE` 两行。
- 指标 `status="499"`：只在 handler 经输出流写响应体时出错才记录；handler 返回后由容器刷新缓冲区时客户端断开，记录的是最终状态码。
- `memory_heap` 比较 JVM 已用堆（`MemoryMXBean`）与 `HEALTH_MEMORY_HEAP_THRESHOLD_MB`（默认 300）；nestjs 比较 V8 `heapUsed` 与写死的 300MB。数值不属于契约。
- 限流算法为 GCRA（Redis Lua，键 `throttle:<sha256>:default:gcra`），nestjs 为固定窗口（`INCR` 计数，超限后按 TTL 封禁，键 `throttle:<sha256>:default`）：同一规则下 `X-RateLimit-Remaining`、`X-RateLimit-Reset`、`Retry-After` 的取值与 429 出现后的恢复节奏不同（GCRA 每隔 `ttl / limit` 恢复一次配额，nestjs 封禁满一个 TTL）；头名、出现条件、整数格式与 `X-RateLimit-Limit` 相同。两边键不同，共用 Redis 时各自计数。
- `FILE_DRIVER=s3` 时对象的 `Content-Type` 取用途表中的 mime，文件整段接收到临时文件后再 `PutObject`；nestjs（multer-s3 `AUTO_CONTENT_TYPE`）按内容嗅探并边接收边上传。
- S3 驱动 `GET files/private/:id` 的 302 响应 `Content-Type` 为 Tomcat 写法 `text/plain;charset=utf-8`（或 `text/html;charset=utf-8`），nestjs 为 `text/plain; charset=utf-8`；正文、`Location`、`Vary: Accept` 相同。
- `FILE_DRIVER` 未设置时取 `local`（与 fastapi-temp 相同）；nestjs 的 `@IsEnum(FileDriver)` 未加 `@IsOptional`，未设置时启动失败。空串与其它非法值两边都启动失败。
- 健康检查 `wechat_api` / `alipay_api` 的探测跟随重定向（`HttpClient.Redirect.NORMAL`），但不跟随 https → http 的降级重定向、最多 5 次；nestjs 的 `fetch` 两者都跟随、最多 20 次。
- local 驱动 `GET files/:path` 的参数解码后含 `/` 或 `\` 时一律 404 `File not found`；nestjs 按最后一段查找 PUBLIC 记录，命中后由 send 处理原路径（文件不在根目录时 404 `Not Found`，含 `..` 时 403）。

## 响应视图映射（schemaMappings）

| 基线 schema | 映射到 | 原因 |
|---|---|---|
| `User` | `tech.xingyunqi.template.views.UserView` | `@Expose({groups})` 分组导致的键缺省、关联的 `__entity`、新建时关联只含 `{id}`，按 nestjs ClassSerializerInterceptor 的输出逐键构造 |
| `LoginResponseDto` | `tech.xingyunqi.template.views.LoginResponseView` | nestjs 实际键顺序为 `refreshToken, token, tokenExpires, user`，与基线声明顺序不同 |
