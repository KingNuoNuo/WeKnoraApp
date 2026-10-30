package com.jinge.weknora.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jinge.weknora.data.Attachment
import com.jinge.weknora.data.ChatMessage
import com.jinge.weknora.data.REASONING_LEVELS
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.markdown.MarkdownBody
import com.jinge.weknora.ui.markdown.stripToolCalls

/**
 * 对话页。
 *
 * 布局自上而下：运行参数条（智能体 / 模型 / 思考强度 / 联网搜索）→ 消息列表 → 输入区。
 * 参数条做成常驻的一行图标：改选只影响「下一条」提问，不会打断正在流式输出的回答。
 *
 * 附件入口只保留输入区下方那一个 —— 之前参数条上也有一个，两个入口做同一件事，
 * 位置还隔着半屏，属于纯冗余。参数条那个位置腾给「联网搜索」开关。
 */
@Composable
fun ChatScreen(
    vm: AppViewModel,
    onPickAttachments: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    // 输入草稿留在 UI 层：纯界面状态，进 ViewModel 会让每次敲字触发整屏重组
    var draft by rememberSaveable { mutableStateOf("") }

    // 流式输出时跟随滚动（用最后一条消息的正文长度做触发信号）
    val lastLen = vm.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(vm.messages.size, lastLen) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.lastIndex)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            // 不显式刷底色：Scaffold 的 containerColor 本来就是 background，
            // 其他三个页面都没重刷，这里再涂一层只是多一道绘制指令
            .imePadding()
            .navigationBarsPadding()
    ) {
        OptionBar(vm)

        // 顶部分隔：流式输出时变成一条细进度线，不打断阅读
        Box(Modifier.fillMaxWidth()) {
            if (vm.streaming) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.messages.isEmpty()) {
                EmptyState(
                    kbName = vm.currentKb?.name,
                    agentOn = vm.selectedAgentId.isNotBlank(),
                    webSearch = vm.webSearchEnabled
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // animateItem：新消息淡入、位置变化有过渡，不再是硬插硬跳
                    items(vm.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            msg = msg,
                            modifier = Modifier.animateItem(),
                            onCopy = { text ->
                                clipboard.setText(AnnotatedString(text))
                                vm.snackText = "已复制"
                            }
                        )
                    }
                }
            }
        }

        Composer(
            vm = vm,
            draft = draft,
            onDraftChange = { draft = it },
            onPickAttachments = onPickAttachments,
            onSend = {
                if (draft.isNotBlank()) {
                    vm.send(draft)
                    draft = ""
                }
            }
        )
    }
}

/* ============================================================
 * 运行参数条
 * ============================================================ */

/**
 * 运行参数条：三个下拉（智能体 / 模型 / 思考强度）+ 一个联网搜索开关。
 *
 * 只显示图标不显示文字 —— 中文 + 英文模型名塞进 chip 会把一行挤爆。
 * 三个下拉的「当前值」通过小圆点 + 底色高亮表达，实际取值在
 * contentDescription 里（无障碍朗读，界面上看不见）。
 * 联网搜索是即时开关，语义不同，用「实心主色 = 开」单独区分（见 [WebSearchToggle]）。
 */
@Composable
private fun OptionBar(vm: AppViewModel) {
    // 仅用于无障碍朗读与「恢复默认」的显示判断，不出现在界面上
    val agentName = vm.agents.firstOrNull { it.id == vm.selectedAgentId }?.name ?: "知识库直答"
    val modelName = vm.chatModels.firstOrNull { it.id == vm.selectedModelId }?.label ?: "默认模型"
    val effortName = REASONING_LEVELS.firstOrNull { it.value == vm.reasoningEffort }?.label ?: "默认"
    val anyCustom = vm.selectedAgentId.isNotBlank() ||
        vm.selectedModelId.isNotBlank() ||
        vm.reasoningEffort.isNotBlank() ||
        vm.webSearchEnabled

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OptionIcon(
                icon = Icons.Outlined.SmartToy,
                description = "智能体：$agentName",
                active = vm.selectedAgentId.isNotBlank()
            ) { close ->
                DropdownMenuItem(
                    text = { Text("知识库直答（不用智能体）") },
                    onClick = { vm.selectedAgentId = ""; close() }
                )
                vm.agents.forEach { a ->
                    DropdownMenuItem(
                        // material3 1.3 的 DropdownMenuItem 没有 supportingText，
                        // 描述拼进标题里，超出用省略号
                        text = {
                            Text(
                                if (a.description.isBlank()) a.name
                                else "${a.name} · ${a.description}",
                                maxLines = 1
                            )
                        },
                        onClick = { vm.selectedAgentId = a.id; close() }
                    )
                }
            }

            OptionIcon(
                icon = Icons.Outlined.Memory,
                description = "模型：$modelName",
                active = vm.selectedModelId.isNotBlank()
            ) { close ->
                DropdownMenuItem(
                    text = { Text("默认模型（跟随知识库配置）") },
                    onClick = { vm.selectedModelId = ""; close() }
                )
                vm.chatModels.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label, maxLines = 1) },
                        onClick = { vm.selectedModelId = m.id; close() }
                    )
                }
            }

            OptionIcon(
                icon = Icons.Outlined.Bolt,
                description = "思考强度：$effortName",
                active = vm.reasoningEffort.isNotBlank()
            ) { close ->
                REASONING_LEVELS.forEach { lv ->
                    DropdownMenuItem(
                        text = { Text(lv.label) },
                        onClick = { vm.reasoningEffort = lv.value; close() }
                    )
                }
            }

            WebSearchToggle(
                on = vm.webSearchEnabled,
                onToggle = { vm.webSearchEnabled = !vm.webSearchEnabled }
            )

            // 有非默认项时才出现，一键回到「全部默认」。
            // 紧跟在参数组后面并用细竖线分隔 —— 之前靠 Spacer 顶到最右边，
            // 中间空一大片，视觉上像个掉队的孤儿，还得来回扫视才能配上对。
            if (anyCustom) {
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(width = 1.dp, height = 20.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                IconButton(
                    onClick = {
                        vm.selectedAgentId = ""
                        vm.selectedModelId = ""
                        vm.reasoningEffort = ""
                        vm.webSearchEnabled = false
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = "恢复默认参数",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 带下拉菜单的图标按钮；active 时用主色容器 + 右上角小圆点标出「已改过」 */
@Composable
private fun OptionIcon(
    icon: ImageVector,
    description: String,
    active: Boolean,
    menu: @Composable (close: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box {
            FilledTonalIconButton(
                onClick = { open = true },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(icon, contentDescription = description, Modifier.size(20.dp))
            }
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-5).dp, y = 5.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu { open = false }
        }
    }
}

/**
 * 联网搜索开关。
 *
 * 跟旁边三个下拉不是一个语义：那三个是「选项」（点开列表里挑一个），
 * 这个是「开关」（点一下就切换，没有中间态）。所以视觉上也分开表达 ——
 * 开着用**实心主色**填充，关着用中性灰，而不是下拉那种「容器色 + 小圆点」。
 *
 * 对应请求体字段 `web_search_enabled`。实测开关差异：
 *   关 → 只用 search_knowledge / wiki_search（本地知识库）
 *   开 → 额外调用 web_search / web_fetch，耗时从 43s 涨到 75s
 */
@Composable
private fun WebSearchToggle(on: Boolean, onToggle: () -> Unit) {
    FilledTonalIconButton(
        onClick = onToggle,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (on) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            Icons.Outlined.Public,
            contentDescription = if (on) "联网搜索：已开启，点按关闭" else "联网搜索：已关闭，点按开启",
            modifier = Modifier.size(20.dp)
        )
    }
}

/* ============================================================
 * 消息
 * ============================================================ */

/**
 * 一条消息。
 *
 * 正文可长按选中复制：Compose 的 [Text] **默认不支持文本选择**，必须显式包一层
 * [SelectionContainer]（长按 → 拖选 → 系统复制菜单）。此前整页一个都没有，
 * 所以长按毫无反应 —— 这就是「对话内容无法复制」的原因。
 *
 * 助手回答另外挂一个一键复制按钮：答案动辄上千字，在手机上拖着选到一半手滑
 * 就得重来；按钮用来兜住这种长文本场景。用户自己的提问短，长按选中就够，
 * 不给按钮是为了不让每个气泡都多出一行控件。
 */
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {}
) {
    val isUser = msg.role == ChatMessage.Role.USER
    // 服务端偶尔把工具调用的原始标记漏进正文（见 stripToolCalls 的注释）。
    // 渲染和复制都走清洗后的文本；msg.content 本身保持原文不动 ——
    // 流式增量是在它上面拼接的，清洗过的文本再接增量会串味。
    val body = remember(msg.content) { stripToolCalls(msg.content) }
    // 圆角统一 16，只在「贴着自己那一侧」的下角收窄，指示方向
    val bubbleShape = if (isUser)
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    else
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            // 助手气泡用「白底 + 细描边」而不是阴影：
            // 浅色模式下 shadowElevation=1dp 几乎看不见，等于白加一层成本
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface,
            border = if (isUser) null
            else BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
            // 两边不等宽：用户提问是自己敲的短句，窄气泡更像「我说的话」；
            // 助手回答给到 560dp —— 原来一律 320dp，手机上频繁折行，
            // 表格和代码块更是被挤成一条竖线。这里是上限不是定宽，
            // 短回答（「好的」）仍会自然收窄。
            modifier = Modifier.widthIn(max = if (isUser) 300.dp else 560.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isUser) {
                    if (msg.attachments.isNotEmpty()) {
                        AttachmentRow(msg.attachments, onSurface = true)
                    }
                    SelectionContainer {
                        Text(
                            msg.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    return@Surface
                }

                if (msg.tools.isNotEmpty()) {
                    ToolChips(msg.tools)
                }
                if (msg.thinking.isNotBlank()) {
                    ThinkingBlock(msg.thinking)
                }
                if (body.isNotBlank()) {
                    // 模型回答是 Markdown（标题 / 列表 / 表格 / 代码块）。
                    // 直接塞进 Text 会连 `#`、`**`、`|` 一起原样显示 ——
                    // 这就是「对话结果不适合手机端排版」的根因。
                    SelectionContainer {
                        MarkdownBody(body)
                    }
                }
                // 一键复制。只在回答真的落地（非流式、有正文）时出现 ——
                // 流式过程中正文还在变，给按钮反而会让人复制到半截
                if (!msg.streaming && body.isNotBlank()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { onCopy(body) },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制这条回答",
                                Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                msg.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                // 还没出内容：给一个「思考中」的等待态，免得看起来像卡死
                AnimatedVisibility(
                    visible = msg.streaming && msg.content.isBlank() && msg.thinking.isBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            // 联网搜索要先搜再答，第一轮 70s 上下，得说清楚在干什么
                            if (msg.webSearch) "  正在联网搜索…" else "  思考中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolChips(tools: List<String>) {
    Row(
        Modifier.padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tools.take(4).forEach { t ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Build, null,
                        Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        "  $t",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 思考过程：默认折叠成一行，点开看全文。
 * 放在气泡里会很长，直接全展开会把答案挤到屏幕外。
 */
@Composable
private fun ThinkingBlock(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Psychology, null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "  思考过程",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        Modifier.size(18.dp)
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        thinking.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(names: List<String>, onSurface: Boolean) {
    Column(
        Modifier.padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        names.forEach { n ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (onSurface)
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.AttachFile, null, Modifier.size(14.dp),
                        tint = if (onSurface) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "  $n",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (onSurface) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/* ============================================================
 * 空态 / 输入区
 * ============================================================ */

@Composable
private fun EmptyState(kbName: String?, agentOn: Boolean, webSearch: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 圆形托盘，与文档页 / 产物页一致；这里用 84dp + 主色容器，
        // 是因为首屏空态要承担「欢迎 + 引导」，和列表页那种功能性空态不同量级
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Forum, null,
                    Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Text(
            "有什么想问的？",
            Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            buildString {
                append("知识库：${kbName ?: "未选择"}")
                append(if (agentOn) " · 智能体模式" else " · 知识库直答")
                if (webSearch) append(" · 联网搜索已开")
                append("\n上方图标可切换智能体、模型、思考强度，地球图标是联网搜索开关。")
            },
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Composer(
    vm: AppViewModel,
    draft: String,
    onDraftChange: (String) -> Unit,
    onPickAttachments: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 已挂上的附件：可单独移除，随下一条提问一起发出
            if (vm.attachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    vm.attachments.forEach { att -> AttachedChip(att, vm) }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp, max = 160.dp),
                    placeholder = {
                        Text(
                            "向知识库提问…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    // 输入框统一 medium(14)，与文档页 / 产物页 / 设置页取同一个 token
                    shape = MaterialTheme.shapes.medium,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                Spacer(Modifier.size(8.dp))

                if (vm.streaming) {
                    FilledIconButton(
                        onClick = { vm.stopStreaming() },
                        modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止生成")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        modifier = Modifier.size(52.dp),
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送"
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPickAttachments, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = "添加附件",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "附件会随本条提问一起发送，发完自动清空",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AttachedChip(att: Attachment, vm: AppViewModel) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AttachFile, null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                " ${att.fileName}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 140.dp)
            )
            IconButton(
                onClick = { vm.removeAttachment(att) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "移除", Modifier.size(14.dp))
            }
        }
    }
}
