package com.cursor.mobile.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AuthRepository {
    private const val FILE = "cursor_auth_secure"
    private const val KEY_API_KEY = "cursor_api_key"

    fun normalizeKey(key: String): String = key.replace("\\s+".toRegex(), "")

    fun isLoggedIn(context: Context): Boolean = apiKey(context) != null

    fun apiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun maskedKey(context: Context): String {
        val raw = apiKey(context) ?: return ""
        return if (raw.length <= 8) "••••" else raw.take(4) + "••••" + raw.takeLast(4)
    }

    fun saveKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, normalizeKey(key)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_API_KEY).apply()
    }

    fun validateFormat(key: String): String? {
        val value = normalizeKey(key)
        if (value.isEmpty()) return "请粘贴 Cursor API Key"
        if (value.length < 16) return "Key 太短，请检查是否复制完整"
        return null
    }

    suspend fun verifyWithApi(apiKey: String): String? = withContext(Dispatchers.IO) {
        CursorApi.verifyKey(normalizeKey(apiKey))
    }

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
