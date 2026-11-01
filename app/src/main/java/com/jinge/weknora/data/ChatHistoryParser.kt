package com.jinge.weknora.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

/** Restore agent steps separately from the final answer, including their retrieved pictures. */
object ChatHistoryParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val resource = Regex("""resource://[A-Za-z0-9_-]{6,}""")

    private fun decoded(value: JsonElement?): JsonElement? =
        if (value is JsonPrimitive && value.isString)
            runCatching { json.parseToJsonElement(value.content) }.getOrNull()
        else value

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun parse(message: JsonObject): ChatMessage? {
        val role = when (message.text("role")) {
            "user" -> ChatMessage.Role.USER
            "assistant" -> ChatMessage.Role.ASSISTANT
            else -> return null
        }
        var content = message.text("content")
        val thoughts = mutableListOf<String>()
        val tools = linkedSetOf<String>()
        val images = linkedSetOf<String>()
        val references = ReferenceParser.parse(decoded(message["knowledge_references"])).toMutableList()
        if (role == ChatMessage.Role.ASSISTANT) {
            message.text("thinking").ifBlank { message.text("reasoning_content") }
                .takeIf { it.isNotBlank() }?.let { thoughts += it }
            val steps = decoded(message["agent_steps"]) as? JsonArray
            steps?.forEach { value ->
                val step = value as? JsonObject ?: return@forEach
                step.text("reasoning_content").takeIf { it.isNotBlank() }?.let { thoughts += it }
                val calls = decoded(step["tool_calls"]) as? JsonArray
                val workCalls = calls.orEmpty().mapNotNull { it as? JsonObject }
                    .filterNot { it.text("name") == "final_answer" }
                if (workCalls.isNotEmpty() || step.text("progress").isNotBlank()) {
                    val preamble = step.text("progress").ifBlank { step.text("thought") }.trim()
                    if (preamble.isNotBlank()) {
                        thoughts += preamble
                        // Some older servers saved streamed preambles before the final answer.
                        // Remove only a verified step at the beginning, never guess from wording.
                        if (content.trimStart().startsWith(preamble)) {
                            content = content.trimStart().removePrefix(preamble).trimStart()
                        }
                    }
                }
                workCalls.forEach tool@{ call ->
                    call.text("name").takeIf { it.isNotBlank() }?.let { tools += it }
                    val result = decoded(call["result"]) as? JsonObject ?: return@tool
                    resource.findAll(result.toString()).forEach { images += it.value }
                    references += ReferenceParser.parse(decoded(result["data"]))
                    references += ReferenceParser.parse(decoded(result["output"]))
                }
            }
        }
        val thinking = thoughts.distinct().joinToString("\n\n")
        if (content.isBlank() && thinking.isBlank() && images.isEmpty()) return null
        return ChatMessage(
            id = message.text("id").ifBlank { "h" + UUID.randomUUID() },
            role = role,
            content = content,
            thinking = thinking,
            tools = tools.toList(),
            references = references.distinct(),
            docImages = images.take(6)
        )
    }
}

/** A tool call confirms that preceding answer tokens were a planning preamble. */
fun ChatMessage.movePreambleToThinking(): ChatMessage {
    if (content.isBlank()) return this
    return copy(
        content = "",
        thinking = listOf(thinking.trim(), content.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    )
}

/** A replacement answer is a snapshot, not another delta to append. */
fun ChatMessage.applyAnswer(frame: StreamFrame): ChatMessage {
    val replace = ((frame.data as? JsonObject)?.get("replace") as? JsonPrimitive)?.booleanOrNull == true
    return copy(content = if (replace) frame.content else content + frame.content)
}
