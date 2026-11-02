# 对话受控配置（首期）

这是有限配置能力，不是 Android Root，也不宣称已实现全部模块的对话管理。

## 支持范围

`app_config` 支持主题、动态取色、应用字体比例、终端字号与触觉反馈；真实写入现有 DataStore，界面继续观察原有 Flow。

- `list/get`：查询白名单与当前配置。
- `propose(key, value, reason, ttl_seconds)`：说明原因，生成会话绑定的不可变 changeId；不写配置、不自动授权。
- `apply(changeId)`：首次执行必须在原生审批卡片确认，显示原因、影响、旧值、新值、授权期限。FULL_ACCESS 不能跳过；后台 Lane 必须交回主会话。
- `grants/revoke`：查看或撤销本会话的单键授权，TTL 60–1800 秒；进程重启失效。
- `rollback(changeId, reason)`：仅从已成功变更的快照生成恢复提案，再由 apply 走正常审批。不能输入任意恢复值。

授权绑定会话与单个配置键；后续低风险写入可在已获批 TTL 内执行。PLAN 模式仅允许 list/get/grants。未知或已删除会话拒绝执行。

权限、审批模式、运行模式等只能提议并提示用户手动操作，没有对应的配置写入口。模型不能以 userConfirmed、approvedNative 等参数伪造批准。

## 一致性与审计

写前记录持久审计，随后 DataStore compare-and-set 检查旧值，防止覆盖用户手动修改。写后记录完成审计；若完成审计失败，明确返回“已修改但完成审计失败”，保留 changeId/快照并撤销本键宽限授权，禁止自动重试。取消不应抹掉已提交事实。

提案、授权与回滚快照在进程内有界存储，不是跨重启的配置历史。原有参数摘要、审批期限和 operation 归属检查由原生审批恢复链复用。

## 验证

CI 执行 `./gradlew testDebugUnitTest`，再构建 TaiXuDev，并校验 top.wkbin.taixu.dev / TaiXuDev。纯 Kotlin 单测不替代 Android 整体构建。

## 未完成范围

尚未新增统一超管 UI 开关、持久团队创建/发布接口、代码级规则编辑器，也未将全部功能模块暴露给对话。该首期是可审查的受控配置入口，不能视作完整超管系统。
