package top.wkbin.taixu.harness.admin

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Constructed by native tool dispatch from its actual operation, never from JSON or main lookup. */
internal class NativeManagementActor(val sessionId: String,val operationId: String,val callId: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeManagementActor>
}
