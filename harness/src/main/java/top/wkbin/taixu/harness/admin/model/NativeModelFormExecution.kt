package top.wkbin.taixu.harness.admin.model

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.wkbin.taixu.harness.*

/** Tool can request or inspect a native form, never submit its values or approve its write. */
suspend fun executeNativeModelForm(bridge: ModelNativeFormBridge?, call: ToolCall, source: String,
    now: Long): ToolResult {
    fun result(ok: Boolean, text: String)=ToolResult(UUID.randomUUID().toString(),now,call.id,ok,text)
    return try {
        val native=requireNotNull(bridge)
        val action=call.args.getValue("action").jsonPrimitive.content
        val status=when(action) {
            "native_form" -> {
                require(call.args.keys.all { it in setOf("module","action","operation","modelId") })
                val operation=when(call.args.getValue("operation").jsonPrimitive.content) {
                    "create" -> ModelNativeFormOperation.CREATE
                    "delete" -> ModelNativeFormOperation.DELETE
                    "change_credentials" -> ModelNativeFormOperation.CHANGE_CREDENTIALS
                    else -> error("MODEL_FORM_OPERATION_UNKNOWN")
                }
                native.request(source,operation,call.args["modelId"]?.jsonPrimitive?.content)
            }
            "form_status" -> {
                require(call.args.keys == setOf("module","action","requestId"))
                native.status(source,call.args.getValue("requestId").jsonPrimitive.content)
            }
            else -> error("MODEL_FORM_ACTION_UNKNOWN")
        }
        result(true,buildJsonObject {
            put("requestId",status.requestId);put("state",status.state.name)
            put("notice","打开原生表单不代表完成；凭据仅由用户在原生页填写，AI不能提交或自批。")
        }.toString())
    } catch(cancelled: CancellationException) { throw cancelled }
    catch(_: Exception) { result(false,"MODEL_NATIVE_FORM_REJECTED；未返回凭据或私有错误") }
}
