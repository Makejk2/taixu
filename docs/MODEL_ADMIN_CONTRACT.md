# WP2 非凭据模型管理：生产核心与接线合同

## 已落盘能力与边界

- 新包 `harness/admin/model` 提供 typed commands、严格 JSON decoder、安全 list/get、已有档案 patch、全局默认与当前会话选择、提案/逐次原生审批门。
- `ModelManagementCas.kt` 提供安全数据类型、Repository 边界及 Room DAO mixin。CAS 在 `@Transaction` 内读取完整私有行摘要、验证并写入；不是用内存锁冒充数据库并发保护。
- list/get 只给 modelId、globalDefault 和温度/maxTokens/topP/reasoning 枚举。名称/provider/model 原文也不返回，避免存量任意文本夹带凭据。digest/sessionVariant 不输出。
- patch 仅上述五项：缺字段保留旧值，显式 null 清该参数。SQL 只更新白名单列，绝不重写 customHeaders/secretRef/baseUrl/provider。
- provider、endpoint、model variant、header、Key 都不可改；换源必须去原生凭据 UI 重新明确绑定，不能携旧 Key 改源。
- 创建/删除档案为下批生命周期任务（删除须明确清理凭据），本批不是目录占位：已有档案参数和两种选择均有真实数据库写路径。
- 无 discovery/probe/真实模型调用，无自动网络重试。

## 主集成 owner 的精确最小接线

1. 本 Lane 已将 `AiModelDao : ModelManagementCas` 落盘，并在 `ModelManagementCas.kt` 提供 `RoomModelManagementRepository(dao: ModelManagementCas)`。无新增表/迁移；真实 CAS 在生成的 Room DAO 事务内执行。
2. 主 owner 在数据库 DI 注册 `ModelManagementRepository = RoomModelManagementRepository(aiModelDao)`；harness singleton 构造：

   ```kotlin
   val authority = NativeModelAdminAuthority(runtime, sessions, bots, assignments)
   val backend = createModelManagementBackend(modelManagementRepository, authority, auditRepository)
   ```

   `createModelManagementBackend` 的回调实际调用 `AgentApprovalAuditRepository.record`，强制持久化 intent/completion；不能用 no-op 回调装配生产。
3. 新 wrapper 精确签名（所在包 `top.wkbin.taixu.harness.admin.model`）：

   ```kotlin
   suspend fun executeBoundModelManagement(
       backend: ModelAdminBackend?, actor: ModelAdminActor, call: ToolCall,
       approvedNative: Boolean, now: Long,
       requestApproval: suspend (ApprovalDecision) -> ToolResult,
   ): ToolResult
   ```

   router 的 `module=model` 分支传入真实 `requestApproval`，不再自行解释 `ModelAdminResult`。首次 apply 的原生预览包含非敏感参数、global/current scope 与 `willResetVariant`；返回 callback 的真实暂停 ToolResult。只有可信原生 continuation 才传 `approvedNative=true`。JSON 里任何 nativeReplay/approval/身份字段均拒绝。
4. 每次分发（含 list/get/propose/replay）先执行 `ManagementActorGuard`，在 FULL_ACCESS/grant/cache 之前进入专用分支。actor = `ModelAdminActor(context.sessionId, nativeMainJournalOperation.id, call.id)`；operation 必须来自 native runtime/current main lane，不能从模型参数或父 Lane 继承。authority 每次重新核查 native journal/DB ownership。
5. 主 `AiAdminBoundary` epoch/开关的同一同步边界内调用 `backend.service.disable()`，会话失效/审批拒绝/结束调用 `invalidateSession(session)`；service 不单独替代主 owner 的 epoch admission。原生 replay 必须持有相同 session/operation/call，不能让异步 disable 与 apply 脱离主边界串行化。
6. 新提案仍是 120 秒 TTL、最大 256 个。prepare 重读快照，变化时条件删除永久消费，不展示旧审批；apply 在 IO 前消费，再次事务内 CAS。后台 Lane 无法原生暂停时按主边界 deferred 处理，不能视为批准。

## 线协议（module 必填；其他未知字段全部拒绝）

```json
{"module":"model","action":"list"}
{"module":"model","action":"get","modelId":"existing-id"}
{"module":"model","action":"propose","operation":"patch","modelId":"existing-id","patch":{"temperature":0.7,"maxTokens":null}}
{"module":"model","action":"propose","operation":"global_default","modelId":"existing-id"}
{"module":"model","action":"propose","operation":"current_session","modelId":"existing-id"}
{"module":"model","action":"apply","proposalId":"native-issued-uuid"}
```

- patch 允许 temperature [0,2]、topP [0,1]（有限浮点）、maxTokens [1,2000000]、reasoningMode enabled/disabled、reasoningEffort low/medium/high，五项均可 null。空 patch 拒绝。
- current_session 只更新原生受控来源会话 modelId，同 modelId 重选保留 variant；真实切换才清除旧 profile 的 modelVariant，预览只披露 willResetVariant 布尔，不展示 variant 原文；不改 globalDefault。null 会话选择在 list 中表示沿用全局默认；本批不提供 reset-to-global 命令。
- PLAN 允许 list/get，不允许提案/prepare/apply，写入检查发现 PLAN 时清该会话提案；FULL_ACCESS 不豁免。每次查询/提案/批准重放重新验证真实 main lane operation 与非 Bot/群/派生用户会话。
- CAS digest 覆盖全部现有模型行（含私有配置但只留摘要）及来源会话；任一存量字段编辑导致拒绝，返回 MODEL_SNAPSHOT_CHANGED。更新 schema 时必须同步 digest 字段清单。事务比较只保证当前值一致（改回原值的 ABA 不视为版本冲突）；如需审计级版本 CAS，另加持久 revision 迁移。
- 所有异常固定错误码；严禁记录原始实体、decoder/数据库异常或拼入错误详情。`Rejected` 表示未确认执行；跨进程崩溃后以 list/get/原生页面核对，不自动重放。业务失败重提案必须重新审批。

## 验证与尚未完成项

`sh harness/src/test/java/top/wkbin/taixu/harness/admin/model/run-focused.sh`
使用已缓存 Kotlin 2.4.10 compiler + JUnit，无 Gradle/安装/CI/外网。脚本仅在 lease 测试目录生成临时产物并清理。Room annotation、工具消息/审批 DTO 和外部 repository 使用极小编译 stub；真实模型实体、会话 entity（排除无关 DAO）、审计 repository、模型生产源码参与编译。不是 Room 集成验证。

测试覆盖：私有字段/错误不泄漏、未知凭据 patch 拒绝、缺省参数保留、显式 null、非法范围、CAS 失配、审批一次性/跨 call/session 拒绝、TTL、PLAN/来源权限、单会话不串改全局、全局唯一、私有 header 编辑导致摘要失效且 patch 保留凭据。

### 本轮实际验证与待 CI

- 更新 focused runner 以隔离其他 Lane 新增的 SessionManagementDao/fence 依赖；仍运行原有 7 项服务/算法测试，不把 stubs 称为 Room。
- 新增 `ModelManagementRoomTest` 四项真实 Robolectric + `Room.inMemoryDatabaseBuilder(AppDatabase)` 测试，使用真实 `AiModelDao` / `RoomModelManagementRepository`：同选择保留 variant、切模型仅影响来源会话、隐藏字段与 variant CAS 失效、patch 保留凭据、全局默认与当前选择隔离、SQL 失败事务回滚。**尚未运行，留 CI**，focused runner 明确排除这组。
- 审计：intent 持久化失败时不开始数据库 mutation；进入 CAS 后的数据库/完成审计异常返回 `MODEL_UNKNOWN_PARTIAL`，提案已消费，不声称无副作用、不回显异常。取消保持协程取消语义，进程崩溃/取消后一律核对台账和实际状态，不自动重放。

**未完成：** 主 router/DI、AiAdminBoundary epoch 串行化接线、真实 Room KSP/新增 Room 测试、端到端原生审批/后台身份/ FULL_ACCESS 防绕过仍由主 owner 验收。本 Lane 未修改 AppDatabase/SessionDAO，未执行本机 Gradle、全量构建、CI、安装、push 或委派。不能声称已上线或全量验收通过。

## 连续实现补充：原生表单与历史会话恢复（尚未装配）

本节取代上文“创建/删除下批任务”的范围说明，但不表示已经执行表单或通过验证。

### 模型原生表单桥

新增 `ModelNativeFormBridge(launcher: ModelNativeFormLauncher, authorize: suspend (String) -> Boolean, clock: () -> Long = System::currentTimeMillis)`。

- `request(sourceSessionId, operation: ModelNativeFormOperation, modelId: String? = null)`，operation 为 CREATE / DELETE / CHANGE_CREDENTIALS；CREATE 无 modelId，其余必须有合法 id。
- launcher 的真实接线点是 `suspend fun open(request: ModelNativeFormRequest)`；UI owner 在此显示原生页面，收集表单/Key。返回只表示页面已请求，状态为 PENDING_NATIVE_FORM，不是 APPLIED。
- `status(sourceSessionId, requestId)` 仅提供状态，不提供表单值。
- 真实原生批准后 `submitFromNative(requestId) { frozenRequest -> ... }` 执行一次 writer，回调正常返回才 APPLIED；失败/取消标 UNKNOWN_PARTIAL，不重放。`invalidateSession` 撤销尚未执行的表单。
- authorize 必须接主 boundary 的开关/epoch/main-lane/PLAN/所有权检查；submit 必须在同一生命周期串行化边界内执行，避免 authorize 后关闭开关的竞态。回调不可由 ToolExecutor JSON action 直接调用。
- 原生 UI 注入现有 `AiProfileWriter`：create/upsert 及 deleteProfile 是现有真实入口。**当前 writer 没有跨安全存储和 Room 的事务、引用保护/持久中断台账，桥不伪造这些保障。** 主 owner 仍须在 writer 所属生命周期服务里增加真实冻结快照/CAS、默认和会话引用处理、secretRef 替换/清理与持久恢复记录，再调用桥的 submission 回调。禁止把旧 Key 随新 provider/endpoint 自动带入；换源必须在本地原生表单要求重新绑定凭据。禁止把原生表单值、Key、异常文本写入 tool args/result/log。
- 进程重启会丢失桥的内存 request；它不是持久操作台账。执行中崩溃需通过真实模型/凭据服务核对，不宣称已取消/已执行。

### 会话 claim / cleanup 原生接线

新增 `NativeSessionRecovery(dao: SessionManagementDao, control: () -> InteractiveSessionControl, authorize: suspend (String) -> Boolean)`；这是 native-only API，不能注册为 AI 直接调用动作。其依赖是现有 Room DAO、实际 HarnessLoop control 和当前用户原生确认校验。

- `claimFromNative(id, expectedRevision)`：显式确认后事务单 SQL 插入持久 claim，要求当前 session 存在、fence revision 一致且 mode=0。历史会话不自动回填 USER。
- `isExplicitlyClaimed(id)`：主 factory 的 nativeOwnership callback 可在排除所有 workflow/subagent/bot/group/assignment 真实注册后使用此映射；**不可注入恒 true**。新会话应由主 lifecycle 写原生 provenance 或明确 claim；当前 create 返回 UNKNOWN_PARTIAL 是 fail-closed，不是成功冒认。
- `pendingCleanupFromNative()` 返回 mode=5 ID；原生重试界面展示并确认后调用 `retryCleanupFromNative(id, protectedSourceSessionId)`。运行时 session reservation 内 CAS 接管旧 token，保留 mode=5，重建 coroutine 删除租约后调用真实 `control.deleteSession`；失败仍 fenced。主 boundary 必须阻止恢复与其他实例/活跃 lifecycle 并发，不能把进程间“旧 token”当作确定死亡证明。现有清理服务须逐个保证幂等才能安全重试。
- `loadFromNative(id)` 核验授权与开放 fence 后调用真实 `loadSession`。原生 authorize/负载生命周期必须在主 boundary 持锁直到 load 完成，避免校验和加载之间竞态。AI 的 session load proposal 路由尚未补入，不可宣称已接通。

### v88 合并精确需求

不修改 v87、不生成 schema。AppDatabase 主 owner 注册 `SessionManagementFenceEntity`、`SessionManagementClaimEntity` 和 `SessionManagementDao`；迁移与新库 onCreate 都调用 `SessionManagementSchema.install(db)`。该 installer 保留现有全部 fence trigger DDL，并新增：

```sql
CREATE TABLE IF NOT EXISTS session_management_claims (sessionId TEXT NOT NULL PRIMARY KEY, claimedAt INTEGER NOT NULL);
```

现有 fence 表 DDL：
```sql
CREATE TABLE IF NOT EXISTS session_management_fences (sessionId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, ownerToken TEXT, mode INTEGER NOT NULL);
INSERT OR IGNORE INTO session_management_fences SELECT id,0,NULL,0 FROM harness_sessions;
```

所有准确触发器定义集中在 `SessionManagementSchema.statements`，v88 直接调用此对象，不复制丢失。claims 不做历史数据回填；没有 FK 是为保留显式审计/删除 tombstone，未来隐私清理策略须一起设计。模型本轮新增桥无 schema；真实 writer lifecycle 中断记录尚未实现，不能凭空列不存在的迁移。

### 本轮限制/待办

未执行任何测试、编译、Gradle、CI、安装、下载、提交或子委派。没有修改 router/ToolExecutor/DI/AppDatabase。主 owner 尚须接 UI launcher、epoch/原生授权、会话 claim 展示/恢复重试页面及 AI load 提案；模型 create/delete/换凭据的真实生命周期 CAS/持久台账尚未完成。metadata 分页沿用现有全量过滤实现，尚非数据库分页；archive 仍 unsupported。这些不是验收通过项。
