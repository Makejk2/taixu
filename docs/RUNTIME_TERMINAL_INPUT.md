# 原生终端输入 owner 边界

## 实现

`TerminalSessionManager` 在会话发布前一次性绑定 `TerminalSession.InputOwner`。
本项目 Termux `TerminalSession` 与预编译 `TerminalView` 都是 final；View 的 client
回调不能覆盖 ACTION_MULTIPLE、autofill、鼠标/滚轮报告及全部 paste。因此不是靠
禁用 View、覆盖少量 key 回调或检查后再直接 write 来保护。

`feature/terminal/vendor-input-owner/TerminalSession.java` 来自仓库现有
`termux-terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
（Termux v0.118.0 / GPL-3.0，许可证见该模块），仅新增一次性 owner 绑定及 write 入口保护。
feature 构建脚本为现有 emulator 项目注册 `prepareInputOwnedEmulator` Sync：复制现有
Java 源，排除原 TerminalSession，加入替换类；源目录使用任务输出并由 preBuild 依赖。
不新增下载、不替换 JNI、不打包第二套 emulator、不修改 SDK。

同步边界为 `stateLock` 下检查：无 adminReserved、无 closingAll、资源 identity 相同、
已 published、未 closing、session identity 相同；然后 generation++，同步执行原始
ByteQueue.write。拒绝时不入队、不递增 generation，旧 View 不能借同 id 新会话输入。
owner 从未绑定则 fail-closed；关闭后保留 owner，身份/closing 检查永久拒绝旧对象。
不存在 suspend lambda、runBlocking 或 monitor 跨 suspend。原 ByteQueue 的有界阻塞和
背压保持原样，owner monitor 可能等待队列腾出空间，writer drain 不取得 owner monitor。

## 显式调用与底层兜底

- Host IME commit/finishComposing/delete → Bridge → ViewModel.writeNative → manager。
- Bridge 快捷键、剪贴板 → ViewModel.writeNative → manager；按传入 session 身份查找，
  不读取当前 active id 来重定向旧输入。
- Host 拥有焦点时把物理 key down/up/pre-IME/multiple 交还原 TerminalView；未重写
  Unicode/dead-key/Ctrl/Alt/application cursor 的编码算法。
- TerminalView 自带 InputConnection、onCodePoint、硬件键盘、ACTION_MULTIPLE、autofill、
  中键粘贴、selection paste、滚轮/触摸 mouse report 最终都经过 Session.write guard。
- TerminalOutput.write(String)、Session.writeCodePoint、Emulator.paste / sendMouseEvent
  以及设备查询回复都归并到 byte-array write；输出读取、绘制、选取与滚动逻辑未改。
- 原 manager.write/inputApproved 的 generation 消耗保留（可以递增两次，generation
  是不透明快照版本而非字符计数），其真正入队同样受 session guard 保护。

## 边界与剩余事项

1. 这是“接受/入队”的线性化边界，不是同步 PTY flush：事务前已接受的字节可能在
   后续后台 writer 才发送；不能声称 reservation 清除了旧队列或撤销已发送字节。
2. 自动终端查询回复也视作输入，会使旧审批失效；reservation 期间这些回复被拒绝，
   不排队重播至新会话。它们不是用户输入旁路。
3. backend.write 是内部 drain，非 UI 入口；直接在别处持有 backend/PTY 写入不在此
   UI owner 范围。已检查的 TerminalView 公共输入路径未发现绕过 session.write 的入口。
   同包恶意访问队列、反射、直接 native fd 写入不属于此资源边界的威胁模型。
4. 替换源目前由 feature 构建脚本配置 emulator 项目，当前仓库未开启 configure-on-demand。
   未来启用按需配置/isolated projects，或只配置 runtime 而不配置 feature 的独立构建，
   应将这一源替换规则迁移到 emulator 模块自身（本任务写入权限不含该模块）。
5. 没有运行编译、测试、CI 或设备验证；没有修改 SDK 或提交 Git。Gradle 接线和所有
   中文组合输入/硬件键盘/选取/滚动及事务期间拒绝行为仍需后续允许验证时验收。

## 静态检查

已读取实际 AAR 反编译出的 TerminalView 检查写入口，临时反编译文件已删除。
已执行限定路径 `git diff --check`，并检查 vendor diff 仅修改 write 边界。
