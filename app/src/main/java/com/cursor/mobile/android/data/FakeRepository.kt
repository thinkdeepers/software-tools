package com.cursor.mobile.android.data

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.delay

object FakeRepository {
    val models = listOf("Composer 2.5", "GPT-5", "Claude Sonnet 4.5", "Gemini 2.5 Pro")
    val slashCommands = listOf("/remote-control", "/fix-ci", "/review", "/move-to-cloud", "/summarize")

    val sessions = mutableStateListOf(
        AgentSession("a1", "修复登录页闪退并补回归测试", "acme/shop-app", "fix/login-crash", AgentStatus.WORKING, "Composer 2.5", MachineKind.CLOUD, "2 分钟前", unread = 2),
        AgentSession("a2", "给订单模块加退款单导出", "acme/shop-app", "feat/refund-export", AgentStatus.NEEDS_INPUT, "Claude Sonnet 4.5", MachineKind.TEAM_POOL, "18 分钟前", unread = 1),
        AgentSession("a3", "升级 Gradle 8.10 并修 CI", "acme/shop-app", "chore/gradle-810", AgentStatus.READY_FOR_REVIEW, "GPT-5", MachineKind.CLOUD, "1 小时前"),
        AgentSession("a4", "调研图片加载卡顿", "acme/shop-app", "spike/image-jank", AgentStatus.DONE, "Gemini 2.5 Pro", MachineKind.MY_MACHINE, "昨天")
    )

    val repos = listOf(
        RepoRef("acme/shop-app", "main"),
        RepoRef("acme/design-system", "main"),
        RepoRef("cook/side-project", "dev", false)
    )

    fun messagesFor(sessionId: String): MutableList<ChatMessage> = mutableListOf(
        ChatMessage("m1", Sender.USER, "复现路径：Pixel 8 上登录页输入手机号后点发送验证码就闪退，帮忙定位并修掉。", "10:02"),
        ChatMessage("m2", Sender.AGENT, "收到，我先复现并抓堆栈，同时在云端机器上起一个 Agent 跑这单。你可以继续聊，我边做边同步。", "10:03"),
        ChatMessage("m3", Sender.AGENT, "定位到 `SmsCodeButton` 在倒计时未初始化时解包空状态，已加保护并补了单测，CI 日志和 Demo 录屏稍后贴上来。", "10:11", attachment = "diff: SmsCodeButton.kt +42/-8"),
        ChatMessage("m4", Sender.SYSTEM, "云端任务运行中 · Composer 2.5 · Cloud machine", "10:11")
    )

    fun diffsFor(sessionId: String): List<DiffFile> = listOf(
        DiffFile(
            "app/src/main/java/shop/login/SmsCodeButton.kt", 42, 8, "M",
            "@@ 倒计时状态增加空保护\n+ if (countdown == null) return\n  fun onClick() { ... }"
        ),
        DiffFile("app/src/test/java/shop/login/SmsCodeButtonTest.kt", 68, 2, "A", "@@ 新增 5 个单测\n+ test(\"空状态不闪退\") { ... }"),
        DiffFile("screenshots/login-fix-demo.mp4", 0, 0, "DEMO", "云端生成的验证录屏，可在 Review 页播放占位。")
    )

    suspend fun fakeAgentReply(userText: String, onToken: (String) -> Unit) {
        val reply = "明白：「$userText」。Demo 为高保真占位回复：我会先在云端复现 → 改最小 diff → 跑单测 → 贴录屏/截图 → 等你 Review 后合 PR。接真实后端时把这里换成 SSE/WebSocket 流即可。"
        val acc = StringBuilder()
        reply.chunked(6).forEach { part ->
            delay(28)
            acc.append(part)
            onToken(acc.toString())
        }
    }
}
