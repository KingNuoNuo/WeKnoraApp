package com.jinge.weknora.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * 一个刻意做小的 Markdown 解析器，只覆盖「模型回答里实际会出现的语法子集」。
 *
 * 为什么要自己写，而不是引 Markwon / compose-markdown / richtext-compose：
 *  1. 本项目的依赖面一直压得很窄（除 material-icons 外没引过第三方 UI 库），
 *     多一个库就多一份版本兼容风险，而编译用的是本地 toolchain，换库代价不小；
 *  2. 手机窄屏对**代码块**和**表格**有硬要求 —— 必须等宽、可横向滚动、列宽有下限。
 *     通用库给的是网页式排版，直接套进来还得再包一层去纠正，不如自己写可控。
 *
 * 有意不支持的语法（遇到会当普通文本原样显示，不会崩）：
 *  HTML 标签、脚注、任务列表勾选框、四空格缩进代码块、列表嵌套超过一层。
 *
 * 这里是**纯函数**，不依赖 Compose，方便单独验证。
 */

/** 表格列对齐（来自分隔行的 `:---:` 写法） */
enum class MdAlign { START, CENTER, END }

sealed interface MdBlock {
    /** 标题，level 1..6 */
    data class Heading(val level: Int, val text: String) : MdBlock

    /** 段落。行内语法留到渲染时再解析 */
    data class Paragraph(val text: String) : MdBlock

    /** 围栏代码块（``` 或 ~~~） */
    data class Code(val lang: String, val code: String) : MdBlock

    /** 列表。ordered=false 打圆点；true 从 [start] 开始编号 */
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<String>
    ) : MdBlock

    /** 引用块 */
    data class Quote(val text: String) : MdBlock

    /** 表格 */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val aligns: List<MdAlign>
    ) : MdBlock

    /** 分隔线 */
    object Divider : MdBlock

    /**
     * 图片：`![alt](url)` 单独成块。
     *
     * 知识库里的图片（公有云 OSS / 服务端资源）都是走 Markdown 图片语法回来的，
     * 之前解析器「有意不支持图片」，于是 `![](...)` 整段原样显示成一串方括号 ——
     * 用户看到的就是「对话里看不到图片」。
     */
    data class Image(val url: String, val alt: String = "") : MdBlock
}

object Markdown {

    private val RE_FENCE = Regex("""^\s*(`{3,}|~{3,})\s*(.*)$""")
    private val RE_HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val RE_HR = Regex("""^\s*([-*_])(?:\s*\1){2,}\s*$""")
    private val RE_BULLET = Regex("""^\s*([-*+])\s+(.*)$""")
    private val RE_ORDERED = Regex("""^\s*(\d{1,9})[.)]\s+(.*)$""")

    /**
     * 图片语法。只认这两种地址：
     *  - `http(s)://…`：公有云 OSS / 反代后的公网地址，最常见
     *  - `resource://…`：WeKnora 内部句柄（服务端 resource_urls=public 时会换成 http）
     * 其他相对路径（如 `/api/v1/...`）按服务地址补全，见 [resolveImageUrl]。
     */
    /** A quoted resource handle is an image reference; actual code examples stay literal. */
    private val RE_IMAGE_OR_HANDLE = Regex(
        """`+\s*(?<codeHandle>resource://[A-Za-z0-9_-]{6,}(?:\.{2,}|…)?)\s*`+""" +
            """|`+[^`\n]*`+""" +
            """|!\[(?<alt>[^\]]*)]\(\s*(?:<(?<angle>[^>\n]+)>|(?<url>(?:[^()\s]|\([^()\s]*\))+))(?:\s+(?:"[^"]*"|'[^']*'))?\s*\)""" +
            """|\[(?<resourceLabel>[^\]\n]*)]\(\s*(?<linkedHandle>resource://[A-Za-z0-9_-]{6,}(?:\.{2,}|…)?)\s*\)""" +
            """|\[[^\]\n]*]\([^\n)]*\)""" +
            """|(?<handle>resource://[A-Za-z0-9_-]{6,}(?:\.{2,}|…)?)"""
    )

    /**
     * 裸 URL。WeKnora 会把 `resource://` 在图里重写成 `http://…/r/<token>`，
     * 但模型经常只把它写成**普通链接**而不是 `![]()` —— 网页端就显示成一串链接，
     * App 这边如果不处理，连点都点不动。所以行内也要把裸 URL 变成可点链接。
     */
    private val RE_BARE_URL = Regex("""https?://[^\s<>()\[\]"']+""")

    /** Text and pictures in their original order, also used inside lists, quotes and cells. */
    fun splitMedia(text: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        var last = 0
        for (match in RE_IMAGE_OR_HANDLE.findAll(text)) {
            val url = match.groups["url"]?.value ?: match.groups["angle"]?.value
                ?: match.groups["codeHandle"]?.value ?: match.groups["linkedHandle"]?.value
                ?: match.groups["handle"]?.value ?: continue
            val before = text.substring(last, match.range.first).trim()
            if (before.isNotEmpty()) blocks += MdBlock.Paragraph(before)
            val alt = match.groups["alt"]?.value ?: match.groups["resourceLabel"]?.value.orEmpty()
            blocks += MdBlock.Image(url, alt.takeUnless { it.contains("resource://") }.orEmpty())
            last = match.range.last + 1
        }
        val rest = text.substring(last).trim()
        if (rest.isNotEmpty()) {
            blocks += if (RE_BARE_URL.matches(rest) && isImageUrl(rest)) MdBlock.Image(rest)
            else MdBlock.Paragraph(rest)
        }
        return blocks
    }

    /** Images actually rendered in the answer; fenced and inline code are excluded. */
    fun imageUrls(markdown: String): Set<String> = parse(markdown).flatMap { block ->
        when (block) {
            is MdBlock.Image -> listOf(block.url)
            is MdBlock.Heading -> splitMedia(block.text).filterIsInstance<MdBlock.Image>().map { it.url }
            is MdBlock.Quote -> splitMedia(block.text).filterIsInstance<MdBlock.Image>().map { it.url }
            is MdBlock.ListBlock -> block.items.flatMap { splitMedia(it) }
                .filterIsInstance<MdBlock.Image>().map { it.url }
            is MdBlock.Table -> (block.header + block.rows.flatten()).flatMap { splitMedia(it) }
                .filterIsInstance<MdBlock.Image>().map { it.url }
            else -> emptyList()
        }
    }.toSet()

    fun parse(src: String): List<MdBlock> {
        if (src.isBlank()) return emptyList()

        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        val para = StringBuilder()
        var i = 0

        fun flushPara() {
            val text = para.toString().trim()
            para.setLength(0)
            if (text.isEmpty()) return

            blocks += splitMedia(text)
        }

        while (i < lines.size) {
            val t = lines[i].trim()
            val fence = RE_FENCE.find(t)
            val heading = RE_HEADING.find(t)
            val bullet = RE_BULLET.find(t)
            val ordered = RE_ORDERED.find(t)

            when {
                /* ---------------- 围栏代码块 ---------------- */
                fence != null -> {
                    flushPara()
                    // 取前三个字符做收尾标记，这样 ``` 和 ~~~ 及更长的栅栏都能配对
                    val marker = fence.groupValues[1].take(3)
                    val lang = fence.groupValues[2].trim()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(marker)) {
                        body += lines[i]
                        i++
                    }
                    if (i < lines.size) i++   // 跳过收尾那一行
                    while (body.isNotEmpty() && body.first().isBlank()) body.removeAt(0)
                    while (body.isNotEmpty() && body.last().isBlank()) body.removeLast()
                    blocks += MdBlock.Code(lang, body.joinToString("\n"))
                }

                t.isEmpty() -> {
                    flushPara()
                    i++
                }

                /* 水平线要排在列表前面判：`- - -` 也满足 bullet 的形状 */
                RE_HR.matches(t) -> {
                    flushPara()
                    blocks += MdBlock.Divider
                    i++
                }

                /* ---------------- 标题 ---------------- */
                heading != null -> {
                    flushPara()
                    blocks += MdBlock.Heading(
                        heading.groupValues[1].length,
                        heading.groupValues[2].trim()
                    )
                    i++
                }

                /* ---------------- 引用 ---------------- */
                t.startsWith(">") -> {
                    flushPara()
                    val buf = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        buf += lines[i].trim().removePrefix(">").trimStart()
                        i++
                    }
                    blocks += MdBlock.Quote(buf.joinToString("\n"))
                }

                /* ---------------- 表格 ---------------- */
                isTableStart(lines, i) -> {
                    flushPara()
                    val header = splitRow(lines[i])
                    val aligns = splitRow(lines[i + 1]).map { alignOf(it) }
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trim().isNotEmpty() && lines[i].contains('|')) {
                        rows += splitRow(lines[i])
                        i++
                    }
                    blocks += MdBlock.Table(header, rows, aligns)
                }

                /* ---------------- 列表 ---------------- */
                bullet != null || ordered != null -> {
                    flushPara()
                    val isOrdered = ordered != null
                    val items = ArrayList<String>()
                    var start = 1
                    while (i < lines.size) {
                        val cur = lines[i].trim()
                        if (cur.isEmpty() || RE_HR.matches(cur)) break
                        val m = if (isOrdered) RE_ORDERED.find(cur) else RE_BULLET.find(cur)
                        if (m == null) {
                            // 列表项的续行（缩进过的非空行）并进上一项，别另起段落
                            if (items.isNotEmpty() && lines[i].startsWith("  ")) {
                                items[items.lastIndex] = items.last() + " " + cur
                                i++
                                continue
                            }
                            break
                        }
                        if (items.isEmpty() && isOrdered) start = m.groupValues[1].toIntOrNull() ?: 1
                        items += m.groupValues[2].trim()
                        i++
                    }
                    if (items.isNotEmpty()) blocks += MdBlock.ListBlock(isOrdered, start, items)
                }

                /* ---------------- 普通段落 ---------------- */
                else -> {
                    para.appendLine(t)
                    i++
                }
            }
        }

        flushPara()
        return blocks
    }

    /**
     * 表格起始判定：本行含 `|`，且下一行是「只由 `-` `:` `|` 和空白组成、至少含一个 `-`」的分隔行。
     * 不要求首尾有管道符 —— `a | b` 这种写法也要认。
     */
    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        if (!lines[i].contains('|')) return false
        val sep = lines[i + 1].trim()
        if (sep.isEmpty() || !sep.contains('-') || !sep.contains('|')) return false
        return sep.all { it == '-' || it == ':' || it == '|' || it.isWhitespace() }
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private fun alignOf(cell: String): MdAlign = when {
        cell.startsWith(":") && cell.endsWith(":") -> MdAlign.CENTER
        cell.endsWith(":") -> MdAlign.END
        else -> MdAlign.START
    }

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

    /**
     * 这个 URL 像不像一张图。
     *
     * 除了常见后缀，还认服务端的 `/r/<token>` 能力地址 —— WeKnora 把
     * `resource://` 重写成它，图就是从那来的（日志里同一个链接 200/309KB 之后变 404，
     * 说明是短时有效的图）。非图片的 `/r/` 链接会在加载失败时降级成「图片加载失败」。
     */
    internal fun isImageUrl(url: String): Boolean {
        // 服务端的稳定图片形式就是 resource://<handle>；模型经常把它裸写在正文里，
        // 这里必须当成图处理（渲染层会带鉴权走 /files 代理取字节）
        if (url.startsWith("resource://")) return true
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in IMAGE_EXT) return true
        return Regex("""/r/[0-9A-Za-z_-]{8,}$""").containsMatchIn(path)
    }

    /* ---------------- 行内语法 ---------------- */

    /**
     * 行内 token。正则里各分支的顺序有意义：`**` 必须排在 `*` 前面，
     * 否则粗体会被当成「斜体 + 多出来的星号」。
     */
    private val INLINE_TOKEN = Regex(
        """`([^`\n]+)`""" +
            """|\*\*([^*\n]+)\*\*""" +
            """|__([^_\n]+)__""" +
            """|~~([^~\n]+)~~""" +
            """|\*([^*\n]+)\*""" +
            """|_([^_\n]+)_""" +
            """|\[([^\]\n]+)]\(([^)\n]+)\)""" +
            """|(https?://[^\s<>()\[\]"']+)"""
    )

    /**
     * 把一行文本里的行内语法转成带样式的串。
     *
     * 颜色要当参数传进来 —— 这个函数不在 composable 作用域里，取不到 MaterialTheme。
     */
    fun inline(src: String, linkColor: Color, codeBackground: Color): AnnotatedString =
        buildAnnotatedString {
            // Pictures are handled by splitMedia; preserve image syntax inside inline code.
            val cleaned = src
            var last = 0
            for (m in INLINE_TOKEN.findAll(cleaned)) {
                if (m.range.first > last) append(cleaned.substring(last, m.range.first))
                val g = m.groupValues
                val code = g[1]
                val bold = if (g[2].isNotEmpty()) g[2] else g[3]
                val strike = g[4]
                val italic = if (g[5].isNotEmpty()) g[5] else g[6]
                val linkText = g[7]
                val linkUrl = g[8]
                val bareUrl = g[9]
                when {
                    code.isNotEmpty() -> withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                    ) { append(code) }

                    bold.isNotEmpty() -> withStyle(
                        SpanStyle(fontWeight = FontWeight.Bold)
                    ) { append(bold) }

                    strike.isNotEmpty() -> withStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough)
                    ) { append(strike) }

                    italic.isNotEmpty() -> withStyle(
                        SpanStyle(fontStyle = FontStyle.Italic)
                    ) { append(italic) }

                    linkText.isNotEmpty() -> withLink(
                        LinkAnnotation.Url(
                            linkUrl,
                            TextLinkStyles(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) { append(linkText) }

                    bareUrl.isNotEmpty() -> withLink(
                        LinkAnnotation.Url(
                            bareUrl,
                            TextLinkStyles(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) { append(bareUrl) }

                    else -> append(m.value)
                }
                last = m.range.last + 1
            }
            if (last < cleaned.length) append(cleaned.substring(last))
        }
}
