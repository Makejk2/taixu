# 管理实现收口：源码验收矩阵

范围：根仓 `/root/custom_taixu`；仅人工定向阅读路由、DI、解码器和具体 owner。未运行测试、编译、CI、安装或设备操作。下列“接线已核对”不代表运行时通过；所有 E2E 行为均**未验证**。本轮不自动重放任何提案/安装/工作流/团队副作用。

## 路由与真实落点

共同入口：`ToolExecutor.execute` → `ManagementActorGuard.withActor` / `aiAdminBoundary` → `routeAdminConfiguration`。`ToolBackendModule` 显式传入 workflow/model/session/rules/runtime/package 后端，team 单独路由。用户规则 guard 在普通工具副作用前执行，管理工具保留自己的更强原生门及规则修复入口。

| 模块 | 实际调用链 / owner | 合同与边界 | 验收状态 |
|---|---|---|---|
| model | `executeBoundModelManagement` → `ModelAdminService` → `createModelManagementBackend` / `ModelManagementRepository`；敏感编辑走 `executeNativeModelForm` | 非凭据 patch/default/current_session；native_form仅请求，不提交秘密；form_status用requestId | 接线/解码已核对；原生表单及CAS E2E未验证 |
| session | `SessionManagementBackend` → service/gate → `SessionManagementFactory` → persistence + Harness会话控制 | list支持offset/limit；create/rename/delete放operation对象；删除走stop/join/清理，不直删表 | 接线/解码已核对；删除部分失败/重启未验证 |
| rules | `UserRulesBackend` → repository；`UserRuleToolGuard` → `RuleEngine` | create/replace/delete及revision；routeToRoles是角色允许集合/暂停，不是调度 | 接线已核对；持久预算/审批恢复未验证 |
| distro | `RuntimeAdminBackend` → `RuntimeAdminAdapter` → LinuxRuntime事务 + journal | install/switch/update/uninstall/reset以action生成提案；install/update带digest；apply后读operationId状态 | 接线/解码已核对；真实rootfs生命周期未验证 |
| terminal | `RuntimeAdminBackend` → `TerminalAdminAdapter` → `TerminalSessionManager` | create用label/cwd/target；switch/close/input用id；closeAll不收ids；recover不同于只读recovery | 接线/解码已核对；终端关闭/恢复未验证 |
| packages | `executePackageManagement` → `PackageAdminBackend` → sync/web/process/skill事务owner链 | process安装更新可走已注册manifest；web/skill安装更新走NativePackageImportPanel → 原生文档controller → reviewed service，不是通用propose闭环 | owner链已核对；安装/取消/恢复未验证 |
| workflow | `AppAdminService` → `createBoundAppAdminBackend` → `AppAdminFactory.startBound` → `WorkflowRunManager` | 定义、源环境、模型、权限/租约冻结；通用start直接拒绝，必须startBound；受理不等于完成 | 接线已核对；节点审批与恢复未验证 |
| team | `TeamService` → `IndependentTeamStart`；`TeamSuggestions` + `IndependentMemberInbox` | 显式独立工具；成员建议adopt生成新的owner提案，不执行；受治理执行成员不得用启动批准替代方案审议 | 启动/建议/只读消息入口已核对；多成员真实协作未验证 |

## 本轮修正

- `ManagementToolSpec`：补session分页；移除实际解码不接收的顶层distro/ids；明确terminal target/closeAll；列全runtime action；补rules/native_form operation、reason适用范围、包sync常量和web/skill原生导入限制；声明role限制不自动派单。
- `AppModuleDirectory`：model/session不再标UI-only；补rules/distro/terminal及四类包管理目录；管理条目提供managementRoute；只有工具可见而无后端能力回调时标route_unverified，不能凭app_config存在宣称可执行。目录不触发连接、发现、审批或服务生命周期。
- 没有改动原生执行、身份、批准或重放逻辑。没有读取或输出凭据。

## 真实待收口项（最多8项；当前3项）

1. **role需求语义仍需冻结**：`RuleEngine.evaluate` 将 `actions.routeToRoles` 相交为 `allowedMemberRoles`；`UserRuleToolGuard.check` 对不匹配角色返回 `USER_RULE_ROLE_RESTRICTED`，并没有安排另一成员执行。若冻结需求仅“角色限制”，当前实现不构成缺陷；若要求“自动调度”，则未完成。最小改法：在可信任务派发前生成可审阅的成员路由建议，复用既有建议/消息与独立任务身份；不得在失败后自动重做原工具，也不可把规则门直接改成执行器。
2. **包通用propose允许不可执行操作**：`PackageAdminBackend.propose` 对web/skill允许install/update，但 `WebPackageTransactions.execute` 仅remove、`SkillMetadataTransactions.execute` 仅enable/disable/remove且后继owner为空；会在apply后拒绝，而非导入成功。已有原生文档导入UI/controller/reviewed service可复用。本轮文案已明确限制；最小后续改法是在propose即返回稳定native-import-required（在生成可批准提案前），不要伪造下载URL/文档句柄或重复安装。非原生对话“完成导入”的闭环不能宣称通过。
3. **受审议执行成员的团队启动限制**：`IndependentTeamStart.validate` 对任意有executionRules的成员直接拒绝；`TeamService.startMembers` 默认选全部已发布成员。因此包含受控executor的团队默认start会失败，不能据此声称“全角色自动启动已完成”。最小改法取决于冻结需求：保留显式限制并指导先启动非受控成员，再复用既有方案审议/消息流程；若要求受控成员先启动后在工具边界等待审议，需要单独实现并验收，不可删除校验以绕过批准。

目录后端能力回调当前未绑定并非本轮新增业务故障：以route_unverified诚实呈现，避免替实际调用背书。缺少运行证据是统一验收缺口，不人为扩列成每模块重复blocker。
