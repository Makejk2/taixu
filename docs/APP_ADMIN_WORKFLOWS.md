# app_admin / workflow 对话控制面

## 边界与接入（实现已接线，本批未经集成验证）

新实现位于 `harness/admin`，不替换工作流引擎、不读取密钥仓储，不暴露 `decide`。
真实保存/删除复用 `WorkflowRepository`；运行/停止复用 `WorkflowRunManager`。
提案只保存在进程内，重启失效。停止保留运行历史；删除定义沿用现有 Repository 外键 CASCADE，会级联删除该定义历史（审批会告知），不伪称保留。启动返回真实 `executionId` 和 `accepted`，不等待任务完成。
`accepted=false` 可能已有 FAILED 历史，绝不能报“没有副作用”。

生产工厂精确签名：

```kotlin
fun createAppAdminBackend(
    workflows: WorkflowRepository,
    sessions: HarnessSessionRepository,
    bots: PersonaBotRepository,
    assignments: BotAssignmentRepository,
    audit: AgentApprovalAuditRepository,
    runManager: () -> WorkflowRunManager,
    nativeSourceState: suspend (sessionId: String) -> AppAdminNativeSourceState = {
        AppAdminNativeSourceState("unresolved", "unresolved", false)
    },
): AppAdminBackend

data class AppAdminNativeSourceState(
    val modelFingerprint: String,
    val environmentFingerprint: String,
    val supportsRun: Boolean,
    val resolvedModelId: String? = null,
    val resolvedModelVariant: String? = null,
)
```

`nativeSourceState` 必须来自原生服务，不得来自工具参数。每次提案、准备审批和批准回放均重新求值。
指纹须覆盖有效模型档案/具体模型及默认回退、Provider 配置代次（不含凭据）、本地执行目标/发行版身份及权限。
`supportsRun` 只在原生适配器确定当前目标可交给固定目标 WorkflowRunManager 且模型上下文受支持时设 true；
未知/不支持目标、缺少有效模型 ID/variant 均 fail closed，默认回调不会伪造 run 支持。
源会话仓库的 workspace/model/approvalMode/runMode/executionTargetJson 同时纳入来源 hash。
推理/子智能体节点模型固定为来源有效模型；显式不同节点模型拒绝，不继承运行中切换的全局默认。
Host/GUI 通过来源 authority 原生逐节点批准及真实宿主权限检查；GUI 每轮固定来源模型并在配置变化时重新批准。

必须以延迟 `runManager = { get<WorkflowRunManager>() }` 接入，不能在创建 ToolExecutor 时 eager resolve，
避免 ToolExecutor → workflow → Harness 循环。

主代理需要在现有 enum/Provider/ToolExecutor/DI 做的接线：

1. 注册名 `AppAdminToolSpec.name`（`app_admin`）及 `description/schema`。
2. 将 app_admin 纳入统一 **AiAdmin 开关 Boundary**，关闭时调用 `backend.disableManagement()` 清空所有待批权限；
   不能只在通用执行后兜底。成员/群/独立 assignment/`workflow:`/`subagent:` 派生会话由工厂拒绝。
3. 在通用 FULL_ACCESS、remembered grants、approved replay 快捷路径 **之前** 分派 `executeAppAdmin`。
4. `approvedNative=true` 只能来自真实原生审批回放，保留最初 ToolCall.id，不能取自 JSON、FULL_ACCESS、历史 grant 或模型字段。
5. 请求原生审批使用现有 `requestApproval(ApprovalDecision)` 机制，不使用 ask_user 默认答案或模型 approved。
6. app_admin 被禁止/后台 Lane 不支持原生审批时按既有 Boundary 拒绝/上交，不调用 apply。

执行门精确签名：

```kotlin
suspend fun executeAppAdmin(
    backend: AppAdminBackend?,
    sessionRunMode: suspend (String) -> RunMode?,
    call: ToolCall,
    sessionId: String,
    approvedNative: Boolean,
    now: Long,
    requestApproval: suspend (ApprovalDecision) -> ToolResult,
): ToolResult
fun appAdminApproval(args: JsonObject): ApprovalDecision
```

Backend 只有只读/提案入口 `execute(sessionId, args): Pair<Boolean,String>` 和 `disableManagement()`；
`applyNative/prepare` 为 internal 服务方法，无公共模型可调用 apply bypass。

## JSON 合约

严格拒绝未知顶层/操作/定义字段、非字符串变量、额外 approved/userConfirmed/approvedNative 等自批字段。
`module` 必须为 `workflow`；后续模块可增加独立实现，不会默默 fall through。

```json
{"module":"workflow","action":"list"}
{"module":"workflow","action":"get","workflowId":"custom"}
{"module":"workflow","action":"status","executionId":"wf_actual"}
{"module":"workflow","action":"propose","reason":"用户希望运行此工作流","operation":{"operation":"run","workflowId":"custom","workspace":"/workspace/project","variables":{"TARGET":"debug"}}}
{"module":"workflow","action":"apply","proposalId":"native-generated-id"}
```

operation 精确变体：

- `save`: `{"operation":"save","definition":<WorkflowDefinition JSON>}`
- `delete`: `{"operation":"delete","workflowId":"..."}`
- `run`: `{"operation":"run","workflowId":"...","workspace":"原生来源工作区","variables":{}}`
- `stop`: `{"operation":"stop","executionId":"..."}`

save 使用真实 `WorkflowDefinition` 反序列化和 `WorkflowValidator.validate`，不支持任意脚本接口扩展。
只接受现有内置节点；插件动作和动态展开节点明确拒绝（非 stub 假成功）。
内置定义不可覆盖/删除，包括尚未初始化入库的 `BuiltinWorkflows.all` ID；请使用新 ID 另存。
run 显式冻结原生 workspace、定义、defaults+variables 与有效模型变量；不得传模型控制变量或凭据变量。
权限为 PLAN 时只读 list/get/status，不得 propose/apply；FULL_ACCESS 仍逐次原生审批。
所有工作流节点审批仍在现有原生 UI；本模块无 resume/retry/decide API，不代批 HUMAN_APPROVAL。

## 提案/一致性/失败语义

- 原因、会话、原生来源、定义 hash、操作 JSON、创建时刻不可变；TTL 为 5 分钟，时钟回退拒绝。
- 原生 prepare 绑定 callId；回放须同时匹配 session/proposalId/callId。
- 原生来源/权限/模型/工作区/定义/停止目标状态失配，一经观测立即永久消费；恢复旧状态也不能复用。
- apply 只允许 module/action/proposalId，不能替换操作或 variables。
- 消费先于写入；成功、部分失败、取消均不重试。写后审计失败仍返回“变更已执行，审计写入失败”，不误报零副作用。
- 审计仅记录动作、ID、来源/操作/快照/原因 hash，不记录原因原文、变量、凭据、定义、日志或端口异常文本。
- 实际 run 拒绝仍返回真实 executionId/accepted=false；stop 只是取消请求，最终状态由 status 核对。
- Repository 无原子 compare-and-swap API。服务内 Mutex 序列化本控制面，批准前复查快照；
  无法锁住原生编辑器跨服务并发写入，最终复查与 Repository 调用之间仍存在 TOCTOU 窗口，后续可增加仓库 CAS。
  不宣称跨所有写入源线性一致；内置身份在生产写入前再次保护。

## 脱敏与容量

list 最多返回 200 条元数据；get 返回 ID/name/isBuiltin/hash/节点 ID+类型，不返回 config/defaultVariables/命令/prompt。
status 仅返回运行元数据，历史读取完全不解码 finalContextJson；不返回日志、error/context/审批令牌。
变量 key 的凭据模式与值中已知 token/private-key/auth 形式拒绝；不接触 SecretManager。
启发式无法识别任意被伪装为普通业务文本的秘密，使用方不可把凭据塞入普通标题或字段。
定义上限 128K、100 节点、400 边；最多 64 个变量，单值 8192 字符；最多 100 个待批提案。

## 验证状态

`AppAdminServiceTest` 使用 fake 端口覆盖 save/delete/run/stop、真实受理 ID、身份/call/session、TTL/回退、
来源/定义失配永久消费、PLAN、原生审批暂停与回放、自批字段拒绝、敏感变量拒绝、只读脱敏、取消、写后审计失败和停用失效。
本次依照任务要求 **未执行 Gradle/CI/真实模型/安装**；仅静态检查，单测未运行，不能宣称编译或测试通过。

## 当前真实接入状态
通过已有`app_config`的`module=workflow`路由，未增加独立工具枚举。默认关闭的超管开关、统一互斥执行边界及epoch失效仍覆盖此分支；所有写操作逐次原生批准。get/list/status脱敏。save/delete复用真实持久库，run/stop复用运行管理器。

运行门禁以以下精确矩阵为准。保存不等于运行，关闭超管不等于停止全部运行；紧急停止仍可用原生界面。本入口不提供 decide/resume/retry 或凭据接口。

## 并发一致性验证
保存和删除已改为WorkflowDao同一Room写事务内按批准definitionHash比较并变更；并发原生编辑会使CAS拒绝，旧提案不重放。WorkflowDefinitionCasTest覆盖缺失创建、原生编辑、删除后复活、竞争提交以及历史级联语义；历史记录不作为本轮验证证据；真实 Room 及整包集中验证待办。

## 固定目标租约与已接能力（静态实现状态，未测试）

旧 checkpoint 的 Agent/Host 一概拒绝已被真实 source 验证替代，不代表绕过安全核。
`startBound` 要求来源主会话操作授权，持久 consume 在一次 INSERT 中绑定唯一 executionId；
`WorkflowExecutionLeases.use` 在创建环境前核对该持久授权，恢复不能拿旧批准开新执行。
每个节点经 lease.operation 核定义 hash、目标和 source；新图/动态扩图不能沿用旧授权。
source 仅对新 admin 启动强制要求；无 source 旧日志仍不能运行 Agent/Host，不能自动补造来源。

### Exact node matrix

| 节点类型（枚举精确值） | admin 运行准入 | 运行时边界 |
| --- | --- | --- |
| TRIGGER | 支持 | 固定完整图 |
| CONDITION_BRANCH | 支持 | 固定完整图 |
| HUMAN_APPROVAL | 支持 | 原生 broker |
| DELAY | 支持 | 节点执行预算 |
| SET_VARIABLE | 支持 | 变量及敏感值检查 |
| TERMINAL_OUTPUT | 支持 | 既有输出执行器；管理 status 不导出日志 |
| BASH_COMMAND | 需 bound lease | 冻结环境、工作区、资源 owner |
| PROCESS_SERVICE | 需 bound lease | 冻结环境及服务生命周期 |
| TAIXU_BUILD | 需 bound lease | 固定环境与构建执行器 |
| AGENT_INFERENCE | 需 bound lease + source | 来源模型/variant、每轮配置指纹及工具权限 |
| SUBAGENT_DELEGATE | 需 bound lease + source | 派生 Lane 继承来源、目标、权限；真实工具审批 |
| HOST_ACTION（含 gui_pilot） | 需 bound lease + source | 原生逐节点批准，真实 privilege 检查；GUI 每轮模型重核 |
| PLUGIN_ACTION | 拒绝 | 尚无可信原生插件 executor 准入，不能假开放 |
| 任意含 WorkflowExpansions.SOURCE_KEY 的节点 | 拒绝 | 必须重新批准完整新图，不沿用原授权 |

无 bound lease 的 appAdminValidateNativeRun 继续拒绝命令、服务、构建、Agent、子智能体、Host 作用节点。
普通原生 UI 无绑定旧路径不在本次扩大授权范围；绑定旧日志的无 source Agent/Host 继续拒绝。

### 真实链与资源边界

- 本地固定 distribution；远程使用 FrozenRemoteWorkflowEnvironment 的显式 SSH target/server snapshot、原生 server 配置/凭据租约和真实 connector，不读 active remote、不回退本地。目标不可用即拒绝。
- 资源关闭失败保留 owner/pin，status.cleanupPending=true；stop 重试清理不重跑业务，成功释放后才解除维护/档案修改保护。
- Scheduler 每节点装配 WorkflowLaneAuthority。Agent adapter 和派生会话核来源模型、workspace、target、approvalMode/runMode；不注入 FULL_ACCESS、不解析活动默认模型。
- SubagentLaneRunner 保留 ToolExecutor 原权限策略：只对 approvalDeferred 的未执行调用等待原生批准；展示脱敏 actual-call 参数和摘要，批准只重放同一 immutable bounded call。拒绝不执行，不重放之前调用。
- 模型配置变化通过 reviewModel 原生重批，每轮重取并核配置；GUI 同样固定来源模型。删除模型/变体不静默切默认。
- WorkflowSourceAuthority 在发布审批前持久 REQUESTED，动作前持久 APPROVED/DENIED，取消尝试 UNKNOWN；写审计失败不能执行。审批记录不存明文提示词/凭据，不跨进程复用批准。
- 审批等待使用独立 active-work 执行预算，状态真实投影 WAITING_APPROVAL；原生等待不冒充执行。恢复未知副作用仍依既有 Recovery 显式 retry，不能自动重放。

### 未完成的底层缺口与集中验证待办

插件 registry 不等于真实 executor；PLUGIN_ACTION 继续拒绝。动态扩图缺少 admin 完整新图重新提案/批准协议，继续拒绝。
模型 secret bytes 的仅凭据轮换没有公共 revision，不能声称检测所有凭据变化；source 指纹只覆盖持久配置及引用。
真实 Host 权限、GUI 无障碍/设备能力及 SSH 连接仍依赖设备环境，原生批准不替代权限或连通性。
本轮只静态核对及改动，未运行测试、编译、CI、安装或真实模型。集中阶段须覆盖无 lease/source、授权重用、
图/模型漂移、审批拒绝与审计写失败、SSH pin/清理失败、旧日志恢复及 GUI/Host 权限拒绝；不能把此矩阵当成 tested 结果。
