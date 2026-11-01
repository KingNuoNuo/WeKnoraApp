package com.jinge.weknora.ui.markdown

import com.jinge.weknora.data.ReferenceParser

data class AssistantContent(val body: String, val thinking: String)

private val CONTENT_TOKEN = Regex(
    """(?im)^ {0,3}(`{3,}|~{3,})[^\n]*$|`+[^`\n]*`+|</?(?:think|thinking)\s*>"""
)

/** Keep raw stream deltas intact; separate tagged reasoning only for display and copying. */
fun prepareAssistantContent(
    content: String,
    thinking: String = "",
    imageUrls: List<String> = emptyList()
): AssistantContent {
    val body = StringBuilder()
    val thoughts = StringBuilder()
    var depth = 0
    var fence = ""
    var last = 0

    fun append(text: String) {
        if (depth > 0) thoughts.append(text) else body.append(text)
    }

    for (match in CONTENT_TOKEN.findAll(content)) {
        append(content.substring(last, match.range.first))
        val token = match.value
        val marker = match.groups[1]?.value
        when {
            fence.isNotEmpty() -> {
                append(token)
                if (marker != null && marker.first() == fence.first() &&
                    marker.length >= fence.length && token.trim() == marker
                ) fence = ""
            }
            marker != null -> {
                fence = marker
                append(token)
            }
            token.startsWith('`') -> append(token)
            token.startsWith("</") -> {
                if (depth > 0) {
                    depth--
                    if (depth == 0) thoughts.append('\n')
                }
            }
            else -> depth++
        }
        last = match.range.last + 1
    }

    var tail = content.substring(last)
    // An SSE delta can end at "<thi". Don't flash partial tags into the answer.
    if (fence.isEmpty()) {
        val start = tail.lastIndexOf('<')
        if (start >= 0) {
            val partial = tail.substring(start).lowercase()
            if (listOf("<think>", "</think>", "<thinking>", "</thinking>")
                    .any { it.startsWith(partial) }
            ) tail = tail.substring(0, start)
        }
    }
    append(tail)

    val combinedThinking = listOf(thinking.trim(), thoughts.toString().trim())
        .filter { it.isNotEmpty() }.distinct().joinToString("\n\n")
        .replace(Regex("""resource://[A-Za-z0-9_-]{6,}(?:\.{2,}|…)?"""), "图片资源")
    return AssistantContent(
        body = ReferenceParser.repairTruncatedImageUrls(stripToolCalls(body.toString()), imageUrls).trim(),
        thinking = combinedThinking
    )
}
