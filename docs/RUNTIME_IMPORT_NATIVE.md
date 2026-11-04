# 原生发行版导入（release）

## 集成（主会话拥有 DI / Settings 写入）

面板精确签名：

```kotlin
@Composable
fun NativeRuntimeImportPanel(
    controller: NativeRuntimeImportController = koinInject(),
)
```

`AgentSettingsScreen` 的设置列表增加 `item { NativeRuntimeImportPanel() }`。
不得用 `BuildConfig.DEBUG` 包裹；面板仅走原生 ActivityResult `OpenDocument`。

`ToolBackendModule` 增加以下 singleton；不要注册为工具/router，scope 必须为应用生命周期：

```kotlin
single {
    top.wkbin.taixu.runtime.admin.PrivateNativeImportStore(
        java.io.File(get<android.content.Context>().filesDir, "management-native-runtime-archives"))
}
single {
    top.wkbin.taixu.harness.admin.runtime.NativeRuntimeImportController(
        resolver = get<android.content.Context>().contentResolver,
        archives = get(), components = get(), sessions = get(), ownership = get(),
        bots = get(), assignments = get(), preferences = get(),
        scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO))
}
```

构造器精确依赖类型：`ContentResolver, PrivateNativeImportStore, RuntimeAdminComponents,
HarnessSessionRepository, NativeSessionOwnership, PersonaBotRepository, BotAssignmentRepository,
AiAdminPreferences, CoroutineScope`（依次）。不要额外创建假的 `ModelAdminActor`，也不要构造
假的 main operation；本入口使用 native-UI 专属 typed gate，来源是当前用户原生会话选择。

## 状态与授权

1. 打开 picker 前读取 source session、prefs enabled/epoch、build mode、native ownership、
   排除 bot/assignment/group/workflow/subagent，并冻结源/目标/工作区/发行版 generation 与摘要。
2. OpenDocument 仅接收 `content:` URI。IO dispatcher 流式私有捕获（128 KiB buffer，最多 16 GiB），
   完整 SHA-256、fsync、只读重命名。不接受 AI 路径/URI，不保留 provider permission。
3. 捕获后再次核对 source authorization、prefs epoch 和完整 owner snapshot；展示独立原生审阅卡。
   选择文件不是完成，也不会自动导入。
4. 用户点击明确的「确认此摘要与目标并导入」，typed native gate CAS 一次性消费。
   确认和应用任务内都校验授权。应用 job 持有 `AiAdminPreferences.withManagementSnapshot`
   与已有写操作相同的偏好关闭边界，重新核对工作区/目标 snapshot，再调用现有
   `LinuxRuntime.applyManagement`：runtime owner 在 lifecycle mutex 内最终 CAS。
5. 返回 operationId 后显示 queued/running；仅 finished 表示成功。
   snapshot_changed、failed_or_partial、unknown_partial 不自动重试。terminal 状态在 handle.close
   后发布；清理失败显式 cleanupPending。原操作日志仍保留、不自动重放。

`NativeManagementQueue` 与 `NativePackageImportPanel` 未修改。因为通用 queue 的枚举没有
runtime import、且其 APPLIED 不能表达异步 operation，此实现复用其「私有处理器 + 元数据流 +
原生确认一次性消费」模式，用独立 runtime review 流，避免冒充 PLUGIN_INSTALL 或过早 APPLIED。

## 归档 owner / 释放

* 捕获阶段由 controller 拥有；捕获失败/取消/审阅注册失败立即释放，包含 dispatcher prompt cancellation。
* 待确认由 native gate 拥有；取消、2 分钟到期、应用 scope 取消均释放。
* gate 成功 dispatch 后由 `RuntimeAdminAdapter` 应用 job 独占，finally 关闭；ATOMIC start
  确保 scope cancellation race 不跳过 finally。重复 close 安全；正在解包时延迟删除至退出。
* 完整归档 SHA-256 在 owner CAS 内、真实解包前再次核验。私有 store 不导出路径。
* 应用进程重启后，singleton store 初始化回收上一进程 owner-* 目录；同进程活 store 不删除。
  因进程终止丢失的 pending review 不重放。捕获或删除失败的 orphan 最迟在下次 store 初始化回收。
* pending 清理失败保留 gate cleanup owner，可点击重试释放（不是重试导入）。
* 导入的既有安全解封、平台/格式校验保持由 runtime/rootfs installer 实现，无 unsupported stub。

## 验证范围 / 待办

本轮仅静态阅读与源文件修改；遵照任务约束未运行 tests、compile、CI、SDK、真实 import，未提交。
DI 与设置入口不在本子任务写入范围，必须按上文集成后才能从 release UI 实际触达。
需用户之后验证：选择/取消、epoch 翻转、切换目标/工作区、重复确认、归档 tamper、导入异步状态、
进程死亡 orphan 回收。审阅卡是进程内状态；操作日志落盘但本面板不恢复进程死亡前审阅卡。
