package com.jinge.weknora.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val handle = "resource://actual_diagram_123456"
    private val steps = """[
        {"thought":"先检索。","reasoning_content":"核对型号。","tool_calls":[
            {"name":"search_knowledge","result":{"output":"![图]($handle)"}}
        ]},
        {"thought":"答案","tool_calls":[]}
    ]"""

    private fun history(content: String, serialized: Boolean = false): JsonObject = buildJsonObject {
        put("id", "history-1")
        put("role", "assistant")
        put("content", content)
        put("agent_steps", if (serialized) JsonPrimitive(steps) else json.parseToJsonElement(steps))
    }

    @Test fun historyRestoresThoughtsToolsAndPictures() {
        val message = ChatHistoryParser.parse(history("答案"))!!
        assertEquals("答案", message.content)
        assertEquals("核对型号。\n\n先检索。", message.thinking)
        assertEquals(listOf("search_knowledge"), message.tools)
        assertEquals(listOf(handle), message.docImages)
    }

    @Test fun legacyAccumulatedPreambleIsRemovedOnlyFromTheBeginning() {
        assertEquals("答案", ChatHistoryParser.parse(history("先检索。\n\n答案"))!!.content)
        val content = "答案里引用先检索。这句话。"
        assertEquals(content, ChatHistoryParser.parse(history(content))!!.content)
    }

    @Test fun serializedStepsAndReferencesAreAccepted() {
        val record = JsonObject(history("答案", serialized = true) + ("knowledge_references" to
            JsonPrimitive("""[{"knowledge_title":"manual","content":"caption","image_info":[{"url":"$handle"}]}]""")))
        val message = ChatHistoryParser.parse(record)!!
        assertEquals(listOf(handle), message.docImages)
        assertEquals(listOf(handle), message.referenceImages)
    }

    @Test fun userTextAndUnsupportedRolesAreNotReinterpretedAsAssistantAnswers() {
        val user = json.parseToJsonElement("""{"role":"user","content":"<think>example</think>"}""").jsonObject
        assertEquals("<think>example</think>", ChatHistoryParser.parse(user)!!.content)
        for (role in listOf("tool", "system", "thinking")) {
            assertNull(ChatHistoryParser.parse(buildJsonObject { put("role", role); put("content", "internal") }))
        }
    }

    @Test fun terminalStepThoughtDoesNotHideTheFinalAnswer() {
        assertFalse(ChatHistoryParser.parse(history("答案"))!!.thinking.contains("答案"))
    }

    @Test fun toolCallMovesPlanningTextOutOfTheBodyOnce() {
        var message = ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "我先检索。", thinking = "核对型号。")
        message = message.movePreambleToThinking()
        assertEquals("", message.content)
        assertEquals("核对型号。\n\n我先检索。", message.thinking)
        assertEquals(message, message.movePreambleToThinking())
        message = message.applyAnswer(StreamFrame(content = "最终答案"))
        assertEquals("最终答案", message.content)
        assertEquals("核对型号。\n\n我先检索。", message.thinking)
    }

    @Test fun answerReplacementDoesNotDuplicateTheSnapshot() {
        val message = ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "临时文本")
        val frame = StreamFrame(content = "校正后的答案", data = buildJsonObject { put("replace", true) })
        assertEquals("校正后的答案", message.applyAnswer(frame).content)
    }

    @Test fun topLevelReferencesSurviveFrameDeserialization() {
        val frame = json.decodeFromString<StreamFrame>("""{"response_type":"references","knowledge_references":[{"content":"source"}]}""")
        assertEquals(1, ReferenceParser.parse(frame.knowledgeReferences).size)
    }
}
