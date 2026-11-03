# app_admin / workflow 对话控制面

## 边界与接入（已接入，集成验证见CI报告）

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
`supportsRun` 只在原生适配器确定当前目标可交给本地 WorkflowRunManager 且模型上下文受支持时设 true；
未知、SSH/不支持目标、缺少有效模型 ID/variant 均 fail closed，默认回调不会伪造 run 支持。
源会话仓库的 workspace/model/approvalMode/runMode/executionTargetJson 同时纳入来源 hash。
推理/子智能体节点模型固定为来源有效模型；显式不同节点模型拒绝，不继承运行中切换的全局默认。
原生 HostAction GUI 模型/运行时的后续变化仍由现有执行器负责；若装配方不能保证其来源一致，应返回 supportsRun=false。

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

运行限制：Agent/subagent/GUI Agent、插件和展开节点拒绝；生产RunManager尚无全程绑定目标租约，因此沙盒命令/服务/构建/HostAction执行也明确拒绝，不能因快照检查冒充无TOCTOU。当前仅静态纯数据/条件/等待/人工审批/输出工作流能从此入口运行。保存定义不等于运行。停止同样需开启超管并原生批准；关闭开关不是取消全部运行，紧急停止仍可用原生工作流界面。

原文所称“节点审批仍原生”指人工审批节点及引擎现有门禁，并不意味着每个Bash节点本来都有逐节点审批。本轮不提供decide/resume/retry工具，未开放凭据。

## 并发一致性验证
保存和删除已改为WorkflowDao同一Room写事务内按批准definitionHash比较并变更；并发原生编辑会使CAS拒绝，旧提案不重放。WorkflowDefinitionCasTest覆盖缺失创建、原生编辑、删除后复活、竞争提交以及历史级联语义；本地纯逻辑25项通过，真实Room及整包CI待核实。
