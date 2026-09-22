package com.cursor.mobile.data.api

import com.cursor.mobile.data.model.ApiErrorBody
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiJsonParseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun parsesApiErrorBody() {
        val raw = """{"error":{"code":"agent_busy","message":"Agent is busy"}}"""
        val parsed = json.decodeFromString(ApiErrorBody.serializer(), raw)
        assertTrue(parsed.error?.code == "agent_busy")
        assertTrue(parsed.error?.message?.contains("busy") == true)
    }
}
