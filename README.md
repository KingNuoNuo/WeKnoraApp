<div align="center">

<img src="art/ic_launcher-512.png" width="120" alt="WeKnora App icon">

# WeKnora App

**私有部署 WeKnora 知识库的安卓客户端**

面向网络 / 服务器运维场景的「随身技术资料柜」：把 NAS 上几千篇设备手册、厂商文档灌进 [WeKnora](https://github.com/Tencent/WeKnora)，在机房、设备旁、出差路上随时查。

Android client for a self-hosted WeKnora knowledge base — chat with RAG agents over SSE, read PDF/DOCX/PPTX manuals in-app, and manage documents and artifacts from your phone.

[![Platform](https://img.shields.io/badge/platform-Android%2015%2B-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-7F52FF?logo=kotlin&logoColor=white)](#)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![minSdk](https://img.shields.io/badge/minSdk-35-blue)](#)
[![Release](https://img.shields.io/github/v/release/KingNuoNuo/WeKnoraApp?color=0F6B63)](https://github.com/KingNuoNuo/WeKnoraApp/releases/latest)
[![Build](https://github.com/KingNuoNuo/WeKnoraApp/actions/workflows/release.yml/badge.svg)](https://github.com/KingNuoNuo/WeKnoraApp/actions/workflows/release.yml)

</div>

---

当前版本 **v1.1.39**（versionCode 41）· [下载 APK](https://github.com/KingNuoNuo/WeKnoraApp/releases/latest) · [更新日志](CHANGELOG.md)

> 本仓库只包含**安卓客户端**。服务端部署、知识图谱 / Neo4j 开关、模型配置都在 [Tencent/WeKnora](https://github.com/Tencent/WeKnora) 一侧。

## 功能 / Features

### 对话

- 两种问答通道：`agent-chat`（智能体 + 工具调用）与 `knowledge-chat`（纯知识库检索）
- **SSE 流式输出**，逐字上屏，可中途停止；思考过程默认折叠，点击整行展开
- 自写 **Markdown 渲染**：标题、列表、表格、围栏代码块、引用、行内样式。窄屏专门处理过：代码块横向滚动不折行；**表格按气泡可用宽度分配列宽，右端不会被屏幕裁掉**，只有列多到放不下时才横滑
- **运行参数贴在输入区上方**：智能体 / 模型 / 思考强度 / 联网四个开关，和「敲下一条问题」是一套连续动作，不用再抬手到屏幕顶端
- **只显示正文里的插图**。回答末尾不再追加「文档插图 / 来源图片」缩略图区 —— 正文没写图就不给图
- **PDF 等非图片资源自动变成文档卡片**：显示文件类型与大小，点开走内置阅读器（PDF / 图片 / 文本 / DOCX / PPTX / HTML）；模型把资源句柄写成带省略号的截断形式时，会拿引用分块里的完整句柄修回来
- **参考来源**默认收起，展开可看引用了哪几篇、摘要与网页域名
- 正文长按选中复制，另有一键复制按钮；对话页保持屏幕常亮
- 会话创建 / 切换 / 删除，支持附件上传；网络切换时刷新连接池并重新解析 DNS

### 文档与知识库

- **内置阅读器**：PDF（上下连续滚动、书签目录、页码跳转、缩放）、DOCX（正文 / 表格 / 内嵌图）、PPTX、HTML、常见文本与图片；旧版 `.doc` 不支持
- 多知识库切换，库内文档数 / 分块数统计
- 文档列表分页、文件夹树、按文件夹筛选
- 上传（可多选）、下载、预览、重新解析、取消解析、批量重新解析 / 批量删除、移动到文件夹
- 解析状态实时刷新

### 产物（Artifacts）

- 按会话归档产物列表，在线预览 / 下载 / 删除
- 按会话与文件路径合并重复条目，删除历史版本时一次清理

### 连接与主题

- 首次启动引导页填写：服务地址、API Key、Tenant ID，保存前可**测试连接**
- 局域网无 TLS 部署可用（manifest 已放行明文 http，如 `http://<内网 IP>:8080` 或 `:8081`，按实际部署端口填）；公网反代请配 https
- `X-API-Key` / `Authorization: Bearer` 两种鉴权头都会发
- Material 3 明暗双主题，跟随系统；视觉语言「墨与信号」：信号青 `#0F6B63` / 墨色顶栏 `#12201E` / 暖纸底 `#F5F3EE`
- 设置页**检查更新**，读 `update.json`（默认 GitHub raw，可改成自己的 NAS）

## 技术栈 / Tech Stack

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3（不引第三方设计库） |
| 架构 | 单 Activity + Compose Navigation + `ViewModel` + `State` |
| 网络 | OkHttp 手写请求（SSE 自行读 `Source`，不用 Retrofit） |
| 序列化 | kotlinx.serialization |
| 图片 | Coil |
| 构建 | AGP 8.9.1 / Gradle 8.11.1 / Kotlin 2.1.20 / JDK 17 |
| SDK | minSdk 35 · targetSdk 35 · compileSdk 36（扩展 19） |

## 构建 / Build

需要 **JDK 17** + **Android SDK 36 扩展 19**（`platforms;android-36`、`platforms;android-36-ext19`、`build-tools;36.0.0`）。

仓库已带 `gradlew` / `gradlew.bat`（`.gitattributes` 锁死行尾，Linux 上不会因为 CRLF 挂掉），两个平台都能直接跑：

```bash
# Windows
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug

# macOS / Linux
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> `local.properties` 里的 `sdk.dir` 是本机绝对路径，已在 `.gitignore` 中排除，请按自己的 SDK 位置自行创建。
> 根目录下的 `build-apk.bat` / `_build.py` 是本机出包脚本，不是构建必需。

### 自动构建

`.github/workflows/release.yml`：**push `v*` tag 即触发**，约 5 分钟跑完。

1. JDK 17 + Android SDK 36 / ext19
2. **校验 tag 与 `versionName` 一致**（不一致直接 fail，防止包和版本对不上）
3. 从 GitHub Secrets 还原 release keystore（详见「签名」一节）
4. `testDebugUnitTest` → `assembleRelease`（用固定证书签名）
5. `apksigner` 校验证书指纹，不匹配直接 fail
6. 发布 GitHub Release，挂上 `app-release.apk`

也可以从 Actions 页面用 `workflow_dispatch` 手动填 tag 触发。

### 发版要同步改三处

| 文件 | 改什么 |
|---|---|
| `app/build.gradle.kts` | `versionCode` +1、`versionName` 末位 +1 |
| `update.json` | `version` / `version_code` / `url`（漏改会让 App 内检查更新一直读到旧版本） |
| `CHANGELOG.md` | 追加本次条目 |

然后 `git tag -a v<versionName> -m ...` 并推送 tag。tag 必须和 `versionName` 完全相同（带 `v` 前缀）。

## 首次连接

1. 在 WeKnora 服务端创建 API Key
2. 打开 App → 填写服务地址（如 `http://10.0.0.4:8081`）、API Key、Tenant ID
3. 点「测试连接」→ 通过后保存，进入知识库列表

## 目录结构

```
app/src/main/java/com/jinge/weknora/
├── MainActivity.kt              # 单 Activity 入口 + 底部导航
├── DocumentReaderActivity.kt    # 内置阅读器（PDF/DOCX/PPTX/HTML/文本/图片）
├── NativePdfActivity.kt         # PDF 连续滚动渲染
├── data/
│   ├── WeKnoraApi.kt            # REST + SSE 流式客户端
│   ├── Models.kt                # 数据模型（kotlinx.serialization）
│   ├── ChatHistoryParser.kt     # 历史会话解析
│   ├── ReferenceParser.kt       # references 帧解析 + 截断资源句柄修复
│   ├── SettingsStore.kt         # 连接配置持久化
│   ├── UpdateChecker.kt / UpdateDownloader.kt
│   └── WordPreview.kt / PowerPointPreview.kt / PdfOutline.kt / ImageSaver.kt
└── ui/
    ├── AppViewModel.kt          # 全局状态与业务动作
    ├── markdown/                # Markdown 解析与渲染、图片 / 文档卡片、工具调用清洗
    ├── screens/                 # Setup / Chat / Docs / Artifacts / Settings
    └── theme/Theme.kt           # 设计 token 与配色

app/src/test/                    # ChatHistoryParser / Markdown 正文 / Word / PPTX 单测
.github/workflows/release.yml    # 打 tag 自动出包 + 固定签名 + 指纹校验
design/                          # 本地设计对比稿（静态 HTML，未纳入版本库、不参与构建）
signing/                         # 本地签名私钥与密码（.gitignore 排除，CI 走 Secrets）
update.json                      # App 内检查更新读的版本文件
```

## 更新机制

`update.json` 放在仓库根目录（也可以放自己的 NAS），当前实际格式：

```json
{
  "version": "1.1.39",
  "version_code": 41,
  "notes": "本次更新说明",
  "url": "https://github.com/KingNuoNuo/WeKnoraApp/releases/download/v1.1.39/app-release.apk"
}
```

App 按 `version_code` 比较（缺失时退化为语义化版本比较），发现更大就提示下载并显示进度。
`url` 要指向**该版本的 APK 直链**，不要用 `/releases/latest` 页面地址。

## 签名 / Signing

Release 包用**固定的 release keystore** 签名，各版本证书一致，可以直接覆盖安装。

**本机出 Release**：`signing/` 下放 `release.jks` 和 `keystore.properties`（`storeFile` /
`storePassword` / `keyAlias` / `keyPassword`）。构建脚本按 `-P 属性 > 环境变量 > 该文件`
的顺序取值；都没配时退回 debug 签名，只用于未配置签名的新克隆环境。

**CI 出 Release**：私钥不入库，由三个仓库 Secret 注入：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 signing/release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore 密码（key 密码相同） |
| `RELEASE_KEY_ALIAS` | 密钥别名 |

workflow 会先还原 keystore，构建后用 `apksigner` 比对证书 SHA-256
（`462c41a3…9dace5`，常量写在 `release.yml` 里）。换 keystore 时**必须同步改这个常量**，
否则 CI 会正确地把包拦下来 —— 这道校验就是为了防止 Secret 缺失时又悄悄退回每次都变的 debug 签名。

> ⚠️ **私钥只有本地这一份。** `signing/` 已被 `.gitignore` 排除，请自行离线备份
> keystore 与密码；丢了就只能用新证书重签，所有设备又得卸载重装一次。
>
> 同理，从 debug 签名的旧版本（≤ 1.1.37）升级到 **v1.1.38（首个固定签名版本）** 时，
> **必须卸载重装一次**，Android 不允许不同证书的包覆盖安装。之后就一劳永逸。

## 说明

个人自用项目，接口对齐 WeKnora 服务端 `/api/v1/*`，随服务端演进可能调整。
Release 包仅供内部安装使用，未上架应用商店。
