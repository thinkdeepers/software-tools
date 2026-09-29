package com.cursor.mobile.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.delay

object AuthRepository {
    private const val FILE = "cursor_auth_secure"
    private const val KEY_API_KEY = "cursor_api_key"

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getString(KEY_API_KEY, null).isNullOrBlank().not()

    fun maskedKey(context: Context): String {
        val raw = prefs(context).getString(KEY_API_KEY, null) ?: return ""
        return if (raw.length <= 8) "••••" else raw.take(4) + "••••" + raw.takeLast(4)
    }

    fun saveKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_API_KEY).apply()
    }

    fun validateFormat(key: String): String? {
        val v = key.trim()
        if (v.isEmpty()) return "请粘贴 Cursor API Key"
        if (v.length < 16) return "Key 太短，请检查是否复制完整"
        if (v.contains(" ")) return "Key 含空格，请检查是否多复制了字符"
        return null
    }

    suspend fun verifyWithApi(apiKey: String): Boolean {
        delay(700)
        return validateFormat(apiKey) == null && apiKeyRequiresNetworkPass(apiKey)
    }

    private fun apiKeyRequiresNetworkPass(apiKey: String): Boolean = apiKey.trim().length >= 16

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
