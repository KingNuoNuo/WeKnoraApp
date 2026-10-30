package com.jinge.weknora.ui.markdown

/**
 * 剥掉回答正文里漏出来的**工具调用原始标记**。
 *
 * 背景：WeKnora 偶尔会把模型输出的非标准工具调用当正文流下来。实测一例 ——
 * 「快速回答」智能体（`builtin-quick-answer`）在知识库检索「相关性不足，未用于回答」
 * 之后想改调 web_search，但输出的不是结构化工具调用，于是这段原文进了 answer：
 *
 * ```
 * 我先在知识库中检索相关文档内容。
 *
 * <tool_call>
 * <function=web_search>
 * <parameter=query>
 * Pure Storage E590 replace controller power supply procedure
 * </parameter>
 * </function>
 * </tool_call>
 * ```
 *
 * 这类文本对用户零价值（真正的工具调用服务端会用 `tool_call` 事件单独给出，
 * 客户端已经渲染成 chip 了），却会把气泡撑得很长、看起来像正经答案。
 * 所以在**渲染和复制之前**清掉。
 *
 * 为什么放在 UI 层而不是收流时剥：
 * 剥完的文本**不能**再拿去和后续增量拼接 —— 标签被截断后，
 * 下一次到达的 `</tool_call>` 就会变成孤立文本显示出来。
 * 每次拿完整原文重算一遍才是对的，所以收流保持原文、渲染时清洗。
 */

/** 完整的工具调用块，连同尾随空白一起吃掉 */
private val TOOL_BLOCK = Regex("""(?s)<tool_call\b.*?</tool_call>\s*""")

/** 未闭合的块（流式中途）：从 `<tool_call` 一路截到结尾 */
private val TOOL_OPEN = Regex("""(?s)<tool_call\b.*$""")

/** 散落的单个标记，兜底清掉（`<function=x>` / `</parameter>` 这类） */
private val STRAY_TAG = Regex("""</?(?:function|parameter|tool_call)\b[^>\n]*>""")

fun stripToolCalls(text: String): String {
    if (text.isEmpty()) return text
    // 快路径：绝大多数回答本来就不含这些东西，别白跑三遍正则
    if (!text.contains("<tool_call") && !text.contains("</tool_call") &&
        !text.contains("<function") && !text.contains("</parameter")
    ) {
        return text
    }
    return text
        .replace(TOOL_BLOCK, "")
        .replace(TOOL_OPEN, "")
        .replace(STRAY_TAG, "")
        .trim()
}
