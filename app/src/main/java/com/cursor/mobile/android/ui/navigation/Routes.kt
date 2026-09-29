package com.cursor.mobile.android.ui.navigation

object Routes {
    const val INBOX = "inbox"
    const val NEW_AGENT = "new"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"
    const val REVIEW = "review/{sessionId}"

    fun chat(id: String) = "chat/$id"
    fun review(id: String) = "review/$id"
}
