package com.jinge.weknora.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `references` 帧的宽松解析。
 *
 * 刻意**不**用 @Serializable data class：这一块字段版本间变化很大
 * （标题可能在 knowledge_title 也可能在 knowledge_filename，
 * 图片在 image_info 里可能是数组、也可能是被序列化过的 JSON 字符串），
 * 一个字段对不上就整条流抛异常，代价太大。拿不到就当没有。
 *
 * 解析结果只服务界面：标题、摘要、配图、网页地址。
 */
object ReferenceParser {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    private val RE_MD_IMAGE = Regex("""!\[[^\]]*]\(\s*([^)\s]+)""")
    private val RE_MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val RE_TAG = Regex("""<[^>]{1,80}>""")
    private val RE_WS = Regex("""\s+""")

    fun parse(el: JsonElement?): List<ChatReference> {
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["references"] as? JsonArray)
                ?: (el["results"] as? JsonArray)
                ?: (el["data"] as? JsonArray)
                ?: return emptyList()
            else -> return emptyList()
        }

        val out = LinkedHashMap<String, ChatReference>()
        arr.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val meta = o["metadata"] as? JsonObject
            val content = text(o, "content", "snippet", "text")

            val webUrl = meta?.let { text(it, "url") }.orEmpty()
                .ifBlank { text(o, "id").takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty() }

            val title = text(o, "knowledge_title", "knowledge_filename")
                .ifBlank { meta?.let { text(it, "title") }.orEmpty() }
                .ifBlank { webUrl.substringAfter("://").substringBefore('/') }
                .ifBlank { "来源" }

            val images = buildList {
                addAll(imagesFrom(o["image_info"]))
                // 正文里内嵌的图片也算来源配图（模型常常只给一张图 + 一句话）
                RE_MD_IMAGE.findAll(content).forEach { add(it.groupValues[1]) }
            }.distinct()

            val snippet = clean(content)
            if (snippet.isBlank() && images.isEmpty() && webUrl.isBlank()) return@forEach

            val key = listOf(title, webUrl, images.firstOrNull().orEmpty()).joinToString("|")
            if (key in out) return@forEach
            out[key] = ChatReference(
                title = title,
                snippet = snippet,
                imageUrls = images,
                url = webUrl,
                knowledgeId = text(o, "knowledge_id"),
                chunkIndex = (o["chunk_index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
            )
        }
        return out.values.toList()
    }

    /** image_info 可能是数组、也可能是 JSON 字符串；元素里 url / original_url 都认 */
    private fun imagesFrom(el: JsonElement?): List<String> {        if (el == null || el is JsonNull) return emptyList()
        val arr = when (el) {
            is JsonArray -> el
            is JsonPrimitive -> runCatching { JSON.decodeFromString<JsonArray>(el.content) }.getOrNull()
            ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            text(o, "url", "original_url", "origin_url").ifBlank { null }
        }
    }

    private fun text(o: JsonObject, vararg keys: String): String {
        keys.forEach { k ->
            val v = (o[k] as? JsonPrimitive)?.content
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    /**
     * 摘要：去掉 Markdown 图片/链接/HTML 标签，压掉换行。
     * 来源列表里放原始 chunk 会把整页 markdown 塞进两行文字里，很难看。
     */
    private fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var s = RE_MD_IMAGE.replace(raw, " ")
        s = RE_MD_LINK.replace(s, "$1")
        s = RE_TAG.replace(s, " ")
        s = RE_WS.replace(s, " ").trim()
        return if (s.length > 160) s.take(160) + "…" else s
    }

    private val TRUNCATED = Regex("""(resource://[A-Za-z0-9_-]{6,})(?:\.{2,}|…)""")

    /**
     * 修掉被模型截断的图片句柄。
     *
     * 实测：模型会把 `resource://x4PkfoNNzSsuWbaV-K9w9w` 写成
     * `resource://x4PkfoNNzSsuWbaV-K9w...`（自己加省略号），
     * 客户端照着请求 `/files` 时服务端看到 `..` 直接 400 invalid file path。
     *
     * 来源分块里保存的是**完整**句柄，所以拿前缀去匹配回来即可；
     * 匹配不到就保持原样（渲染层会跳过，不再发无效请求）。
     */
    fun repairTruncatedImageUrls(text: String, knownUrls: List<String>): String {
        if (knownUrls.isEmpty()) return text
        if (!text.contains("...") && !text.contains("…")) return text
        return TRUNCATED.replace(text) { m ->
            val prefix = m.groupValues[1]
            knownUrls.firstOrNull { it.startsWith(prefix) } ?: m.value
        }
    }
}
