package top.wkbin.taixu.core.tools

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/** Cancel the socket as well as the coroutine, including while blocked in a body read. */
internal suspend fun <T> Call.consumeRemotePluginResponse(block: suspend (Response) -> T): T = coroutineScope {
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@consumeRemotePluginResponse.cancel() }
    }
    try { execute().use { block(it) } }
    catch (error: IOException) {
        // Socket cancellation can interrupt execute/read after the caller's active check.
        currentCoroutineContext().ensureActive()
        throw error
    }
    finally { cancellation.cancel() }
}
