package top.wkbin.taixu.core.tools.admin

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** Only the native picker controller gets this store. There is deliberately no tool action to
 * manufacture candidates from model paths/URIs. Approval binds the exact private staged bytes. */
class NativePackageCandidateStore(private val privateDirectory: File) {
    data class Metadata(val token: String, val owner: String, val module: String,
        val sha256: String, val size: Long, val expiresAt: Long)
    private data class Entry(val metadata: Metadata, val file: File)
    private val entries = ConcurrentHashMap<String,Entry>()
    private val readers = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    init { check(privateDirectory.isDirectory || privateDirectory.mkdirs()) }

    suspend fun stage(owner: String, module: String, maximumBytes: Long,
        openNativeDocument: () -> InputStream): Metadata = withContext(Dispatchers.IO) {
        require(owner.isNotBlank() && module in setOf("process_plugin","web_plugin","skill"))
        require(maximumBytes in 1..(8L * 1024 * 1024 * 1024))
        val token=UUID.randomUUID().toString()
        val file=File(privateDirectory,"$token.pkg")
        val hash=MessageDigest.getInstance("SHA-256")
        var size=0L
        try {
            openNativeDocument().use { input -> file.outputStream().use { output ->
                val buffer=ByteArray(65536)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val n=input.read(buffer);if(n<0) break
                    size+=n;require(size<=maximumBytes) { "PACKAGE_DOCUMENT_TOO_LARGE" }
                    output.write(buffer,0,n);hash.update(buffer,0,n)
                }
                output.fd.sync()
            } }
            check(file.setReadOnly()) { "PACKAGE_STAGING_NOT_READ_ONLY" }
            val metadata=Metadata(token,owner,module,
                hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) },size,
                System.currentTimeMillis()+10*60_000)
            readers[token] = kotlinx.coroutines.sync.Mutex()
            check(entries.putIfAbsent(token,Entry(metadata,file))==null)
            metadata
        } catch(failure: Throwable) { file.delete();throw failure }
    }

    /** A short-lived native inspection view. It cannot mutate installed state or mark approval. */
    suspend fun <T> inspect(owner: String, token: String,
        read: suspend (Metadata, InputStream) -> T): T = withContext(Dispatchers.IO) {
        val mutex = requireNotNull(readers[token]) { "NATIVE_PACKAGE_CANDIDATE_UNAVAILABLE" }
        mutex.withLock {
        val entry=requireNotNull(entries[token]) { "NATIVE_PACKAGE_CANDIDATE_UNAVAILABLE" }
        require(entry.metadata.owner==owner && entry.metadata.expiresAt>System.currentTimeMillis())
        val hash=MessageDigest.getInstance("SHA-256")
        entry.file.inputStream().use { input ->
            val b=ByteArray(65536);while(true) { currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;hash.update(b,0,n) }
        }
        check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==entry.metadata.sha256)
        entry.file.inputStream().use { read(entry.metadata,it) }
        }
    }

    /** Removed before use: failed/partial submission cannot silently replay a native document. */
    suspend fun <T> consume(owner: String, token: String, digest: String,
        usePrivateBytes: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val mutex = requireNotNull(readers[token]) { "NATIVE_PACKAGE_CANDIDATE_UNAVAILABLE" }
        mutex.withLock {
        val candidate=requireNotNull(entries[token]) { "NATIVE_PACKAGE_CANDIDATE_UNAVAILABLE" }
        require(candidate.metadata.owner==owner && candidate.metadata.sha256==digest)
        require(candidate.metadata.expiresAt>System.currentTimeMillis())
        check(entries.remove(token,candidate))
        try {
            val actual=MessageDigest.getInstance("SHA-256")
            candidate.file.inputStream().use { input ->
                val b=ByteArray(65536);while(true) { currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;actual.update(b,0,n) }
            }
            check(actual.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==digest) { "PACKAGE_STAGING_CHANGED" }
            candidate.file.inputStream().use { usePrivateBytes(it) }
        } finally { candidate.file.delete(); readers.remove(token,mutex) }
        }
    }

    suspend fun discard(owner: String, token: String) {
        val mutex = readers[token] ?: return
        mutex.withLock {
            val candidate=entries[token] ?: return@withLock
            require(candidate.metadata.owner==owner)
            if(entries.remove(token,candidate)) { candidate.file.delete(); readers.remove(token,mutex) }
        }
    }
}
