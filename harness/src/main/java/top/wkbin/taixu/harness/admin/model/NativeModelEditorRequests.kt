package top.wkbin.taixu.harness.admin.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Navigation metadata only. Native view-local credentials never enter this coordinator. */
class NativeModelEditorRequests {
    private val mutable = MutableStateFlow<ModelNativeFormRequest?>(null)
    val current = mutable.asStateFlow()
    fun openFromNative(request: ModelNativeFormRequest) {
        check(mutable.value == null || mutable.value?.requestId == request.requestId)
        mutable.value = request
    }
    fun clearFromNative(id: String) { if (mutable.value?.requestId == id) mutable.value = null }
}
