package com.jinge.weknora.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* ============================================================
 * WeKnora API 数据模型
 *
 * 所有 model 都开了 ignoreUnknownKeys（见 ApiClient.JSON）：WeKnora 返回的字段
 * 远多于这里声明的，而且版本之间会增删。只挑我们需要的字段，其余忽略——
 * 否则每次后端加字段都会让客户端反序列化直接抛异常。
 * ============================================================ */

@Serializable
data class ApiError(
    val code: Int = 0,
    val message: String = "",
    val details: JsonElement? = null
)

/**
 * 统一响应包装。
 *
 * 列表类接口（`/knowledge`、`/sessions` 等）在**外层**还带分页三件套
 * `total` / `page` / `page_size` —— 注意不是塞在 data 里。
 * `total` 是「筛选后的总条数」，分页拉全量必须靠它判断何时停。
 */
@Serializable
data class Envelope<T>(
    val data: T? = null,
    val success: Boolean = true,
    val error: ApiError? = null,
    val total: Int? = null,
    val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null
)

@Serializable
data class KnowledgeBase(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    /**
     * 库内文档总数。
     *
     * 服务端在知识库列表里就把它带上了（实测与文档接口的 total 完全一致：
     * 16/16、361/361），所以设置页显示「总数」**不需要**再拉一次文档表。
     *
     * 注意：知识库里还有个 `processing_count`，实测**不可信**
     * （「通用存储」库有 123 篇 finalizing 时它仍报 0），所以刻意不吃这个字段，
     * 「处理中」一律按文档状态本地分类数出来。
     */
    @SerialName("knowledge_count") val knowledgeCount: Int = 0
)

/**
 * 一个知识库的文档统计快照（设置页每个库右侧那三列数字）。
 *
 * 三个值都可空：null = 还不知道（正在统计 / 统计失败），UI 显示破折号。
 * 「还不知道」和「确实是 0」必须能分辨 —— 都写 0 会让人以为文档丢了。
 *
 * 后两个刻意划成**互不重叠**的两堆（见 [Knowledge.isQueued] / [Knowledge.isRunning]）：
 * 排队还没开始，处理已经在跑，所以「排队 + 处理」就是全部未完成的文档数。
 * 如果把排队也算进处理，两个数字就会互相包含，看着像加起来超过了总数。
 *
 * @param total      库内文档总数。来自知识库列表，随列表一起到达，不用等统计
 * @param queued     排队中（还没轮到解析）的条数
 * @param processing 正在跑（解析 / 收尾 / 建索引）的条数
 * @param loading    是否仍在统计中（驱动设置页标题右侧的转圈）
 */
data class KbStats(
    val total: Int? = null,
    val queued: Int? = null,
    val processing: Int? = null,
    val loading: Boolean = false
)

@Serializable
data class Session(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
) {
    /** 列表里显示的相对时间：`10-31 16:59`。解析失败就退回原串 */
    val timeLabel: String
        get() {
            val t = updatedAt.indexOf('T')
            return if (t >= 10 && updatedAt.length >= t + 6) {
                updatedAt.substring(5, t + 6).replace('T', ' ')
            } else updatedAt
        }
}

/** 历史消息（GET /api/v1/messages/{sid}/load） */
@Serializable
data class HistoryMessage(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    /**
     * 服务端这个字段类型不统一：用户消息是字符串 `"[]"`，
     * 助手消息是数组（参考文献对象列表）。用 JsonElement 接住，
     * 由调用方判断是字符串还是数组 —— 声明成 String 会在助手消息上直接抛
     * SerializationException。
     */
    @SerialName("knowledge_references") val references: JsonElement? = null,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class Knowledge(
    val id: String = "",
    val title: String = "",
    val type: String = "",
    @SerialName("parse_status") val parseStatus: String = "",
    val source: String = "",
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("folder_path") val folderPath: String = ""
) {
    /** parse_status 的中文显示 & 是否属于「进行中」 */
    val statusLabel: String
        get() = when (parseStatus) {
            "success", "completed", "done" -> "已就绪"
            "pending" -> "排队中"
            "processing" -> "解析中"
            "finalizing" -> "收尾中"
            "failed", "error" -> "解析失败"
            "cancelled", "canceled" -> "已取消"
            "indexing" -> "建索引中"
            "" -> "未知"
            else -> parseStatus
        }

    val isBusy: Boolean
        get() = parseStatus in setOf("pending", "processing", "finalizing", "indexing")

    /**
     * 排队中：还没轮到它解析（服务端还没有工作线程接手）。
     *
     * 与 [isRunning] 互斥，两者合起来正好是 [isBusy] 那一堆 ——
     * 设置页的「排队 / 处理」两列就靠这条不重叠的划分，
     * 否则排队数会同时被算进处理数，两个数字加起来对不上总数。
     */
    val isQueued: Boolean
        get() = parseStatus == "pending"

    /** 正在跑：已经在解析 / 收尾 / 建索引（不含排队） */
    val isRunning: Boolean
        get() = parseStatus in setOf("processing", "finalizing", "indexing")

    val isFailed: Boolean
        get() = parseStatus in setOf("failed", "error", "cancelled", "canceled")
}

/* ---------------- 智能体 / 模型 / 会话附件 ---------------- */

@Serializable
data class Agent(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val avatar: String = "",
    @SerialName("is_builtin") val isBuiltin: Boolean = false,
    val config: JsonObject? = null
) {
    /** 智能体模式：quick-answer（快速问答）/ smart-reasoning（智能推理） */
    val agentMode: String
        get() = (config?.get("agent_mode") as? JsonPrimitive)?.content ?: ""
    val modelId: String
        get() = (config?.get("model_id") as? JsonPrimitive)?.content ?: ""
}

/**
 * 模型。type 是后端的原样取值：KnowledgeQA / Embedding / Rerank / VLLM / ASR
 * —— 注意不是前端常用的 "chat"，聊天下拉只列 KnowledgeQA。
 */
@Serializable
data class ModelInfo(
    val id: String = "",
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    val type: String = "",
    val source: String = "",
    val status: String = ""
) {
    val label: String get() = displayName.ifBlank { name }
}

/** 会话附件（临时文档）。上传后 status: uploaded → processing → ready / failed */
@Serializable
data class Attachment(
    val id: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    val status: String = ""
)

@Serializable
data class FolderInfo(
    val path: String = "",
    val name: String = "",
    /** 直接挂在该目录下的文档数 */
    @SerialName("document_count") val documentCount: Int = 0,
    /** 含子目录的文档数；服务端有时不返回，UI 上回退到 documentCount */
    @SerialName("total_count") val totalCount: Int = 0
) {
    val shownName: String get() = name.ifBlank { path }
    val shownCount: Int get() = if (totalCount > 0) totalCount else documentCount
}

@Serializable
data class FoldersEnvelope(
    @SerialName("root_document_count") val rootDocumentCount: Int = 0,
    @SerialName("total_document_count") val totalDocumentCount: Int = 0,
    val folders: List<FolderInfo> = emptyList()
)

/**
 * 一页数据 + 总数（泛型版）。
 *
 * 之前只解 `data` 数组、拿不到 `total`，客户端就只能写死 page_size=100 拉一页，
 * 于是「文档超过 100 个就看不到」——实际服务端根本没有 100 条硬上限
 * （实测 page_size=500 一次返回 361 条）。正确做法是带 total 分页拉到底。
 *
 * 做成泛型是因为产物接口（`/artifacts`）用的是同一套分页信封。
 */
data class PagedResult<T>(
    val items: List<T>,
    val total: Int
)

/** 文档列表的一页。留这个名字，调用方（ViewModel / DocsScreen）不用改 */
typealias KnowledgePage = PagedResult<Knowledge>

/* ---------------- 产物：智能体在对话里生成的文件 ---------------- */

/**
 * 一个产物。
 *
 * 字段来自官方 API 的 Artifact 结构（会话维度 `/sessions/{sid}/artifacts`
 * 与产物库 `/artifacts` 共用同一套字段，后者多一个 `session_title`）。
 *
 * 没有 `id`：**产物是靠三元组定位的** —— (session_id, message_id, index)。
 * 下载与删除地址都用这三个拼，详见 [WeKnoraApi.artifactDownloadUrl]。
 * 也正因为如此，[key] 必须三个都用上，只拿 index 会在跨会话列表里撞键。
 */
@Serializable
data class Artifact(
    @SerialName("session_id") val sessionId: String = "",
    /** 只有产物库接口会带；按会话聚合时由客户端自己补会话标题 */
    @SerialName("session_title") val sessionTitle: String = "",
    @SerialName("message_id") val messageId: String = "",
    /**
     * 产物在**该条消息**里的下标，从 0 开始。
     * 官方特别提醒：不能用会话汇总列表的下标去拼某条消息的下载地址，
     * 所以我们存的永远是消息内的原始 index。
     */
    val index: Int = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_type") val fileType: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    /** 生成时在沙箱里的路径。同一路径的多次生成在产物库里只算一行 */
    @SerialName("source_path") val sourcePath: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("version_count") val versionCount: Int = 0
) {
    /** 列表 key。三个字段缺一不可 —— 单用 index 会撞 */
    val key: String get() = "$sessionId|$messageId|$index"

    /**
     * 类型角标文字。优先从文件名取后缀（最可靠），
     * 退而取 file_type —— 它可能是扩展名也可能是 MIME，两种都兜住。
     */
    val ext: String
        get() {
            val raw = fileName.substringAfterLast('.', "")
                .ifBlank { fileType.substringAfterLast('/').substringAfterLast('.') }
            return if (raw.length in 1..5) raw.uppercase() else "FILE"
        }

    val displayName: String get() = fileName.ifBlank { sourcePath.substringAfterLast('/') }

    /** 人类可读的大小；为 0（服务端没给）时返回空串，UI 上直接不显示 */
    val sizeLabel: String
        get() = when {
            fileSize <= 0 -> ""
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 ->
                String.format(java.util.Locale.US, "%.1f KB", fileSize / 1024.0)
            else ->
                String.format(java.util.Locale.US, "%.1f MB", fileSize / 1024.0 / 1024.0)
        }

    /**
     * 时间显示成 `MM-dd HH:mm`。
     * 刻意不引日期库：服务端给的是 RFC3339 字符串，这里只做截取，
     * 解析失败（格式变了）时原样退回，不会抛异常把整页拖崩。
     */
    val timeLabel: String
        get() {
            val t = createdAt.indexOf('T')
            return if (t >= 10 && createdAt.length >= t + 6) {
                createdAt.substring(5, t + 6).replace('T', ' ')
            } else createdAt
        }
}

/* ---------------- 本轮提问的可选项 ---------------- */

data class ReasoningLevel(val value: String, val label: String)

/**
 * 官方 reasoning_effort 取值：off / auto / minimal / low / medium / high /
 * xhigh / max。空串 = 不传，沿用智能体配置（模型不支持时服务端会自动就近调整）。
 */
val REASONING_LEVELS: List<ReasoningLevel> = listOf(
    ReasoningLevel("", "默认"),
    ReasoningLevel("off", "不思考"),
    ReasoningLevel("minimal", "极简"),
    ReasoningLevel("low", "低"),
    ReasoningLevel("medium", "中"),
    ReasoningLevel("high", "高"),
    ReasoningLevel("xhigh", "极高"),
    ReasoningLevel("max", "最强"),
    ReasoningLevel("auto", "自动")
)

/* ---------------- 问答 SSE 帧 ---------------- */

@Serializable
data class StreamFrame(
    val id: String = "",
    @SerialName("response_type") val responseType: String = "",
    val content: String = "",
    val done: Boolean = false,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("assistant_message_id") val assistantMessageId: String = "",
    val data: JsonElement? = null
) {
    companion object {
        const val TYPE_AGENT_QUERY = "agent_query"
        const val TYPE_THINKING = "thinking"
        const val TYPE_ANSWER = "answer"
        const val TYPE_REFERENCES = "references"
        const val TYPE_TOOL_CALL = "tool_call"
        const val TYPE_TOOL_RESULT = "tool_result"
        const val TYPE_ERROR = "error"
        const val TYPE_COMPLETE = "complete"
    }
}

/** 一次问答在 UI 上的完整状态 */
data class ChatChunk(
    val thinking: String = "",
    val answer: String = "",
    val finished: Boolean = false,
    val finalContent: String = "",
    val durationMs: Long = 0L
)

/* ---------------- UI 侧消息 ---------------- */

/**
 * 一条检索来源（SSE `references` 帧里的一项）。
 *
 * 服务端字段是 SearchResult：`knowledge_title` / `knowledge_filename` /
 * `content` / `chunk_type` / `image_info`。这里只挑界面用得到的，
 * 全部给默认值 —— 老版本服务端少几个字段也不至于让整条流解析失败。
 */
data class ChatReference(
    val title: String = "",
    val snippet: String = "",
    /** 这条来源带的图片（`image_*` 分块的 image_info.url / original_url） */
    val imageUrls: List<String> = emptyList(),
    /** 联网搜索的网页地址 */
    val url: String = "",
    /** 来源文档 id：用它去拉该文档的分块，补「文档插图」 */
    val knowledgeId: String = "",
    /** 引用的分块下标：取图时优先取它附近的 */
    val chunkIndex: Int = -1
)

data class ChatMessage(
    val id: String = "",
    val role: Role,
    val content: String = "",
    val thinking: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
    /** 助手消息附带的工具调用名（agent 模式才有），UI 上以 chip 展示 */
    val tools: List<String> = emptyList(),
    /** 用户消息携带的附件名，展示在气泡里 */
    val attachments: List<String> = emptyList(),
    /**
     * 这条回答是否开着联网搜索。只用来改等待态的文案 ——
     * 打开联网搜索后第一轮要 70s 上下（要先搜再答），
     * 还写「思考中…」会让用户以为卡死了。
     */
    val webSearch: Boolean = false,
    /** 检索来源。回答正文之外还能看到「参考了哪几篇、有没有配图」 */
    val references: List<ChatReference> = emptyList(),
    /**
     * 从引用文档里补的插图（按引用位置就近取的资源句柄）。
     *
     * 模型经常不把图写进答案、或者写出来的句柄是编的；文档本身的分块里
     * 图是真实存在的，所以回答落地后按 knowledge_id 去拉一次分块补图。
     */
    val docImages: List<String> = emptyList()
) {
    /**
     * 这条回答要显示的图片：来源里的图 + 正文 Markdown 里的图。
     *
     * 正文里的图由 Markdown 渲染器自己画（位置跟着段落走），这里只收来源图 ——
     * 用户问「有没有架构图」时，即使模型只在正文里说「见下图」，
     * 来源里的 image 分块也能把图顶到眼前。
     */
    val referenceImages: List<String>
        get() = references.flatMap { it.imageUrls }.distinct()

    enum class Role { USER, ASSISTANT }
}
