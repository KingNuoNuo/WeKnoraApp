<div align="center">

<img src="art/ic_launcher-512.png" width="120" alt="WeKnora App icon">

# WeKnora App

**WeKnora 私有部署知识库的安卓客户端**
面向网络 / 服务器运维场景的「随身技术资料柜」—— 把 NAS 上几千篇设备手册、厂商文档灌进 [WeKnora](https://github.com/Tencent/WeKnora)，在机房、设备旁、出差路上随时查。

Android client for a self-hosted WeKnora knowledge base — browse, chat with RAG agents, and manage documents from your phone.

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](#)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![minSdk](https://img.shields.io/badge/minSdk-26-blue)](#)
[![Version](https://img.shields.io/badge/version-1.1.25-0F6B63)](#)

</div>

---

## 功能 / Features

**对话**
- 两种问答通道：`agent-chat`（智能体 + 工具调用）与 `knowledge-chat`（纯知识库检索）
- **SSE 流式输出**，逐字上屏，可中途停止
- 回答渲染 **Markdown**（标题、列表、表格、围栏代码块、引用、行内样式），窄屏专门处理：代码块横向滚动不折行、表格等宽可横滑、标题降字号
- 工具调用以 chip 条展示；服务端漏解析的纯文本 `<tool_call>` 会被清洗掉，不污染正文
- **回答里的图片直接显示**：Markdown 图片 + 来源 `image_*` 分块的配图（公有云 OSS 公网地址可用），点开看大图并保存到相册
- 正文与列表、引用、表格中的图片按内容顺序显示；思考过程默认折叠，点击整行展开或收起
- 回答下方给出**参考来源**（默认收起），可看到引用了哪几篇、摘要与来源图片
- 对话页**保持屏幕常亮**，长回答不会读到一半黑屏
- 正文长按可选中复制，渲染与一键复制都用清洗后的文本
- 会话创建 / 切换 / 删除，支持附件上传

**知识库与文档**
- 内置 PDF / DOCX 阅读：点击文档打开；PDF 支持上下连续滚动、书签目录、页码跳转和放大；DOCX 支持正文、表格、内嵌图片（旧版 DOC 暂不支持）
- 多知识库切换，库内统计（文档数 / 分块数）
- 文档列表分页、文件夹树、按文件夹筛选
- 上传（可多选）、下载、预览、重新解析、取消解析、批量重新解析 / 批量删除、移动到文件夹
- 解析状态实时刷新

**产物（Artifacts）**
- 会话产物与会话产物的归档列表，在线预览 / 下载 / 删除

**连接与主题**
- 首次启动引导页填写：服务地址、API Key、Tenant ID，保存前可**测试连接**
- 局域网无 TLS 部署也可用（manifest 已放行明文 http，供 `http://<内网IP>:8080` 直连；公网反代请配 https）
- Material 3 明暗双主题，跟随系统；视觉语言「墨与信号」——信号青 `#0F6B63` / 墨色顶栏 `#12201E` / 暖纸底 `#F5F3EE`
- 设置页可**检查更新**（读仓库根目录 `update.json`，更新源可改成自己的 NAS）

## 技术栈 / Tech Stack

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3（不引第三方设计库） |
| 架构 | 单 Activity + Compose Navigation + `ViewModel` + `State` |
| 网络 | OkHttp 手写请求（SSE 自行读 `Source`，不用 Retrofit） |
| 序列化 | kotlinx.serialization |
| 构建 | AGP 8.5.2 / Gradle 8.7 / Kotlin 2.0.21 / JDK 17 |
| SDK | minSdk 26 · targetSdk 34 · compileSdk 34 |

## 构建 / Build

需要 **JDK 17** + **Android SDK 34**。

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux：仓库只带了 gradlew.bat，先补一个 wrapper 脚本
gradle wrapper && ./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> `local.properties` 里的 `sdk.dir` 是本机绝对路径，已在 `.gitignore` 中排除，请按自己的 SDK 位置自行创建。

## 首次连接

1. 在 WeKnora 服务端创建 API Key（`X-API-Key` / `Authorization: Bearer` 两种鉴权头客户端都会发）
2. 打开 App → 填写服务地址（如 `http://10.0.0.4:8080`）、API Key、Tenant ID
3. 点「测试连接」→ 通过后保存，进入知识库列表

## 目录结构

```
app/src/main/java/com/jinge/weknora/
├── MainActivity.kt          # 单 Activity 入口 + 导航
├── data/
│   ├── Models.kt            # 数据模型（kotlinx.serialization）
│   ├── SettingsStore.kt     # 连接配置持久化
│   └── WeKnoraApi.kt        # WeKnora REST + SSE 客户端
└── ui/
    ├── AppViewModel.kt      # 全局状态与业务动作
    ├── markdown/            # 自写 Markdown 解析与渲染 + 工具调用清洗
    ├── screens/             # Setup / Chat / Docs / Artifacts / Settings
    └── theme/Theme.kt       # 设计 token 与配色
```

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。当前版本 **1.1.25**（versionCode 27）。

## 更新机制

`update.json` 放在仓库根目录（也可以放自己的 NAS），格式：

```json
{
  "version": "1.1.6",
  "version_code": 8,
  "notes": "本次更新说明",
  "url": "https://github.com/KingNuoNuo/WeKnoraApp/releases/latest"
}
```

App 按 `version_code` 比较（缺失时退化为语义化版本比较），发现更大就提示下载。
发版时把这个文件的版本号和 `app/build.gradle.kts` 一起改掉即可。

## 说明

个人自用项目，接口对齐 WeKnora 服务端 `/api/v1/*`，随服务端演进可能调整。
Release 包使用 debug 签名，仅供内部安装使用，未上架应用商店。
