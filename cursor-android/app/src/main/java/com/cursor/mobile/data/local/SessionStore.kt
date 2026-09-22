package com.cursor.mobile.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cursor_prefs")

class SessionStore(private val context: Context) {
    private val apiKeySecurePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cursor_secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val selectedModelKey = stringPreferencesKey("selected_model_id")
    private val selectedRepoKey = stringPreferencesKey("selected_repo_url")
    private val selectedModeKey = stringPreferencesKey("selected_mode")

    fun getApiKey(): String? = apiKeySecurePrefs.getString(KEY_API, null)

    fun setApiKey(value: String) {
        apiKeySecurePrefs.edit().putString(KEY_API, value.trim()).apply()
    }

    fun clearApiKey() {
        apiKeySecurePrefs.edit().remove(KEY_API).apply()
    }

    val selectedModelId: Flow<String?> = context.dataStore.data.map { it[selectedModelKey] }
    val selectedRepoUrl: Flow<String?> = context.dataStore.data.map { it[selectedRepoKey] }
    val selectedMode: Flow<String?> = context.dataStore.data.map { it[selectedModeKey] }

    suspend fun setSelectedModelId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(selectedModelKey) else prefs[selectedModelKey] = id
        }
    }

    suspend fun setSelectedRepoUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(selectedRepoKey) else prefs[selectedRepoKey] = url
        }
    }

    suspend fun setSelectedMode(mode: String?) {
        context.dataStore.edit { prefs ->
            if (mode.isNullOrBlank()) prefs.remove(selectedModeKey) else prefs[selectedModeKey] = mode
        }
    }

    companion object {
        private const val KEY_API = "api_key"
    }
}
