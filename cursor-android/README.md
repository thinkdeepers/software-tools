# Cursor Android（Cloud Agents 客户端）

手机端 Cursor 云编程应用：登录 Cursor 账号（API Key）后，通过官方 **Cloud Agents API** 在云端仓库完成 AI 对话式编程。

> 范围：仅 AI 对话驱动的云编程（创建 Agent、模型选择、仓库绑定、流式对话、跟进指令、取消/归档）。  
> 不包含：本地代码浏览、编辑器、Tab 补全。

## 功能

| 功能 | 说明 |
|------|------|
| 登录 | Dashboard API Key（Bearer），加密本地存储 |
| 模型选择 | `GET /v1/models`，创建任务时指定或使用账号默认 |
| 仓库选择 | `GET /v1/repositories`，也可手动填写 GitHub URL |
| 新建云任务 | `POST /v1/agents`（模式 Agent/Plan、自动开 PR） |
| 对话 | 历史会话 + 跟进 `POST /v1/agents/{id}/runs` |
| 流式输出 | SSE `.../runs/{runId}/stream`（assistant / thinking / tool_call） |
| 管理 | 取消运行、归档、删除、打开网页版 Agent |

## 环境要求

- Android Studio Ladybug+ / JDK 17
- Android SDK 35
- 付费 Cursor 账号，并已在 Dashboard 连接 GitHub 等源码托管
- 在 [API Keys](https://cursor.com/dashboard/api) 创建用户 API Key

## 构建

```bash
cd cursor-android
./gradlew :app:assembleDebug
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用步骤

1. 打开应用，粘贴 Cursor API Key 并登录  
2. 进入首页查看 Cloud Agents 列表  
3. 点击右下角 **+**，选择模型与仓库，输入任务指令  
4. 进入对话页查看流式回复，并可继续发送跟进指令  
5. 需要时在菜单中归档 / 删除，或在网页打开同一 Agent

## 项目结构

```
cursor-android/
  app/src/main/java/com/cursor/mobile/
    data/api/          # Retrofit + SSE
    data/model/        # API 数据模型
    data/local/        # 加密 API Key / DataStore 偏好
    data/repository/   # 业务封装
    ui/login|home|create|chat|settings/
    navigation/        # Compose Navigation
```

## API 参考

- [Cloud Agents API](https://cursor.com/docs/cloud-agent/api/endpoints)
- [API Overview](https://cursor.com/docs/api)

## 说明

Cursor 官方暂无原生 Android 应用；网页端可用 [cursor.com/agents](https://cursor.com/agents) PWA。  
本仓库提供基于公开 Cloud Agents API 的原生 Kotlin / Jetpack Compose 客户端，能力对齐电脑端「Cloud Agent 对话编程」，不覆盖本地 IDE 能力。
