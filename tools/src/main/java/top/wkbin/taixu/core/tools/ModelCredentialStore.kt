package top.wkbin.taixu.core.tools

/** Narrow encrypted credential port for model profile transfers. */
interface ModelCredentialStore {
    suspend fun setModelApiKeys(secretRef: String, values: List<String>)
    suspend fun readModelApiKeys(secretRef: String): List<String>
    suspend fun removeModelApiKey(secretRef: String)
    /** Native encrypted-store retirement journal; ordinary reads never reveal retired references. */
    suspend fun retireModelApiKey(secretRef: String) { error("CREDENTIAL_RETIREMENT_JOURNAL_UNAVAILABLE") }
    suspend fun eraseRetiredModelKeys(referenced: Set<String>): Int = error("CREDENTIAL_RETIREMENT_ERASURE_UNAVAILABLE")
}
