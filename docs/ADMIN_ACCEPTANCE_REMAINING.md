# AI 超管与独立协作团队：剩余模块及验收核对

## 1. 结论与证据口径

本轮目标仍是**完整 AI 超管 + 独立协作团队**，不是把目标缩为目录发现、几项设置或提示词编排。以下工作包是连续实现顺序，不是产品终点，也不是其余目标的取消。

本次只做定向源码交叉核对；未构建、未运行测试、未安装 APK、未连接真机、未调用模型。源码存在、生产装配、定向测试通过、真机验收通过是四个不同状态。本文所有真机步骤均为**待执行**，不背书当前安装包。

证据优先级：当前源码及调用链 > 同步文档 > 目录/提示词。并行工程正在改动，最终验收须固定 APK 版本、源码 revision 和设备后重新核对。在建的 member suggestion 与 workflow target 即便已出现新文件，也不能标为完成。

### 已核查的真实状态

| 模块 | 当前事实 | 对话缺口/判定 |
|---|---|---|
| 模块目录 | `ToolExecutor` 将 `app_config(action=modules/inspect_module)` 路由至目录，目录只列可见工具与真实注册状态 | 目录不是调用授权，也不是全应用管理接口 |
| 设置 | `AppConfigSpec.All` 有 appearance.theme/dynamic_color/font_scale、terminal.font_size/haptics；approval.mode、run.mode 属 selfPrivilege | 只覆盖白名单；不能说任意设置可读写，更不能自行开启 AI 管理或提升审批权限 |
| 团队 | `TeamService` 有 list/get/status/proposal/propose；写 op 为 create/draft/publish/start/stop/communication-policy，原生 gate 才可 apply | 持久团队与独立启动链路已有代码；不是任意 persona CRUD，也不证明脱离前台后实际稳定协作 |
| 成员建议 | 工程代理正在实现 member suggestion | **在建**：待建议来源、成员身份、持久化、审批与真实独立运行联合验收 |
| 工作流 | `app_config(module=workflow)` 路由到 admin；save/delete CAS、run/status/stop 有服务与 gate | **部分覆盖**。本轮看到 `appAdminValidateNativeRun` 仍拒绝 BASH_COMMAND/PROCESS_SERVICE/TAIXU_BUILD/HOST_ACTION；workflow target **在建**，不可提前报支持这些节点 |
| 会话 | `InteractiveSessionControl`、`SessionControl`、`HarnessSessionRepository` 提供真实生命周期能力 | `history_search/read/compress` 不等于 session CRUD；目录标为 unsupported 合理 |
| 模型档案 | Settings 用 `AiModelRepository` 与 `AiProfileWriter`；保存/删除涉及凭据生命周期 | 无独立非 secret 管理对话适配器；不应直接公开实体或整个 saveModel 表单 |
| 插件工具 | `HarnessPluginRegistry.registeredToolSchemas/inspect/call` 和 `CapabilityToolRouter` 的 plugin 分支是真实执行路径 | 工具执行不是纯目录缺口；仍需核对实际注册、可见性、schema、批准与运行结果。插件包安装/更新/卸载不是该 API |
| MCP | 目录仅列有效启用服务 ID，状态 enabled_uninspected | 不代表工具已发现或服务已连通；发现连接须走真实能力入口 |
| 终端 | `TerminalSessionManager` 有创建/切换/关闭、句柄状态与 PTY 输入 | base/process 不等于终端页标签管理，无专用对话管理适配器 |
| 发行版 | `LinuxRuntime` 有安装、导入、切换、更新、卸载、reset、health 等接口与状态流 | 真实生命周期已有底座，但没有因此自动获得对话入口；涉及长事务和活动执行目标 |
| 其他 | browser/git/diagnostics/permission 等目录仍列 unsupported/UI-only | shell/host 通用操作不等于专用管理 API；需后续逐模块实现，不能靠目录声明补齐 |

### 文档偏差

`APP_MODULE_COVERAGE.md` 开头已说明目录接入，但后面仍保留 “integration steps outside ...” 的历史接线说明；应以 `ToolExecutor` 实际路由为准。`APP_ADMIN_WORKFLOWS.md` 标题/部分接线示例仍写 app_admin，而当前配置路由使用 `app_config(module=workflow)`；其 `AppAdminNativeSourceState` 示例也未列本轮源码的 `localDistributionId`。该文档“集成验证见 CI 报告”不等于本轮已查看或通过 CI。以上待所属工程后续同步，本轮不改其他文件。

## 2. 优先级及最多三个独立落地工作包

共同门槛：使用现有管理开关与原生审批边界；读路径不得连接模型、启动进程或读取 secret；写路径提案/预览/原生确认/复核/执行/审计。FULL_ACCESS、ask_user 默认答案、模型 JSON 的 approved 字段均不是管理批准。并行实现必须预先分配各自 backend 文件，公共 schema、路由与 DI 由一个集成 owner 串行合并，避免重复改同一入口。以下 action 名仅是待设计 API，不宣称现已可调用。

### WP1 / P0：会话管理对话适配器

- Owner：会话/Harness 工程；时间窗：当前两个在建项之外的下一实现批次，完成后立即定向验收。
- 用户结果：不靠手动找页面即可列出、创建、重命名和删除自己可管理的普通会话，且删除不会留下运行中的孤儿任务。
- 已存在 public API：
  - `HarnessSessionRepository.observeAll(): Flow<List<HarnessSessionEntity>>`、`findById(id: String): HarnessSessionEntity?`。
  - `InteractiveSessionControl.newSession(title: String, workspace: String = "", projectType: String = ""): String`、`loadSession(id: String)`、`renameSession(id: String, title: String)`，均为 suspend。
  - `SessionControl.deleteSession(id: String)`（suspend）、`cancel(targetSessionId: String? = null)`；`currentSessionId`、`sessionRunStates` 为状态流。
- 实施内容：脱敏 list/get，加 create/rename/delete 原生提案；load/switch 明确是全局前台选择变化，不暗中随 create 改变操作会话。`newSession` 的前台副作用需集成设计说明，不能把它假定为后台纯创建。
- 不可绕过：删除调用 `SessionControl.deleteSession`，不能只调 repository.deleteSession。`HarnessLoop.deleteSession` 包含 tombstone、cancelAndJoin、环境释放、审批/grant/config 撤销、子任务与消息清理。
- 约束：禁止删除当前发起审批的会话；普通用户入口不得越过 bot/assignment/workflow/subagent 会话所有权；分页且不返回消息正文；读 DTO 不返回执行目标中的敏感连接数据。审批后目标/所有权变化必须失效。底层没有通用会话 CAS，需新适配器补并发复核/互斥，不能声称 repository 已保证。
- 成功标准：创建/改名/删除后原生会话列表一致；无模型调用；删除测试目标没有残留 job/grant；拒批、重放、跨会话、目标已删各有确定结果。

### WP2 / P1：非 secret 模型档案管理

- Owner：模型/设置工程；时间窗：与 WP1 可并行开发，公共接线由集成 owner 串行合并。
- 用户结果：安全查看可用档案、选择已有档案并调整非敏感参数，不把 API Key、认证头或完整配置泄露给模型。
- 已存在 public API：
  - `AiModelRepository.observeAll(): Flow<List<AiModelEntity>>`、`findById(id: String): AiModelEntity?`、`activeModel(): AiModelEntity?`、`activate(id: String)`、`updateReasoning(id: String, mode: String?, effort: String?)`，除 observeAll 外均 suspend；`activate` 对唯一激活使用事务。
  - `HarnessSessionRepository.setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long)`：会话绑定，不等于全局 activate。
  - `AiProfileWriter.upsertProfile(request: UpsertRequest)`、`deleteProfile(id: String)`（suspend）。UpsertRequest 包括 id/name/provider/model/baseUrl/apiKey/customHeaders 等，不能整体映射为模型可写 schema。
- 实施内容：非 secret DTO list/get、明确区分全局默认与单会话选择、已有档案的白名单参数 patch。新建无凭据档案可作为同包后续验收项，但必须显式呈现“未配置凭据”，不自动探测外网或宣称可推理。凭据录入继续由原生用户界面完成。
- 风险约束：`upsertProfile` 空 apiKey 表示保留旧 Key，但内部仍读已有 Key；它不是无秘密访问 API。partial patch 必须保存旧值，不能因为默认参数把 customHeaders 清空；改变 endpoint/provider 可能把既有 Key 发往新目标，应排除该 patch，另走明确原生凭据重绑定。`deleteProfile` 会删 Key，不能归类为无 secret 影响操作，也不能为绕过该影响直接 DAO.delete 留孤儿凭据。
- 验收：读结果不含 secretRef、API Key、customHeaders、认证 URL；无模型 discovery/probe；全局激活唯一且单会话不串改；非法范围/目标变化拒绝；修改非敏感参数前后原生页面一致。档案删除属于需单独明确凭据后果的后续子项，不将未做部分标完成。

### WP3 / P1：发行版生命周期对话适配器

- Owner：Runtime 工程；时间窗：下一实现批次，读/切换先交付，破坏性操作紧接实现且保留未完成标记。
- 用户结果：管理已安装 Linux 环境，能知道真实目标和任务进度，不把工作切到错误发行版或清空在用环境。
- 已存在 public API（`runtime/.../LinuxRuntime.kt`）：
  - `state: StateFlow<RuntimeState>`、`activeDistroId: StateFlow<String>`、`installedDistros: StateFlow<List<InstalledDistro>>`。
  - `switchActiveDistro(distroId: String): AppResult<Unit>`。
  - `installDistro(request: RuntimeInstallRequest, onProgress: suspend (DownloadProgress) -> Unit = {}): AppResult<Unit>`。
  - `importDistro(request: RuntimeInstallRequest, archive: File): AppResult<Unit>`（接口默认可不支持，不能承诺每个实现可用）。
  - `uninstallDistro(distroId: String): AppResult<Unit>`、`resetSandbox(distroId: String? = null): AppResult<Unit>`。
  - `checkRootfsUpdate(distroId: String? = null): AppResult<RootfsUpdateInfo>`、`updateRootfs(distroId: String? = null): AppResult<Unit>`、`healthCheck(distroId: String? = null): RuntimeHealth`，上述函数均 suspend。
  - `RuntimeInstallRequest(distributionId: String, registryRoute: RegistryRoute = AUTO)`；发行版选自实际 `DistributionCatalog.supported`，不接受任意 imageReference 拼接。
- 实施内容：纯快照 list/get 与健康检查明确分开；目标 ID 原生解析；切换/安装/更新/卸载/reset 各自提案，进度/最终失败真实反馈。checkRootfsUpdate 不当成离线零副作用目录读。持久 operationId/恢复状态是新适配器要实现的能力，不能虚构 runtime 已提供。
- 约束：绑定终端、后台进程、会话和工作流的活动目标锁；审批后仍复核。切换默认发行版不得迁移已绑定运行。导入只能用受控归档文件；不开放任意宿主路径。卸载/reset 要明确数据影响，禁止静默清理默认发行版；网络/空间/校验失败不宣称已安装。缺少全程目标租约时拒绝相关写操作而不是仅审批前检查。
- 验收：固定一次性测试发行版，安装成功后列表/磁盘状态一致；失败不出现假成功；切换后旧任务仍指向旧目标；在用环境删除被阻止；仅对测试环境实际验证卸载/重置。大下载、真实安装均由用户操作，不在本轮执行。

### 紧随其后的未覆盖项（不是新增并行工作包）

P1 终端标签管理：真实 `TerminalSessionManager.handles/activeId`，`createSession(label, workingDirectory, distributionId, id)`、`switchTo(id)`、`closeSession(id)`、`closeAllSessions()`、`write(id, data)`、`paste(id, text)`、`interrupt(id)`；终端输入能执行命令，必须保留执行与目标权限，不是普通 UI 设置。关闭可能中断任务，要显式告知。

P1 插件包生命周期、P2 browser/git/diagnostics、其他 settings 与系统权限界面尚待专门适配。插件 TOOL 已有真实 `registeredToolSchemas(pluginId)`、`inspect(server)`、`call(server, toolId, PluginToolRequest)`，server 形式为 `plugin.<descriptor.id>`；实际调用走 `use_capability` / `CapabilityToolRouter`，不能绕过外层审批直接调用 registry。非 TOOL 扩展、未注册包、未知 schema 不伪装成可执行工具。包安装/卸载接口本轮未核实，故不猜测方法名或交付承诺。

## 3. 可执行策略配置与任意源码规则热改不是同一目标

1. **提示词/资料**：目录、load_rule、成员 persona、插件 context 改变模型所见文字，不自动改变执行器、授权或调度；模型说会遵守不构成策略强制执行。
2. **运行时策略配置**：有 schema、版本、所有权、持久化、原生确认、执行器读取与强制校验、审计和失效/回滚的配置才算可执行策略。现有配置白名单、团队审议定义、工作流定义只覆盖各自允许的结构；必须实际跑通冲突/拒绝/恢复才能证明生效。
3. **任意源码规则热改**：修改 Kotlin/Compose/Harness 源码通常要求构建、签名、安装/进程更新；写仓库文件不等于正在运行 APK 已换代码。插件动态扩展只在明确的插件协议和生命周期内生效，不能推导成任意原生代码热替换。

用户若要求任意源码规则热改，应继续作为真实目标登记：源码变更授权、编译/产物验证、受控安装/重启、兼容迁移、失败回滚，或另外设计受限热加载机制。不能擅自解释为“仅改提示词/白名单设置就已完成”，也不能把未设计的全量热替换承诺为已有功能。

## 4. 用户可操作的真实验收剧本（全部待执行）

### 准备与记录

记录源码 revision、APK version/build、设备/Android 版本、测试会话 ID、发行版 ID、模型档案 ID、提案 ID、执行 ID 和时间。用一次性会话/团队/工作流，不用生产对象。截图与日志脱敏，不导出 Key。自然语言请求由用户主动发送；本轮不自动调用任何付费模型。无付费测试选择：已配置的本地免费模型或工程提供的 Fake Provider 验收构建；若二者都无，则对话 E2E 标“阻塞：缺无付费 Provider”，不能用 UI 点击冒充对话成功。

| 场景 | 用户操作 | 真实通过条件 |
|---|---|---|
| 目录与执行区别 | 对话请求“只列模块，再检查一个已安装插件，不执行工具” | 目录明确 unsupported/registered/enabled_uninspected；无进程启动、无配置变更；未注册工具不得出现成功记录 |
| 开关与越权 | 设置关闭 AI 管理，要求改主题/建团队；开启后再提案；审批页拒绝一次 | 关闭阻止写；拒批不落库。只读目录是否可用按现有设计，不误报写权限 |
| 设置端到端 | 请求改主题，核对原生提案后批准，回设置页查看；再改回 | UI/持久值一致；重启保留；不是仅回复“已修改” |
| 提案失效 | 产生提案后手动改对应目标，再批准；再尝试重放/重启后批准旧 ID | 旧快照、已消费或进程失效提案拒绝；不得静默重新应用 |
| 持久团队 | 创建测试团队与成员，检查团队页；草稿修改审议规则并发布 | 真实成员 ID、版本与发布态一致；不是只返回角色提示词 |
| 独立团队 | 用户主动启动限定的无外部副作用任务，记 group/assignment ID，离开聊天页再到团队页看状态，最后 stop | 来源会话与独立任务可追溯；状态/消息真实持续；stop 后不再产生新任务。强杀 App 后是否恢复需另测，不能由“离开页面仍运行”推断 |
| member suggestion | 在新 APK 请求建议成员，查看来源/模型绑定，审批后核对团队页 | **在建待验收**；仅建议文字不算持久成员创建成功 |
| workflow 基线 | 原生 UI 建无副作用工作流，聊天读取并提案运行，用户批准，查看 executionId/status | accepted 与终态分开；失败历史可见；定义变更 CAS 冲突拒绝 |
| workflow target | 新实现就绪后，绑定一次性本地目标运行小型无害 shell 节点；审批前后切换目标；检查 SSH/不支持目标 | **在建待验收**；命令只在冻结目标执行，不静默回退本地；旧实现明确拒绝不算新目标完成 |
| 会话 WP1 | 新建测试会话、重命名，再从另一个管理会话删除 | 列表/持久化一致，运行与批准状态清干净；当前/越权会话删除拒绝 |
| 档案 WP2 | 列非 secret 档案、修改非敏感字段、分别切全局与会话模型 | 无 Key/header 泄露；绑定范围不串改；不自动发探测或推理请求 |
| 发行版 WP3 | 在用户明确同意下载后安装测试环境，切换、运行短命令，最后卸载测试环境 | 实际状态、进度、错误与 UI 一致；在用目标保护有效；默认环境与用户资料未受影响 |
| 插件工具实调 | inspect 实际已注册无副作用 TOOL，按 schema 调用；再用非法参数调用 | 真工具返回可核对结果；非法参数拒绝；未发生权限绕过。此项不证明插件包生命周期完成 |

停止/拒批/错误恢复与成功路径同等重要。每个场景保留用户操作、预期、实际、证据位置、通过/失败/阻塞五项；没有证据不得将状态从“待执行”改为“通过”。

## 5. 测试分层与继续推进门槛

开发期间只做定向验证，不启动全量测试，不改 CI/Gradle，不自动安装、不访问付费模型。

- L0 静态：只检查本模块 diff、schema、DTO 脱敏、依赖与管理入口连通；本轮只执行了源码定向读取和文档检查。
- L1 领域单测：Fake repository/runtime/provider，覆盖正反路径、拒批、过期、重放、并发快照变化、取消与部分失败；零 Key 环境可运行。
- L2 定向集成：分别核验 Provider schema → validator → ToolExecutor 路由 → 管理开关 → 原生审批回放 → 真实适配器（依赖用 fake）。不能只跑 service 单测就宣称装配可用。可复用现有 AppConfigServiceTest/AppConfigExecutionGateTest、TeamServiceTest、AppAdminServiceTest、AppModuleDirectoryTest，再补本包测试；本轮未运行这些测试。
- L3 用户真机：逐个执行上表，仅调用用户选定的本地/免费 Provider 或 Fake Provider；离线 UI 验证不替代对话链路。付费模型 E2E 仅在用户另行明确选择后执行。
- L4 全量回归：不在开发期间执行；最终集成阶段是否执行由用户约束与主流程决定，不作为中途停工借口。

当前退出结论：本文核对与工作包已交付；完整 AI 超管仍**未完成**。member suggestion、workflow target 继续在建；三个工作包及其后的终端/插件包/其他模块继续推进，最终用真实执行证据而非目录条目数验收。
