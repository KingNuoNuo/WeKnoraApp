package com.jinge.weknora.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 把 Markdown 正文渲染成手机端可读的排版。
 *
 * 与网页排版的三个不同（都是为窄屏做的）：
 *  1. **代码块不折行**，改为等宽 + 横向滚动 —— 折行会把缩进结构毁掉；
 *  2. **表格横向滚动**，列宽按内容估算、同列等宽，滚动时列仍然对齐；
 *  3. **标题按级别降到正文附近的字号**，手机上 24sp 的 h1 一屏放不下两行。
 *
 * 渲染出来的每个 [MdBlock] 都是独立 composable，[SelectionContainer] 套在外层即可整体选中。
 */
@Composable
fun MarkdownBody(
    markdown: String,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit = {}
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    val textColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block, textColor)
                is MdBlock.Paragraph -> InlineText(
                    block.text,
                    MaterialTheme.typography.bodyLarge,
                    textColor
                )
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.ListBlock -> ListBlock(block, textColor)
                is MdBlock.Quote -> QuoteBlock(block, textColor)
                is MdBlock.Table -> TableBlock(block, textColor)
                is MdBlock.Image -> MarkdownImage(block.url, block.alt, onOpen = onImageClick)
                MdBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, color: Color) {
    // 4~6 级标题在手机上跟正文区分度已经很小，统一压到 labelLarge，靠字重区分
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        3 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.labelLarge
    }
    InlineText(block.text, style.copy(fontWeight = FontWeight.Bold), color)
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (block.lang.isNotBlank()) {
                Text(
                    block.lang.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
            // softWrap=false + horizontalScroll：代码行一律不折，长了就左右拖
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    block.code,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun ListBlock(block: MdBlock.ListBlock, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.Top) {
                // 有序列表的「10.」「11.」会比个位数宽，标记列给固定 22dp 右对齐，
                // 这样多位数编号时正文左边界也不会抖动
                Text(
                    if (block.ordered) "${block.start + index}." else "•",
                    Modifier.width(22.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(7.dp))
                InlineText(
                    item,
                    MaterialTheme.typography.bodyLarge,
                    color,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(block: MdBlock.Quote, color: Color) {
    // IntrinsicSize.Min 让左侧竖条跟着文字高度走，而不是撑满整列
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline)
        )
        Spacer(Modifier.width(10.dp))
        InlineText(
            block.text,
            MaterialTheme.typography.bodyMedium,
            color,
            Modifier.weight(1f)
        )
    }
}

/**
 * 表格：整体可横向滚动，列宽按「该列最长内容」估算后固定下来。
 *
 * 为什么不用 IntrinsicSize 自动对齐：它和 horizontalScroll 叠加时会拿到无限宽度约束，
 * 直接崩。固定列宽既避开了这个坑，也保证滚动时列始终对齐。
 */
/**
 * 粗估一段文本的显示宽度（dp）：全角字符 ≈15、半角 ≈8。
 *
 * 特意写成命令式的独立函数，而不是内联 `sumOf { }` —— 嵌套 lambda 里的 `sumOf`
 * 有 Int/Long/Double/BigDecimal 一串重载，会让外层 `maxOfOrNull` 也推断不出
 * 返回类型，编译直接报 Overload resolution ambiguity（踩过一次）。
 */
private fun estimateWidth(text: String): Int {
    var w = 0
    for (ch in text) w += if (ch.code > 0x2E80) 15 else 8
    return w
}

@Composable
private fun TableBlock(block: MdBlock.Table, color: Color) {
    val cols = block.header.size
    if (cols == 0) return

    val widths = remember(block) {
        (0 until cols).map { c ->
            val texts = listOfNotNull(block.header.getOrNull(c)) +
                block.rows.mapNotNull { it.getOrNull(c) }
            // 再加 18dp 内边距，夹在 64..240dp
            val w: Int = texts.maxOfOrNull { estimateWidth(it) } ?: 8
            (w + 18).coerceIn(64, 240).dp
        }
    }

    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                block.header.forEachIndexed { c, cell ->
                    TableCell(cell, widths[c], block.aligns.getOrElse(c) { MdAlign.START }, color, true)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            block.rows.forEach { row ->
                Row {
                    repeat(cols) { c ->
                        TableCell(
                            row.getOrNull(c) ?: "",
                            widths[c],
                            block.aligns.getOrElse(c) { MdAlign.START },
                            color,
                            false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: Dp, align: MdAlign, color: Color, bold: Boolean) {
    InlineText(
        text,
        MaterialTheme.typography.bodySmall.copy(
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        ),
        color,
        Modifier
            .width(width)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        textAlign = when (align) {
            MdAlign.START -> TextAlign.Start
            MdAlign.CENTER -> TextAlign.Center
            MdAlign.END -> TextAlign.End
        }
    )
}

/** 带行内样式的一段文本。所有正文最终都走这里，保证行内解析只有一份实现 */
@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val annotated = remember(text, linkColor, codeBackground) {
        Markdown.inline(text, linkColor, codeBackground)
    }
    Text(
        annotated,
        modifier,
        style = style,
        color = color,
        textAlign = textAlign
    )
}
