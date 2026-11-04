package top.wkbin.taixu.harness.admin

/** Discoverable routing contract. Each module validates its own exact field set again. */
object ManagementToolSpec {
    const val description = "受控原生管理，先查询再提案（reason仅workflow/包及外观配置支持），写入必须用户开启超管并逐次原生批准，FULL_ACCESS不豁免。module省略沿用外观配置list/get/propose/apply；workflow管理定义/运行；session查询创建改名删除；model非凭据patch/global_default/current_session及native_form由用户原生表单填写敏感内容；rules管理持久执行规则；distro与terminal管理生命周期；plugin/process_plugin/web_plugin/skill管理包。runtime以动作action直接创建提案再apply；team是独立工具。不同module参数不可混用。未知副作用禁止自动重放。proposalId/changeId/requestId/operationId意义不同，受理不是完成，按status/state核对。不得传凭据、任意URI或自证批准。modules/inspect_module仅目录，不等于所有操作已绑定。"
    const val schema = """{
      "type":"object","additionalProperties":false,
      "properties":{
        "module":{"type":"string","description":"管理路由workflow/session/model/rules/distro/terminal/plugin/process_plugin/web_plugin/skill；省略=外观配置。inspect_module时为目录ID，modules不可带module。"},
        "action":{"type":"string","description":"workflow:list/get/status/propose/apply；session/rules:list/get/propose/apply；model:list/get/propose/apply/native_form/form_status；distro:list/health/updatecheck/status/install/switch/update/uninstall/reset/apply；terminal:list/recovery/create/switch/close/closeAll/recover/input/apply；包:list/status/propose/cancel/apply；外观配置:list/get/propose/apply/grants/revoke/rollback；目录:modules/inspect_module"},
        "operation":{"oneOf":[{"type":"object"},{"type":"string"}],"description":"workflow对象:{operation:save,definition}|{operation:delete,workflowId}|{operation:run,workflowId,workspace,variables:字符串映射且必填}|{operation:stop,executionId}；session对象:{operation:create,title}|{operation:rename,sessionId,title}|{operation:delete,sessionId}。model propose字符串patch/global_default/current_session；native_form字符串create/delete/change_credentials。rules字符串create/replace/delete。包字符串install/update/remove/sync；仅skill支持enable/disable。process_plugin安装更新使用已注册manifest；web_plugin/skill安装更新须原生文档导入，不能仅凭此propose完成；sync固定id=native-sync,target=native-config"},
        "reason":{"type":"string","maxLength":1000,"description":"仅workflow/包propose及外观配置propose/rollback；其余模块拒绝。"},
        "proposalId":{"type":"string"},"changeId":{"type":"string"},"requestId":{"type":"string"},"operationId":{"type":"string"},
        "id":{"type":"string"},"target":{"type":"string"},"sessionId":{"type":"string"},"modelId":{"type":"string"},"workflowId":{"type":"string"},"executionId":{"type":"string"},
        "offset":{"type":"integer","minimum":0,"description":"仅session list，默认0。"},"limit":{"type":"integer","minimum":1,"maximum":100,"description":"仅session list，默认50。"},
        "patch":{"type":"object","description":"模型非凭据字段temperature/maxTokens/topP/reasoningMode/reasoningEffort，不接受provider/endpoint/key/header"},
        "rule":{"type":"object","description":"仅rules create/replace；必须恰含enabled:boolean,scope:string,scopeId:string或null,priority:integer,content:string；delete不接受rule。routeToRoles仅限制/暂停当前角色，不自动派单。不能修改内置安全规则或提升权限"},
        "expectedRevision":{"type":"integer","minimum":1,"description":"rules replace/delete必填，create省略。"},"deleteData":{"type":"boolean"},"digest":{"type":"string"},
        "label":{"type":"string","description":"terminal create必填，另需cwd和target（不是distro）。"},"cwd":{"type":"string"},"text":{"type":"string","description":"terminal input另需id；closeAll不接受ids。"},
        "key":{"type":"string"},"value":{"type":"string"},"ttl_seconds":{"type":"integer","minimum":60,"maximum":1800}
      },"required":["action"]
    }"""
}
