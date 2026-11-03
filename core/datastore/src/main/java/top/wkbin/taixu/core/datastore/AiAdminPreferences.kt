package top.wkbin.taixu.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only native Settings UI writes this switch; it is intentionally NOT in app_config's allowlist. */
class AiAdminPreferences(store: SettingsDataStore) {
    private val data = store.appearanceDataStore
    private val enabledKey = booleanPreferencesKey("ai_controlled_admin_enabled")
    private val revisionKey = longPreferencesKey("ai_controlled_admin_revision")
    val enabled = data.data.map { it[enabledKey] ?: false }
    val snapshot = data.data.map { (it[enabledKey] ?: false) to (it[revisionKey] ?: 0L) }
    suspend fun <T> withManagementSnapshot(block: suspend (Pair<Boolean, Long>) -> T): T =
        AiAdminCoordinator.serialized { block(snapshot.first()) }
    suspend fun setEnabledByUser(value: Boolean) = AiAdminCoordinator.serialized { data.edit {
        if ((it[enabledKey] ?: false) != value) {
            it[enabledKey] = value
            it[revisionKey] = (it[revisionKey] ?: 0L) + 1L
        }
    } }

}

/** One process-wide native boundary: closing completes only after prior management has left it. */
internal object AiAdminCoordinator {
    private val mutex = Mutex()
    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
}
