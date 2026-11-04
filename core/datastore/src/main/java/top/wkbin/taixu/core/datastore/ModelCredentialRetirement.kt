package top.wkbin.taixu.core.datastore

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Encrypted metadata journal. Marking retirement does not claim the key has been erased. */
internal class ModelCredentialRetirement(private val store: SettingsDataStore) {
    suspend fun mark(reference: String) = store.markCredentialRetirement(reference)
}
