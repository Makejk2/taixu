package top.wkbin.taixu.runtime.admin

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One application-scoped owner, rooted in Context.filesDir (never provider/external paths).
 * A restarted process reclaims orphan archives, while live stores in this process are excluded. */
class PrivateNativeImportStore(private val root: File) {
    private val directory: File
    init {
        synchronized(liveDirectories) {
            check(root.isDirectory || root.mkdirs())
            root.listFiles().orEmpty().filter {
                it.name.matches(Regex("owner-[a-f0-9-]{36}")) && it.canonicalPath !in liveDirectories
            }.forEach { check(it.deleteRecursively()) { "NATIVE_ARCHIVE_ORPHAN_CLEANUP_FAILED" } }
            directory = File(root.canonicalFile, "owner-${UUID.randomUUID()}")
            check(directory.mkdir())
            check(directory.setReadable(false, false) && directory.setWritable(false, false) && directory.setExecutable(false, false))
            check(directory.setReadable(true, true) && directory.setWritable(true, true) && directory.setExecutable(true, true))
            liveDirectories.add(directory.absolutePath)
        }
    }

    /** Streaming capture, cancellation checkpoints, full SHA-256, fsync then read-only rename. */
    fun capture(open: () -> InputStream, checkpoint: () -> Unit): NativeImportHandle {
        val id = UUID.randomUUID().toString()
        val staging = File(directory, "$id.part")
        val sealed = File(directory, "$id.archive")
        var retained = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            open().use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bytes += count
                        check(bytes <= 16L * 1024 * 1024 * 1024) { "NATIVE_ARCHIVE_TOO_LARGE" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(bytes > 0) { "NATIVE_ARCHIVE_EMPTY" }
            check(staging.setReadOnly())
            check(staging.renameTo(sealed))
            val handle = PrivateHandle(id, digest.digest().hex(), bytes, sealed)
            retained = true
            return handle
        } finally {
            if (!retained) {
                val partRemoved = !staging.exists() || staging.delete()
                val archiveRemoved = !sealed.exists() || sealed.delete()
                check(partRemoved && archiveRemoved) { "NATIVE_ARCHIVE_CLEANUP_FAILED" }
            }
        }
    }

    private class PrivateHandle(
        override val id: String, override val sha256: String, override val sizeBytes: Long,
        private val archive: File,
    ) : NativeImportHandle {
        private val monitor = Any()
        private var closed = false
        private var using = false
        override suspend fun <T> withVerifiedArchive(block: suspend (File) -> T): T {
            synchronized(monitor) { check(!closed && !using); using = true }
            try {
                check(archive.isFile && archive.length() == sizeBytes) { "NATIVE_ARCHIVE_CHANGED" }
                val digest = MessageDigest.getInstance("SHA-256")
                archive.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                check(digest.digest().hex() == sha256) { "NATIVE_ARCHIVE_CHANGED" }
                return block(archive)
            } finally {
                synchronized(monitor) { using = false; if (closed) delete() }
            }
        }
        override fun close() = synchronized(monitor) {
            closed = true
            if (!using) delete() // Idempotent; cleanup can be retried if filesystem deletion failed.
        }
        private fun delete() { check(!archive.exists() || archive.delete()) { "NATIVE_ARCHIVE_CLEANUP_FAILED" } }
    }
    private companion object {
        val liveDirectories = mutableSetOf<String>()
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
