# Source-bound 动态扩图准入

## 已落盘（未运行测试/编译）

- 保留原始 `WorkflowExecutionBinding` / source authorization；不修改原授权 hash。
- 使用 `WorkflowExpansions.append/validateHistory` 的追加约束、深度与容量限制。
- 原生审批显示完整新增节点/配置/边、冻结目标、父图/新图 SHA-256、插件真实内容 stamps 和本次摘要。
- 随机 exact reviewToken 与原生 broker 绑定，等待暂停 `WorkflowWorkToken`；拒绝、错 token、取消均不准入。
- `REQUESTED -> APPROVED` 持久记录并回读后，`WorkflowJournalRecorder.settled` 才写入 patch 与 `WorkflowExpansionAdmission`；sink 返回前调度前沿不释放新节点。并发审批期间父图变化则失败关闭，需重新确认。
- 恢复与每节点操作：校验 base binding，重放完整 journal，核验每个扩图的 parent/new hash、插件键集合、摘要及对应持久 APPROVED 行。最终 state.definition 必须等于重放图，不作为授权来源。
- 旧无 source 的动态扩图仍走原 exact-token 与持久 journal 路径，不强制补新凭据；source-bound 旧扩图缺凭据则拒绝恢复。

## 未完成：主会话必须补的范围外接线

当前缺少数据库读取与 DI 接线会明确 `NATIVE_REVIEW_LOOKUP_UNAVAILABLE` 失败关闭；不能称生产闭环完成。

1. `core/database/.../WorkflowPersistence.kt`
   - `WorkflowDao` 增加：
     ```kotlin
     @Query("SELECT * FROM workflow_native_reviews WHERE reviewId=:id")
     suspend fun nativeReview(id: String): WorkflowNativeReviewEvent?
     ```
   - `WorkflowRepository` 增加同签名（默认实现若为兼容 fake 应抛错，不能返回假批准）。
   - Room repository 实现：`override suspend fun nativeReview(id: String) = dao.nativeReview(id)`。
   - 不改表结构；已有 reviewId 主键及 REQUESTED 单次 CAS 保持不变。
2. `harness/.../di/harness/KoinModule.kt` 的 `createWorkflowExecutionLeases` 调用传入：
   ```kotlin
   readReview = { id -> workflowRepository.nativeReview(id) },
   plugins = get<HarnessPluginRegistry>(),
   ```
   `workflowRepository` 在 provider 外层用 `val workflowRepository = get<WorkflowRepository>()` 捕获，确保与传入 factory 的 repository 同实例。
   Factory 已增加上述参数，并用真实 `HarnessPluginRegistry.nodeIdentity(plugin, extension)` 捕获 stamps；不是空回调。
3. `harness/.../plugins/PluginNodeExecutor.kt`
   - 原 `val binding = currentCoroutineContext()[WorkflowExecutionContext]?.binding` 改为保留 `lease = currentCoroutineContext()[WorkflowExecutionContext]`。
   - 内容检查改用 `lease?.pluginStamp(node.id) != identity`，不再只读原 `binding.pluginStamps`。此方法返回经原授权/批准链核验的 stamps，不扩写 baseBinding/source。
   - 保留既有 schema、实时安装身份比对与逐动作原生审批。

## 后续验收（本任务按要求未执行）

- 基图启动 -> source 产提案 -> exact-token 原生批准 -> review APPROVED 回读 -> settled sink -> 新节点执行。
- 错 token、拒绝、取消、REQUESTED/APPROVED DB 写失败、回读失败、settled sink 失败：新节点零执行。
- 审批期间并发扩图：父 hash 不匹配者失败，不能借用另一图的批准。
- 恢复时篡改 base/patch/definition/hash/stamp/reviewId、缺审批行、非 APPROVED、跨 execution/node 复用：拒绝。
- 新增插件 stamps 来自 registry；审批后插件更新/卸载则执行器拒绝；恢复不重新捕获或重写已批准 stamps。
- 多层扩图均独立批准；旧无 source 路径兼容；原预算计时与资源收尾规则不变。
