package com.jinge.weknora.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : Exception(message)

/**
 * @param tenantId 可选的空间 ID。
 *   - 空间级（workspace）API Key 已绑定单一空间，留空即可；
 *   - 平台级（platform）API Key **必须**带 X-Tenant-ID，否则服务端返回
 *     409 {"code":"TENANT_REQUIRED"}（见官方 API 总览「认证方式」）。
 */
data class ConnectionConfig(
    val baseUrl: String,
    val apiKey: String,
    val tenantId: String = ""
)

/* ============================================================
 * WeKnora API 客户端
 *
 * 认证：请求头 X-API-Key（WeKnora 也接受 Authorization: Bearer，两个都发更稳）。
 * 契约来源：本机 WeKnora 前端 bundle + 实机探测确认（2026-09-30）。
 *
 * 关键路径：
 *   知识库 GET /api/v1/knowledge-bases
 *   会话   GET /api/v1/sessions?kb_id=    POST /api/v1/sessions
 *   问答   POST /api/v1/knowledge-chat/{session_id}  -> SSE
 *          body 必须带 knowledge_base_id，否则不检索、直接凭常识作答
 *   文档   GET /api/v1/knowledge-bases/{kb}/knowledge
 *   上传   POST /api/v1/knowledge-bases/{kb}/knowledge/file （字段名 file）
 *   下载   GET /api/v1/knowledge/{id}/download
 * ============================================================ */
class WeKnoraApi(private val cfg: ConnectionConfig) {

    companion object {
        /** 接口字段比 model 多且会随版本变化 —— 必须忽略未知 key */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        private const val TIMEOUT_CONNECT = 20L
        private const val TIMEOUT_NORMAL = 60L

        /**
         * OkHttpClient 必须是**单例**：它的连接池和线程池都挂在实例上，
         * 每个请求 new 一个等于每次都要重新 TCP 建连（切一次知识库要发
         * 4~5 个请求，就是 4~5 次握手；公网 HTTPS 上还要多一轮 TLS）。
         * 鉴权在请求头里，跟 client 实例无关，所以可以全局共享。
         */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_CONNECT, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_NORMAL, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_NORMAL, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 流式请求：读超时必须为 0（SSE 的两次事件之间可能隔很久，
         * 一旦设了读超时，长答案会在中途被掐断）。
         * newBuilder() 复用同一个连接池/线程池，只改超时。
         */
        private val sharedStreamClient: OkHttpClient by lazy {
            sharedClient.newBuilder()
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun base(): String = cfg.baseUrl.trimEnd('/')

    /** 普通请求：读超时 60s */
    private fun client(): OkHttpClient = sharedClient

    /** 流式请求：读超时 0 */
    private fun streamClient(): OkHttpClient = sharedStreamClient

    private fun authReq(path: String): Request.Builder {
        val b = Request.Builder()
            .url(base() + path)
            .addHeader("X-API-Key", cfg.apiKey)
            .addHeader("Authorization", "Bearer " + cfg.apiKey)
        if (cfg.tenantId.isNotBlank()) b.addHeader("X-Tenant-ID", cfg.tenantId)
        return b
    }

    /** 下载/预览走系统 DownloadManager 时也要带同样的鉴权头 */
    fun authHeaders(): Map<String, String> = buildMap {
        put("X-API-Key", cfg.apiKey)
        put("Authorization", "Bearer " + cfg.apiKey)
        if (cfg.tenantId.isNotBlank()) put("X-Tenant-ID", cfg.tenantId)
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun jsonBody(obj: JsonObject): RequestBody =
        obj.toString().toRequestBody(jsonMedia)

    /* ---------------- 结果解析 ---------------- */

    private inline fun <reified T> exec(request: Request): T {
        client().newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(parseErrorMessage(raw, resp.code), resp.code)
            }
            if (T::class == Unit::class) return Unit as T
            val env = JSON.decodeFromString<Envelope<T>>(raw)
            env.error?.let {
                throw ApiException(it.message.ifBlank { "请求失败" }, it.code)
            }
            return env.data ?: throw ApiException("服务端返回空数据")
        }
    }

    private fun parseErrorMessage(raw: String, code: Int): String {
        val msg = try {
            JSON.decodeFromString<Envelope<JsonObject>>(raw).error?.message
        } catch (_: Exception) {
            null
        }
        return when {
            !msg.isNullOrBlank() -> msg
            raw.isBlank() -> "HTTP $code"
            raw.length > 200 -> "HTTP $code"
            else -> "HTTP $code: ${raw.trim()}"
        }
    }

    /* ---------------- 连接 ---------------- */

    fun testConnection(): String {
        return try {
            val kbs = exec<List<KnowledgeBase>>(
                authReq("/api/v1/knowledge-bases").get().build()
            )
            "连接成功，共 ${kbs.size} 个知识库"
        } catch (e: ApiException) {
            throw e
        }
    }

    /* ---------------- 知识库 ---------------- */

    fun listKnowledgeBases(): List<KnowledgeBase> =
        exec(authReq("/api/v1/knowledge-bases").get().build())

    fun createKnowledgeBase(name: String, description: String = ""): KnowledgeBase =
        exec(
            authReq("/api/v1/knowledge-bases").post(
                jsonBody(buildJsonObject {
                    put("name", name)
                    put("description", description)
                })
            ).build()
        )

    /* ---------------- 会话 ---------------- */

    fun listSessions(kbId: String): List<Session> =
        exec(authReq("/api/v1/sessions?kb_id=$kbId").get().build())

    /**
     * 全部会话（跨知识库），产物页专用。
     *
     * 产物可能挂在该主体名下任何一个会话上，所以不能被当前知识库筛选框住 ——
     * 这也是产物页不复用 [listSessions] 的原因。
     */
    fun listAllSessions(page: Int = 1, pageSize: Int = 100): PagedResult<Session> =
        execPaged(authReq("/api/v1/sessions?page=$page&page_size=$pageSize").get().build())

    fun createSession(kbId: String, title: String): Session =
        exec(
            authReq("/api/v1/sessions").post(
                jsonBody(buildJsonObject {
                    put("knowledge_base_id", kbId)
                    put("title", title)
                })
            ).build()
        )

    fun deleteSession(sessionId: String) {
        exec<Unit>(authReq("/api/v1/sessions/$sessionId").delete().build())
    }

    /* ---------------- 智能体 / 模型 ---------------- */

    /** GET /api/v1/agents —— 含内置智能体；API Key 需 read_agents/chat/full */
    fun listAgents(): List<Agent> =
        exec(authReq("/api/v1/agents").get().build())

    /** GET /api/v1/models —— 读权限 Viewer+ */
    fun listModels(): List<ModelInfo> =
        exec(authReq("/api/v1/models").get().build())

    /* ---------------- 会话附件 ---------------- */

    /**
     * 上传会话附件：POST /api/v1/sessions/{sid}/attachments （multipart 字段 file）
     * 返回 202，data 里的 id 就是聊天请求要带的 attachment_ids 元素。
     * 服务端异步解析（uploaded → processing → ready）。
     */
    fun uploadAttachment(
        sessionId: String,
        fileName: String,
        mime: String,
        stream: InputStream,
        size: Long
    ): Attachment {
        val safe = fileName.replace("\"", "'").replace("\r", " ").replace("\n", " ")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", safe,
                InputStreamBody(stream, if (size > 0) size else -1L, mime.toMediaTypeOrNull())
            )
            .build()
        return exec(
            authReq("/api/v1/sessions/$sessionId/attachments").post(body).build()
        )
    }

    fun deleteAttachment(sessionId: String, attachmentId: String) {
        exec<Unit>(
            authReq("/api/v1/sessions/$sessionId/attachments/$attachmentId").delete().build()
        )
    }

    /* ---------------- 流式问答 ---------------- */

    /**
     * SSE 流式问答。
     *
     * 协议（实机抓包确认）：
     *   event:message
     *   data:{"response_type":"agent_query|thinking|answer|complete",
     *         "content":"增量文本","done":bool,"data":{...}}
     * 先来一帧 agent_query(done=true)，然后 thinking 增量，再 answer 增量，
     * 最后 complete 且 data.final_content 是完整答案。
     */
    /**
     * @param agentId   非空 → 走 /agent-chat（智能推理，会带 thinking/tool_call 事件）；
     *                  空   → 走 /knowledge-chat（知识库问答）
     * @param modelId   总结模型 summary_model_id；智能体模式下服务端会忽略它，
     *                  始终用智能体自身配置的模型。
     * @param reasoningEffort  off/auto/minimal/low/medium/high/xhigh/max；
     *                  留空 = 沿用智能体配置。
     * @param webSearch 本轮是否允许联网搜索。
     *                  实测（本机 10.0.0.4:8080，agent-chat + builtin-smart-reasoning）：
     *                    false → 只用 search_knowledge / wiki_search
     *                    true  → 多出 web_search / web_fetch
     *                  显式传 false 能关掉「智能体自己开着联网」的默认行为，
     *                  所以这里**总是**发送，不做「省略即跟随」。
     *                  代价是明显变慢（实测 43s → 75s），默认给 false。
     */
    fun streamChat(
        sessionId: String,
        query: String,
        kbId: String,
        agentId: String = "",
        modelId: String = "",
        reasoningEffort: String = "",
        attachmentIds: List<String> = emptyList(),
        webSearch: Boolean = false
    ): Flow<StreamFrame> =
        flow {
            val useAgent = agentId.isNotBlank()
            val body = jsonBody(buildJsonObject {
                put("query", query)
                // 知识库必须在每次提问时显式带上，否则模型会「不检索」直接凭常识作答。
                //
                // 官方字段是复数 knowledge_base_ids（数组）；旧版本只认单数
                // knowledge_base_id。两个都发，新旧服务端都能命中检索。
                put("knowledge_base_ids", idsArray(listOf(kbId)))
                put("knowledge_base_id", kbId)
                if (useAgent) {
                    put("agent_enabled", true)
                    put("agent_id", agentId)
                }
                if (modelId.isNotBlank()) put("summary_model_id", modelId)
                if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
                if (attachmentIds.isNotEmpty()) put("attachment_ids", idsArray(attachmentIds))
                // 官方字段 web_search_enabled（bool）。服务端只认 bool，
                // 传字符串会 400：cannot unmarshal string into ... of type bool
                put("web_search_enabled", webSearch)
            })
            val path = if (useAgent) "/api/v1/agent-chat/$sessionId"
                       else "/api/v1/knowledge-chat/$sessionId"
            val req = authReq(path)
                .post(body)
                .addHeader("Accept", "text/event-stream")
                .build()

            val call = streamClient().newCall(req)

            // ---- 让「停止生成」真正断开连接 ----
            //
            // 只 cancel 协程是不够的：读超时为 0，readUtf8Line() 又是阻塞调用，
            // 协程取消后线程仍会卡在 read 上，把服务端的答案读到算完为止
            // （连接不断开，服务端也以为没人听）。
            //
            // 这里在同一个 coroutineScope 里挂一个哨兵子协程：子协程会被父协程的
            // 取消**立即**传导到，于是它在 finally 里掐断 HTTP 调用，阻塞读抛
            // IOException，整条流真正结束。
            //
            // 不能用 Job.invokeOnCompletion —— 它只在协程「结束」时触发，而卡在
            // 阻塞读里的协程根本结束不了，会互相死等。
            coroutineScope {
                val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    val resp = call.execute()
                    if (!resp.isSuccessful) {
                        val code = resp.code
                        val raw = resp.body?.string().orEmpty()
                        resp.close()
                        throw ApiException(parseErrorMessage(raw, code), code)
                    }
                    resp.use {
                        val source = it.body?.source()
                            ?: throw ApiException("服务端无响应内容")
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val json = line.removePrefix("data:").trim()
                            if (json.isBlank() || json == "[DONE]") continue
                            val frame = try {
                                JSON.decodeFromString<StreamFrame>(json)
                            } catch (_: Exception) {
                                null
                            }
                            if (frame != null) emit(frame)
                        }
                    }
                } catch (e: IOException) {
                    // 连接可能是被我们自己的 watchdog 掐断的（用户点了停止 / 切了库）。
                    // 那种情况下抛出来的是 "Socket closed"，属于**取消**而不是网络故障，
                    // 必须转成 CancellationException，否则 UI 会弹「生成中断：Socket closed」。
                    if (!currentCoroutineContext().isActive) {
                        throw CancellationException("流已取消", e)
                    }
                    throw e
                } finally {
                    // 正常结束时也叫一次：此时响应已读完，cancel 是无害的
                    watchdog.cancel()
                }
            }
        }.flowOn(Dispatchers.IO)

    /* ---------------- 文档 ---------------- */

    /**
     * 文档列表（单页）。
     *
     * @param folderPath  目录筛选。实测取值：
     *                    不传/空串 = 全部；`/` = 仅根目录；`PDL` = 该目录（含子目录）。
     * @param pageSize    服务端未强制 100 上限（实测 500 也收），但别赌它永远如此 ——
     *                    调用方应配合 [KnowledgePage.total] 循环翻页。
     */
    fun listKnowledge(
        kbId: String,
        page: Int = 1,
        pageSize: Int = 100,
        keyword: String = "",
        parseStatus: String = "",
        folderPath: String = ""
    ): KnowledgePage {
        val q = buildList {
            add("page=$page")
            add("page_size=$pageSize")
            if (keyword.isNotBlank()) add("keyword=" + enc(keyword))
            if (parseStatus.isNotBlank()) add("parse_status=" + enc(parseStatus))
            if (folderPath.isNotBlank()) add("folder_path=" + enc(folderPath))
        }.joinToString("&")
        return execPaged(authReq("/api/v1/knowledge-bases/$kbId/knowledge?$q").get().build())
    }

    /**
     * 带 total 的列表解析。
     *
     * 与 [exec] 的区别：列表接口把总条数放在**外层** `total`，
     * 只有同时取出来才能判断「还有没有下一页」。
     * 泛型化后文档列表与产物库共用（两者的分页信封结构一致）。
     */
    private inline fun <reified T> execPaged(request: Request): PagedResult<T> {
        client().newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(parseErrorMessage(raw, resp.code), resp.code)
            }
            val env = JSON.decodeFromString<Envelope<List<T>>>(raw)
            env.error?.let { throw ApiException(it.message.ifBlank { "请求失败" }, it.code) }
            val items = env.data ?: emptyList()
            // 老版本可能不给 total：退化为「本页条数」，调用方据此只拉一页，不会死循环
            return PagedResult(items, env.total ?: items.size)
        }
    }

    fun listFolders(kbId: String): FoldersEnvelope =
        exec(authReq("/api/v1/knowledge-bases/$kbId/knowledge/folders").get().build())

    /**
     * 上传文件。字段名必须是 file（MultipartBuilder 的默认 name）。
     * 流式发送，不把整个文件读进内存 —— 100MB 以上手册也安全。
     */
    fun upload(kbId: String, fileName: String, mime: String, stream: InputStream, size: Long) {
        // 不能对文件名做 urlencode：WeKnora 不解百分号编码，会原样存成 %E6%B5%8B…
        val safe = fileName.replace("\"", "'").replace("\r", " ").replace("\n", " ")
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", safe,
                InputStreamBody(stream, if (size > 0) size else -1L, mime.toMediaTypeOrNull())
            )
            // 官方字段名是 fileName（N 大写）：显式给一次，避免某些版本按
            // multipart 里的 filename 解析中文名时丢字。
            .addFormDataPart("fileName", safe)
        exec<Unit>(
            authReq("/api/v1/knowledge-bases/$kbId/knowledge/file")
                .post(bodyBuilder.build())
                .build()
        )
    }

    fun reparse(id: String) {
        exec<Unit>(authReq("/api/v1/knowledge/$id/reparse").post(emptyJson()).build())
    }

    fun batchReparse(kbId: String, ids: List<String>) {
        exec<Unit>(
            authReq("/api/v1/knowledge/batch-reparse").post(
                jsonBody(buildJsonObject {
                    put("kb_id", kbId)
                    put("ids", idsArray(ids))
                    // 实测：不带 process_config 时部分版本会当作「无配置」而使用
                    // 旧解析器，必须显式给一个空对象表示「沿用知识库默认配置」
                    put("process_config", buildJsonObject { })
                })
            ).build()
        )
    }

    /** 取消解析没有批量接口，只能逐条发 */
    fun cancelParse(id: String) {
        exec<Unit>(authReq("/api/v1/knowledge/$id/cancel-parse").post(emptyJson()).build())
    }

    fun deleteKnowledge(id: String) {
        exec<Unit>(authReq("/api/v1/knowledge/$id").delete().build())
    }

    fun batchDelete(kbId: String, ids: List<String>) {
        exec<Unit>(
            authReq("/api/v1/knowledge/batch-delete").post(
                jsonBody(buildJsonObject {
                    put("kb_id", kbId)
                    put("ids", idsArray(ids))
                })
            ).build()
        )
    }

    /** 改目录：folder_path 为空串表示移回根目录 */
    fun changeFolder(kbId: String, ids: List<String>, folderPath: String) {
        exec<Unit>(
            authReq("/api/v1/knowledge/folder").post(
                jsonBody(buildJsonObject {
                    put("kb_id", kbId)
                    put("knowledge_ids", idsArray(ids))
                    put("folder_path", folderPath)
                })
            ).build()
        )
    }

    fun createFolder(kbId: String, folderPath: String) {
        exec<Unit>(
            authReq("/api/v1/knowledge/folder").post(
                jsonBody(buildJsonObject {
                    put("kb_id", kbId)
                    put("knowledge_ids", idsArray(emptyList()))
                    put("folder_path", folderPath)
                })
            ).build()
        )
    }

    /** ids / knowledge_ids 后端按 JSON 数组解析，不能拼成逗号字符串 */
    private fun idsArray(ids: List<String>): JsonArray = buildJsonArray {
        ids.forEach { add(JsonPrimitive(it)) }
    }

    /* ============================================================
     * 产物：智能体在对话里生成的文件
     *
     * 官方 Web 端首页侧栏有一个「产物」页，App 这边对齐它。
     * 但两端的取数路径**不一样**，原因见 [listArtifacts] 的注释。
     * ============================================================ */

    /**
     * 某个会话的产物。
     *
     * 响应里**没有 total**（实测 `{"data":[...],"success":true}`），一次给全，
     * 所以这里直接返回列表而不是分页结果。
     *
     * 这是 App 里唯一真正能取到产物的接口 —— 见 [listArtifacts]。
     */
    fun listSessionArtifacts(sessionId: String): List<Artifact> =
        exec(authReq("/api/v1/sessions/$sessionId/artifacts").get().build())

    /**
     * 跨会话产物库 `GET /api/v1/artifacts` —— 官方 Web 端「产物」页用的就是它。
     *
     * **App 里它几乎总是空的，所以只当补充来源**：官方文档写明它的范围
     * 「与会话列表的 `source=web` 一致 …… IM 渠道、网页挂件（embed）和
     * **API Key 会话一律不含**」。而 App 全程用 API Key 认证，产生的会话
     * source=api，一个都不会出现在这里（本机实测 total=0，确实如此）。
     *
     * 换来的好处是不用为 Web 端场景做特判：将来若换成 JWT 登录，
     * 这条路径会自动开始有数据。
     */
    fun listArtifacts(
        page: Int = 1,
        pageSize: Int = 100,
        keyword: String = "",
        fileTypes: String = ""
    ): PagedResult<Artifact> {
        val q = buildList {
            add("page=$page")
            add("page_size=$pageSize")
            if (keyword.isNotBlank()) add("keyword=" + enc(keyword))
            if (fileTypes.isNotBlank()) add("file_types=" + enc(fileTypes))
        }.joinToString("&")
        return execPaged(authReq("/api/v1/artifacts?$q").get().build())
    }

    /**
     * 产物下载地址。仍旧交给 DownloadManager，鉴权头由 [authHeaders] 提供。
     *
     * 必须用产物自己那条消息的 messageId 和**消息内**下标 ——
     * 官方明确警告：拿会话汇总列表的下标去拼消息级地址会下到别的文件。
     * 所以 [Artifact.index] 存的永远是原始 index，任何时候都不重新编号。
     */
    fun artifactDownloadUrl(a: Artifact): String =
        base() + "/api/v1/sessions/${a.sessionId}/messages/${a.messageId}/artifacts/${a.index}/download"

    /**
     * 删除产物。**会回收对象存储里的字节，不可恢复** ——
     * 调用方必须先弹确认框，不能直接删。
     *
     * @param allVersions 连同同一 `source_path` 的历史版本一起删。
     *   会话级接口默认 false（只删当前这一版）。
     */
    fun deleteArtifact(a: Artifact, allVersions: Boolean = false) {
        var path = "/api/v1/sessions/${a.sessionId}/messages/${a.messageId}/artifacts/${a.index}"
        if (allVersions) path += "?all_versions=true"
        exec<Unit>(authReq(path).delete().build())
    }

    /* ---------------- 下载 / 预览（返回可直连 URL，由调用方带鉴权下载） ---------------- */

    fun downloadUrl(id: String) = base() + "/api/v1/knowledge/$id/download"
    fun previewUrl(id: String) = base() + "/api/v1/knowledge/$id/preview"

    private fun emptyJson() = "{}".toRequestBody(jsonMedia)

    private fun String.toMediaTypeOrNull() =
        try {
            toMediaType()
        } catch (_: Exception) {
            "application/octet-stream".toMediaType()
        }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private class InputStreamBody(
        private val input: InputStream,
        private val len: Long,
        private val type: okhttp3.MediaType?
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType? = type
        override fun contentLength(): Long = len
        override fun writeTo(sink: BufferedSink) {
            input.source().use { sink.writeAll(it) }
        }

        /**
         * 必须声明为一次性 body。
         *
         * OkHttp 默认以为 body 可以重放：碰到陈旧连接（stale connection）时它会
         * 自动换个连接**重新发一次**。而这里的 InputStream 在第一次 writeTo 之后
         * 已经被消费完了 —— 重放会写出 0 字节或者直接挂住，
         * 表现就是「大文件上传偶尔卡死 / 传上去是个空文件」。
         * 声明 isOneShot=true 后，OkHttp 就不会对这类请求做透明重试。
         */
        override fun isOneShot(): Boolean = true
    }
}

/** 从 complete 帧里取 total_duration_ms（缺失时返回 0） */
fun frameDurationMs(frame: StreamFrame): Long {
    val obj = frame.data as? JsonObject ?: return 0L
    return obj["total_duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
}
