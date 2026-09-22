package com.cursor.mobile.data.api

import com.cursor.mobile.data.model.ApiErrorBody
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

object ApiJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }
}

class AuthInterceptor(
    private val apiKeyProvider: () -> String?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = apiKeyProvider()?.trim().orEmpty()
        val request = if (key.isNotEmpty()) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

object CursorApiFactory {
    const val BASE_URL = "https://api.cursor.com/"

    fun createOkHttp(apiKeyProvider: () -> String?): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(apiKeyProvider))
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun createApi(client: OkHttpClient): CursorApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(ApiJson.instance.asConverterFactory(contentType))
            .build()
            .create(CursorApi::class.java)
    }
}

fun Throwable.toUserMessage(): String {
    return when (this) {
        is HttpException -> {
            val body = response()?.errorBody()?.string().orEmpty()
            val parsed = runCatching {
                ApiJson.instance.decodeFromString(ApiErrorBody.serializer(), body)
            }.getOrNull()
            val code = parsed?.error?.code ?: parsed?.code
            val message = parsed?.error?.message ?: parsed?.message
            when {
                !message.isNullOrBlank() && !code.isNullOrBlank() -> "$code: $message"
                !message.isNullOrBlank() -> message
                code == "401" || response()?.code() == 401 -> "API Key 无效或已过期，请重新登录"
                response()?.code() == 429 -> "请求过于频繁，请稍后再试（仓库列表限流较严）"
                response()?.code() == 409 -> message ?: "代理正忙，请等待当前任务完成"
                else -> "HTTP ${response()?.code()}: ${message ?: message()}"
            }
        }
        else -> message ?: "网络错误，请检查连接后重试"
    }
}
