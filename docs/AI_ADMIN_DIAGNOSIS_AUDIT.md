# 超管迭代诊断修改审计（2026-11）

| 修改 | 原诊断 | 已验证证据 | 处置 |
| --- | --- | --- | --- |
| HTTP stop 锁外 join 与全局二次清扫 | 缺少 join 必然导致 BindException | 未证明因果；源码可证明旧 stop 会清理新 start 的连接。目录相同不能排除间接影响；CI 通过也不能证明根因。 | 撤回该补丁，精确恢复原HTTP文件；不扩大到原版已有竞态。 |
| AiAdminPreferences 显式 Unit 返回 | DataStore内部返回类型泄漏 | feature settings 编译日志明确 Preferences 不可访问 | 保留修复，不增加无关依赖。 |
| ToolExecutor json 导入合并 | 缺少导入及401行超限 | 云端编译/architectureCheck明确报错 | 保留400行修复。 |
| 模块schema断言 | 提供真实schema后仍断言unknown | JUnit报告expected unknown actual known | 保留known/缺快照unknown双契约。 |

HTTP原版本身的全局生命周期仍存在可独立改进之处，但这不属于已确认的本需求根因；不得为测试变绿任意修改。保留所有socket测试，不屏蔽失败。

另一个实质性缺口：之前 team start 仅投递群聊，不能保证独立团队。现改为经可信主用户原生批准后确定受理各成员根任务，默认只读写租约，返回真实assignment/session ID；测试与最终CI必须另行核实。

## 独立实验后续（非原BindException根因结论）

原版已在Java17真实socket隔离实验中复现旧accept在重启后忙循环；新代次隔离State修复的是这一已证缺陷，不是恢复旧join补丁。监听器、已接受连接和owned executor按代次分离，停止只清捕获代，外部executor不关闭。新增5项确定性生命周期测试，本次主会话重复3轮共30项协议/生命周期测试通过；代理先前20轮有1次post-stop重绑失败，因此BindException仍未确认彻底解决，不能凭一轮绿下结论。

完整CI新增--continue仅为收集其他模块的回归，不忽略失败退出码，不删除、跳过或重试单测。

团队同一次启动的rootWorkId已统一，成员stageId/workItemId仍区分；审批快照被观测到变化即永久消费提案，恢复旧值不能复用旧批准。

## 6860a5bb内核现场及本代accept屏障

该轮3836项只有HTTP重启一项BindException，失败当场tcp6存在目标端口LISTEN(state0A)，并非仅TIME_WAIT。因此不再依据无join猜因，也不恢复旧全局二次清扫。新增HttpAcceptBarrier只记录本代原生accept调用的进入/返回；stop在本代listener.close及本代连接清理后，锁外等待原生accept返回，不等待handler/executor线程；超时明确失败，中断恢复flag并报错。retire阻止旧代再次进入accept。

新增3项测试覆盖stop不占服务锁、有限等待显式失败与中断、自direct handler停止不selfjoin。本地Java17真实Socket协议5项+生命周期8项，重复5轮65项通过；Java25云端BindException是否解除仍须本轮CI验证，不以本地全绿冒充最终结论。
