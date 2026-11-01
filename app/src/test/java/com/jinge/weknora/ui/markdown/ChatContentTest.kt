package com.jinge.weknora.ui.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ChatContentTest {
    private val handle = "resource://real_image_handle_123456"

    @Test fun resourceImageStaysBetweenItsSurroundingParagraphs() {
        val answer = prepareAssistantContent("第一步\n\n![接线图]($handle)\n\n第二步")
        assertEquals(listOf(
            MdBlock.Paragraph("第一步"), MdBlock.Image(handle, "接线图"), MdBlock.Paragraph("第二步")
        ), Markdown.parse(answer.body))
    }

    @Test fun truncatedImageUsesRetrievedDocumentHandle() {
        val answer = prepareAssistantContent("![图](resource://real_image...)", imageUrls = listOf(handle))
        assertEquals(setOf(handle), Markdown.imageUrls(answer.body))
    }

    @Test fun unresolvedTruncatedHandleIsNotMistakenForACompleteImage() {
        assertEquals(listOf(MdBlock.Image("resource://unknown_handle...")),
            Markdown.splitMedia("resource://unknown_handle..."))
    }

    @Test fun picturesInListsQuotesHeadingsAndTablesAreRetained() {
        val content = """
            ## 接线图 ![图]($handle)

            1. 连接 ![图]($handle) 后通电

            > 参考 ![图]($handle)

            | 图 |
            | --- |
            | ![图]($handle) |
        """.trimIndent()
        assertEquals(setOf(handle), Markdown.imageUrls(content))
        val list = Markdown.parse(content).filterIsInstance<MdBlock.ListBlock>().single()
        assertEquals(listOf(MdBlock.Paragraph("连接"), MdBlock.Image(handle, "图"),
            MdBlock.Paragraph("后通电")), Markdown.splitMedia(list.items.single()))
    }

    @Test fun imageUrlsCanContainParenthesesAndTitles() {
        assertEquals(listOf(MdBlock.Image("https://example.com/diagram(1).png", "图")),
            Markdown.splitMedia("![图](https://example.com/diagram(1).png \"接线图\")"))
        assertEquals(listOf(MdBlock.Image("/files/diagram.png", "图")),
            Markdown.splitMedia("![图](</files/diagram.png>)"))
    }

    @Test fun codeExamplesAreNeverLoadedAsImages() {
        val literal = "`![图]($handle)`"
        assertEquals(listOf(MdBlock.Paragraph(literal)), Markdown.splitMedia(literal))
        assertTrue(Markdown.imageUrls("```markdown\n![图]($handle)\n```\n$literal").isEmpty())
        assertEquals("![图]($handle)", Markdown.inline(literal, Color.Blue, Color.Gray).text)
    }

    @Test fun resourceLinksBecomePicturesWhileOrdinaryLinksRemainLinks() {
        val link = "[下载]($handle)"
        assertEquals(listOf(MdBlock.Image(handle, "下载")), Markdown.splitMedia(link))
        val ordinary = "[下载文档](https://example.com/manual.pdf)"
        assertEquals(listOf(MdBlock.Paragraph(ordinary)), Markdown.splitMedia(ordinary))
    }

    @Test fun quotedResourceHandlesBecomePicturesInsteadOfVisibleInternalIds() {
        assertEquals(listOf(MdBlock.Paragraph("见"), MdBlock.Image(handle), MdBlock.Paragraph("所示。")),
            Markdown.splitMedia("见 `$handle` 所示。"))
        assertEquals(listOf(MdBlock.Image(handle)), Markdown.splitMedia("``$handle``"))
        assertEquals(listOf(MdBlock.Image(handle)), Markdown.splitMedia("[$handle]($handle)"))
    }

    @Test fun thinkingUsesAFriendlyLabelForInternalImageAddresses() {
        val answer = prepareAssistantContent("<think>查看 $handle 后回答</think>答案")
        assertEquals("查看 图片资源 后回答", answer.thinking)
        assertFalse(answer.thinking.contains("resource://"))
    }

    @Test fun taggedThinkingIsSeparatedFromTheAnswer() {
        assertEquals(AssistantContent("答案", "检查资料"),
            prepareAssistantContent("<think>检查资料</think>\n答案"))
        assertEquals(AssistantContent("答案", "检查资料"),
            prepareAssistantContent("<THINKING>检查资料</THINKING>答案"))
    }

    @Test fun thinkingNeverLeaksDuringIncrementalStreaming() {
        val content = "<think>secret reasoning</think>answer"
        val closingEnd = content.indexOf("answer")
        for (end in 1..closingEnd) {
            assertEquals("prefix $end", "", prepareAssistantContent(content.take(end)).body)
        }
        assertEquals("answer", prepareAssistantContent(content).body)
    }

    @Test fun stoppedUnclosedThinkingRemainsOutsideTheAnswer() {
        assertEquals(AssistantContent("", "还在检索"), prepareAssistantContent("<think>还在检索"))
    }

    @Test fun frameThinkingAndTaggedThinkingAreCombinedWithoutRepeatingIdenticalText() {
        assertEquals(AssistantContent("答案", "检索\n\n核对"),
            prepareAssistantContent("<think>核对</think>答案", thinking = "检索"))
        assertEquals("检索", prepareAssistantContent("<think>检索</think>答案", thinking = "检索").thinking)
    }

    @Test fun thinkingTagsInsideCodeExamplesStayVisible() {
        for (fence in listOf("```", "~~~~")) {
            val code = "$fence\n<think>literal</think>\n$fence\n答案"
            assertEquals(AssistantContent(code, ""), prepareAssistantContent(code))
        }
        val inline = "标签示例 `<think>literal</think>`"
        assertEquals(AssistantContent(inline, ""), prepareAssistantContent(inline))
    }

    @Test fun multipleThinkingBlocksPreserveTheAnswerOrder() {
        assertEquals(AssistantContent("前言\n答案\n结论", "第一轮\n第二轮"),
            prepareAssistantContent("前言\n<think>第一轮</think>答案\n<think>第二轮</think>结论"))
    }

    @Test fun cleaningRetainsCodeIndentationAndRealImages() {
        val content = "<think>检查</think><tool_call>internal</tool_call>" +
            "```\n    indented\n```\n![图]($handle)"
        val answer = prepareAssistantContent(content)
        assertFalse(answer.body.contains("internal"))
        assertTrue(answer.body.contains("    indented"))
        assertTrue(Markdown.imageUrls(answer.body).contains(handle))
    }
}
