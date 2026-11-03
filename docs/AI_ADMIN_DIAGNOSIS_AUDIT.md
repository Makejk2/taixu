# 超管迭代诊断修改审计（2026-11）

| 修改 | 原诊断 | 已验证证据 | 处置 |
| --- | --- | --- | --- |
| HTTP stop 锁外 join 与全局二次清扫 | 缺少 join 必然导致 BindException | 未证明因果；源码可证明旧 stop 会清理新 start 的连接。目录相同不能排除间接影响；CI 通过也不能证明根因。 | 撤回该补丁，精确恢复原HTTP文件；不扩大到原版已有竞态。 |
| AiAdminPreferences 显式 Unit 返回 | DataStore内部返回类型泄漏 | feature settings 编译日志明确 Preferences 不可访问 | 保留修复，不增加无关依赖。 |
| ToolExecutor json 导入合并 | 缺少导入及401行超限 | 云端编译/architectureCheck明确报错 | 保留400行修复。 |
| 模块schema断言 | 提供真实schema后仍断言unknown | JUnit报告expected unknown actual known | 保留known/缺快照unknown双契约。 |

HTTP原版本身的全局生命周期仍存在可独立改进之处，但这不属于已确认的本需求根因；不得为测试变绿任意修改。保留所有socket测试，不屏蔽失败。

另一个实质性缺口：之前 team start 仅投递群聊，不能保证独立团队。现改为经可信主用户原生批准后确定受理各成员根任务，默认只读写租约，返回真实assignment/session ID；测试与最终CI必须另行核实。
