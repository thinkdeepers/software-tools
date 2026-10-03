package com.cursor.mobile.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefsStore: DataStore<Preferences> by preferencesDataStore(name = "cursor_mobile_prefs")

object PrefsRepository {
    private val TierKey = stringPreferencesKey("model_tier")
    private val ModelKey = stringPreferencesKey("model_name")
    private val PinsKey = stringSetPreferencesKey("pinned_session_ids")

    fun tierFlow(context: Context): Flow<ModelTier> =
        context.prefsStore.data.map { ModelTier.fromName(it[TierKey] ?: ModelTier.BALANCED.name) }

    fun modelFlow(context: Context): Flow<String> =
        context.prefsStore.data.map { it[ModelKey] ?: ModelTier.BALANCED.model }

    suspend fun saveSelection(context: Context, tier: ModelTier, model: String) {
        context.prefsStore.edit {
            it[TierKey] = tier.name
            it[ModelKey] = model
        }
    }

    fun pinsFlow(context: Context): Flow<Set<String>> =
        context.prefsStore.data.map { it[PinsKey] ?: emptySet() }

    suspend fun togglePin(context: Context, id: String) {
        if (id.isBlank()) return
        context.prefsStore.edit { prefs ->
            val now = prefs[PinsKey]?.toMutableSet() ?: mutableSetOf()
            if (!now.add(id)) now.remove(id)
            prefs[PinsKey] = now
        }
    }
}
