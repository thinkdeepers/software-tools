package com.cursor.mobile.di

import android.content.Context
import com.cursor.mobile.data.api.CursorApi
import com.cursor.mobile.data.api.CursorApiFactory
import com.cursor.mobile.data.api.RunStreamClient
import com.cursor.mobile.data.local.SessionStore
import com.cursor.mobile.data.repository.CursorRepository
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val sessionStore = SessionStore(context.applicationContext)

    private val okHttpClient: OkHttpClient =
        CursorApiFactory.createOkHttp { sessionStore.getApiKey() }

    val api: CursorApi = CursorApiFactory.createApi(okHttpClient)

    val streamClient: RunStreamClient = RunStreamClient(okHttpClient)

    val repository: CursorRepository = CursorRepository(api, streamClient, sessionStore)
}
