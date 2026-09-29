# Cursor Mobile · Android Demo

Android 原生 Demo，对标 iOS 版 Cursor（Public Beta），跑通可编译、可安装主流程。

## 构建运行

需要 JDK 17+、Android SDK（platform 34 + build-tools 34.0.0）。

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 安装：adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 已实现（Demo 占位）

- 侧边栏（ModalNavigationDrawer）：收件箱 / 发起 Agent / 设置 / 仓库列表
- 收件箱：Agent 会话列表、状态、未读、机器类型
- 对话页：消息流、模型切换、`/指令` 快捷、模拟流式回复、附件/语音占位
- 发起页：选仓库/模型/机器、任务描述、本地建单
- Review 页：diff 列表、CI/approvals 占位、Merge / Request changes 占位
- 设置页：深色主题切换、推送开关、隐私/Remote Control 说明
- 主题：Material3 + iOS 风格浅色 `#F2F2F7` / 深色，圆角卡片

接真实后端时替换 `data/FakeRepository.kt` 为 SSE/WebSocket + REST（sessions/chat/diff/review/merge）。
