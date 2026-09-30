package com.cursor.mobile.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AuthRepository {
    private const val FILE = "cursor_auth_secure"
    private const val KEY_ACCESS = "cursor_access_token"
    private const val KEY_REFRESH = "cursor_refresh_token"
    private const val KEY_API_KEY = "cursor_api_key"
    private const val KEY_EMAIL = "cursor_account_email"

    fun isLoggedIn(context: Context): Boolean = accessToken(context) != null && apiKey(context) != null

    fun hasPendingSession(context: Context): Boolean = accessToken(context) != null && apiKey(context) == null

    fun accessToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() }

    fun apiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun accountLabel(context: Context): String {
        val email = prefs(context).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
        return email ?: if (isLoggedIn(context)) "已登录" else ""
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_API_KEY)
            .remove(KEY_EMAIL)
            .apply()
    }

    suspend fun signIn(context: Context, openLogin: (String) -> Unit): String? {
        val handshake = CursorSession.handshake()
        try {
            withContext(Dispatchers.Main.immediate) { openLogin(handshake.url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "无法打开 Cursor 登录页"
        }
        val tokens = try {
            withContext(Dispatchers.IO) { CursorSession.poll(handshake.uuid, handshake.verifier) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "登录失败"
        }
        return finishMint(context, tokens.accessToken, tokens.refreshToken)
    }

    suspend fun retryMint(context: Context): String? {
        val token = accessToken(context) ?: return "没有已保存的登录会话。请先点「用 Cursor 账号登录」。"
        val refresh = prefs(context).getString(KEY_REFRESH, null).orEmpty()
        return finishMint(context, token, refresh)
    }

    private suspend fun finishMint(context: Context, access: String, refresh: String): String? {
        val minted = try {
            withContext(Dispatchers.IO) { CursorSession.mintCloudCredential(access) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveSession(context, access, refresh, null, "")
            return e.message ?: "换发 Cloud Agents 凭证失败"
        }
        val email = minted.email.ifBlank { CursorSession.emailFromToken(access) }
        if (minted.apiKey.isNullOrBlank()) {
            saveSession(context, access, refresh, null, email)
            return minted.detail
        }
        saveSession(context, access, refresh, minted.apiKey, email)
        return null
    }

    private fun saveSession(context: Context, access: String, refresh: String, apiKey: String?, email: String) {
        val edit = prefs(context).edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_EMAIL, email)
        if (apiKey.isNullOrBlank()) edit.remove(KEY_API_KEY) else edit.putString(KEY_API_KEY, apiKey)
        edit.apply()
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
