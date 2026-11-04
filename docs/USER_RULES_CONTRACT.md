# 用户规则 v1：存储、管理与执行接入契约

## 权威与兼容性

这是新增 app_config `module=rules`，不改变既有配置语义。用户规则永远是低权威的**附加限制**，不能覆盖 actorGate、nativeApproval、secret、Lane、团队审批或执行边界；无 allow、跳过审批或权限提升动作。内置安全规则仍由宿主代码拥有，不进入自定义表，`custom:` 以外 ID 不可管理。本模块 list/get 只返回自定义规则，内置目录由既有规则目录继续提供，不能误报为已合并内置列表。

`content` 是严格 JSON 的声明式规则，不是自然语言提示词，也不是任意 Kotlin/JavaScript/shell 执行器。文本提示词不能创建执行动作。需要任意源码热补时，必须另走源代码变更、原生审核、构建、验证与安装链；本接口不提供热加载源码。

## 精确管理协议

入口 `UserRulesBackend.execute(RuleManagementActor, JsonObject): JsonElement`；actor 由可信主 Lane operation 上下文构建，不得从工具 JSON 接收。未知顶层字段、未知规则字段与未知策略字段拒绝。失败抛出异常，由 app_config router 映射为失败 ToolResult，不能包装成成功。

- `{module:rules,action:list}`：自定义规则列表，priority DESC/id ASC。
- `{module:rules,action:get,id}`：规则或 null。
- `{module:rules,action:propose,operation:create,id,rule}`。
- `{module:rules,action:propose,operation:replace,id,expectedRevision,rule}`。
- `{module:rules,action:propose,operation:delete,id,expectedRevision}`。
- `{module:rules,action:apply,proposalId}`：不可变提案一次消费、可信原生预览批准后执行。

rule 必须精确包含 enabled(Boolean)、scope(global/session/workspace)、scopeId(global 时 null，其他非空精确标识)、priority(-1000..1000)、content(String)。创建 revision=1；replace 更新全部允许字段并在 SQL 内 revision+1；delete/replace 使用 expectedRevision 的单 SQL CAS。id、revision 不接受在 rule 里覆写。相同 ID 创建冲突，不做覆盖；过期 revision 必须重新读取与提案。apply 重放报已消费；取消、拒绝、写入结果未知都不能盲重放。后台成员不得自批；管理读取也须可信主 actor。

`RuleManagementAuthority.requireNativeMainActor` 每次验证真实运行模式、身份、操作归属；`review` 对 frozen proposal 做原生审批，不能使用模型布尔返回值。工厂还须接共享管理 epoch：原生开关变化调用 setManagementEnabled；撤销调用 disableManagement 清空提案。review 期间不持内部 mutex，批准后锁内重核 epoch、actor 并 CAS 写入。

## 可执行内容

```json
{"version":1,"condition":{"tools":["base"],"taskKinds":["maintenance"],"memberRoles":["implementer"]},"actions":{"requireNativeReview":true,"maxToolCalls":5,"routeToRoles":["reviewer"]}}
```

condition 的非空集合彼此 AND，集合内部 OR；空集合为通配；名称精确匹配，不执行正则。工具名使用 HarnessApiMapper 对外规范名称；taskKind/memberRole/session/workspace/计数由可信上下文提供，不得接受调用者自报。

动作：block 拒绝；requireNativeReview 要求额外原生审核；pause 暂停；maxToolCalls 为执行任务生命周期内允许的调用总预算，已消费数 >= 阈值则暂停；routeToRoles 限定可运行角色（当前角色不匹配即暂停，由可信调度器重新路由，不自动授予角色权限）。多条规则 deny/pause/review 取 OR，预算取最小，角色集合取交集；priority 只决定稳定诊断顺序，不能让高优先级允许覆盖拒绝。空角色交集暂停。规则存储/解析错误 fail closed，取消正常传播。引擎不维护计数：执行宿主必须以已有执行锁/租约原子预留预算并避免并发超额，不得仅传易竞态快照。

## 主集成 owner 必须接的真实 hook（本包未越界修改）

1. Room AppDatabase v88：entities 增加 `UserManagementRule::class`，添加 `abstract fun userRuleDao(): UserRuleDao`；87→88 migration 执行 `UserRuleSchema.CREATE_TABLE`，不要改 v87；新装 schema 同实体生成。DDL 全常量在 core/database/UserManagementRule.kt，无索引、无默认值、PK(id)。集中阶段生成/核验 KSP schema。
2. DI：`UserRuleRepository = RoomUserRuleRepository(database.userRuleDao())`；`RuleEngine(repository)`；`UserRulesBackend(repository, nativeAuthority)`。harness 只依赖 repository，不注入 DAO。接口临时同实体文件声明是租约边界内的持久化公开面。
3. app_config rules router/factory：转交上述 execute，复用可信 main operation actor gate、原生审核桥、共享 epoch 与模块开关；不能把模型传的 sessionId/operationId 当 actor。只读模式不得 apply/propose；禁止后门自动 enable。
4. `ToolExecutor.executeBound` 当前 `governanceGuard?.blockReason` 处：与既有团队门禁串联，**每次调用、包括 bypassApproval 和 replay**，在 executionBoundary/任何工具副作用前 `RuleEngine.evaluate(trustedContext)`。blocked/failure 返回失败；paused 或不满足角色返回暂停/路由结果，不继续执行。不能用目录展示替代本 hook。
5. requireNativeReview 不等于单纯将 bypassApproval=false：必须进入可信 native review 桥并绑定准确 toolCall/operation/规则 revision 快照；模式为 FULL_ACCESS 也不得跳过；后台不能弹审批时返回暂停待主 Lane。批准后重核规则 revision/权限/epoch；新规则失效旧批准。应用规则审批不授予其他 actor/Lane/secret 权限。
6. budget：由任务/执行所属服务持有计数与预留锁；暂停与路由由该服务落实。规则引擎仅计算强类型决策，不伪造已执行的调度、副作用或原生审批。

## 当前交付状态与集中验证

已提供生产 Room DAO/Repository adapter、CAS DDL、严格 codec、deterministic RuleEngine/TypedRuleDecision 与真实持久管理 backend；不是 fake store。AppDatabase、DI、公共 router、ToolExecutor hook、可信预算计数/角色调度由主 owner 合并，未接这些点前**不能宣称运行中生效**。内置安全规则不可通过本后端修改，已有宿主安全门继续独立生效。

按阶段要求，本包未运行 tests/compile/Gradle/CI、未安装、未提交。集中验收需覆盖 CAS 并发、升级/新装、未知字段、提案消费/epoch、后台 actor 拒绝、FULL_ACCESS 强制原生审阅、预算并发及各宿主执行入口的 fail closed。
