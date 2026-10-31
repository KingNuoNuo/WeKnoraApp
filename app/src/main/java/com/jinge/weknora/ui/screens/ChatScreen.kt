package com.jinge.weknora.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowOutward
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.jinge.weknora.data.Attachment
import com.jinge.weknora.data.ChatMessage
import com.jinge.weknora.data.ChatReference
import com.jinge.weknora.data.REASONING_LEVELS
import com.jinge.weknora.data.ReferenceParser
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.markdown.ImageLightbox
import com.jinge.weknora.ui.markdown.LocalApiBase
import com.jinge.weknora.ui.markdown.MarkdownBody
import com.jinge.weknora.ui.markdown.resolveImageUrl
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

    // 对话时不熄屏：一轮 RAG 问答经常 40s 起（联网搜索 70s+），
    // 读答案读到一半屏幕自动黑掉再解锁，体验很差。
    // 只在本页挂载期间生效，切到文档/设置页就恢复系统默认。
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    // 流式输出时跟随滚动（用最后一条消息的正文长度做触发信号）
    val lastLen = vm.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(vm.messages.size, lastLen) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.lastIndex)
        }
    }

    // 全屏看图的覆盖层状态。放在本页而不是用系统 Dialog ——
    // Dialog 会另开 window，个别 ROM 上会出现白底/尺寸异常
    var lightbox by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
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
                    kbName = vm.kbDisplayName,
                    docCount = vm.displayDocCount,
                    onSuggestion = { draft = it }
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
                            },
                            onImageClick = { src -> lightbox = src }
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

        // 覆盖在整页之上（含输入区）：看图时不该还能点到后面的按钮
        lightbox?.let { src ->
            ImageLightbox(src, "知识库图片") { lightbox = null }
        }
    }
}

/* ============================================================
 * 运行参数条
 * ============================================================ */

/**
 * 运行参数条：三个下拉（智能体 / 模型 / 思考强度）+ 一个联网搜索开关。
 *
 * 上一版只画图标不画字，四个淡紫圆圈排在一行，用户得靠空态里那段说明
 * 才知道谁是谁 —— 图标猜谜是 UI 里最亏的一笔。这一版改成**带文字的 chip**：
 * 每个 chip 自己说明自己，空态里那段「上方图标可切换…」因此可以直接删掉。
 *
 * 参数条可横向滚动：知识库直答、模型名前缀、思考强度三件事加一起，
 * 窄屏一行放不下，宁可让它滑，也不要截断成「知识库直…」。
 */
@Composable
private fun OptionBar(vm: AppViewModel) {
    val agentName = vm.agents.firstOrNull { it.id == vm.selectedAgentId }?.name ?: "直答"
    val modelName = vm.chatModels.firstOrNull { it.id == vm.selectedModelId }?.label ?: "模型默认"
    val effortName = REASONING_LEVELS.firstOrNull { it.value == vm.reasoningEffort }?.label ?: "思考默认"
    val anyCustom = vm.selectedAgentId.isNotBlank() ||
        vm.selectedModelId.isNotBlank() ||
        vm.reasoningEffort.isNotBlank() ||
        vm.webSearchEnabled

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ParamChip(
                icon = Icons.Outlined.SmartToy,
                label = agentName,
                active = vm.selectedAgentId.isNotBlank()
            ) { close ->
                DropdownMenuItem(
                    text = { Text("知识库直答（不用智能体）") },
                    onClick = { vm.selectAgent(""); close() }
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
                        onClick = { vm.selectAgent(a.id); close() }
                    )
                }
            }

            ParamChip(
                icon = Icons.Outlined.Memory,
                label = modelName,
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

            ParamChip(
                icon = Icons.Outlined.Bolt,
                label = effortName,
                active = vm.reasoningEffort.isNotBlank()
            ) { close ->
                REASONING_LEVELS.forEach { lv ->
                    DropdownMenuItem(
                        text = { Text(lv.label) },
                        onClick = { vm.reasoningEffort = lv.value; close() }
                    )
                }
            }

            ToggleChip(
                icon = Icons.Outlined.Public,
                label = "联网",
                on = vm.webSearchEnabled
            ) { vm.webSearchEnabled = !vm.webSearchEnabled }

            // 有非默认项时才出现，一键回到「全部默认」，紧跟在参数组后面
            if (anyCustom) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = 1.dp, height = 18.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            vm.selectAgent("")
                            vm.selectedModelId = ""
                            vm.reasoningEffort = ""
                            vm.webSearchEnabled = false
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = "恢复默认参数",
                        Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 参数 chip：图标 + 当前值 + 下拉箭头。
 *
 * active = 改过默认值 → 主色容器 + 主色描边，一眼能看出「这条不是默认」。
 * 当前值直接写在 chip 上，不再靠小圆点和无障碍描述暗示。
 */
@Composable
private fun ParamChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    menu: @Composable (close: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    val container =
        if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    val content =
        if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            Modifier
                .clip(shape)
                .background(container)
                .border(
                    0.8.dp,
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape
                )
                .clickable { open = true }
                .padding(start = 8.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, Modifier.size(13.dp), tint = content)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            Icon(
                Icons.Filled.ArrowDropDown, null,
                Modifier.size(14.dp),
                tint = content.copy(alpha = 0.7f)
            )
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
 * 开着用**实心信号青**填充，关着用中性底，而不是下拉那种「容器色 + 描边」。
 *
 * 对应请求体字段 `web_search_enabled`。实测开关差异：
 *   关 → 只用 search_knowledge / wiki_search（本地知识库）
 *   开 → 额外调用 web_search / web_fetch，耗时从 43s 涨到 75s
 */
@Composable
private fun ToggleChip(
    icon: ImageVector,
    label: String,
    on: Boolean,
    onToggle: () -> Unit
) {
    val shape = MaterialTheme.shapes.small
    val container =
        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow
    val content =
        if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(shape)
            .background(container)
            .border(
                0.8.dp,
                if (on) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .clickable(onClick = onToggle)
            .padding(start = 9.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = content)
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
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
    onCopy: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {}
) {
    val isUser = msg.role == ChatMessage.Role.USER
    // 服务端偶尔把工具调用的原始标记漏进正文（见 stripToolCalls 的注释）。
    // 渲染和复制都走清洗后的文本；msg.content 本身保持原文不动 ——
    // 流式增量是在它上面拼接的，清洗过的文本再接增量会串味。
    // 服务端偶尔把工具调用的原始标记漏进正文（见 stripToolCalls 的注释）。
    // 另外模型会把 `resource://<handle>` 截断成 `...K9w...`，用来源里的完整
    // 句柄补回去 —— 不补的话 /files 会因为路径里有 ".." 直接 400。
    //
    // 最后一步：把正文里裸的 `resource://` 句柄**全部删掉**。它们是模型
    // 从检索结果里抄来的内部标识，实测有相当一部分是编的（404/403）；
    // 真实图片一律以「参考来源」里的资源为准，不在正文里展示句柄串。
    val body = remember(msg.content, msg.references) {
        ReferenceParser.repairTruncatedImageUrls(
            stripToolCalls(msg.content),
            msg.referenceImages
        )
            .replace(Regex("""resource://[A-Za-z0-9_-]{6,}"""), "")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }
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
                    // 模型回答是 Markdown（标题 / 列表 / 表格 / 代码块 / 图片）。
                    // 直接塞进 Text 会连 `#`、`**`、`|` 一起原样显示 ——
                    // 这就是「对话结果不适合手机端排版」的根因。
                    SelectionContainer {
                        MarkdownBody(body, onImageClick = onImageClick)
                    }
                }
                // 来源里的配图：正文没带图时，这些是「看得见知识库图片」的唯一入口。
                // 单独加一行小标题 —— 否则缩略图紧跟在正文后面，会被当成回答内容的一部分
                if (msg.docImages.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Image, null,
                            Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "  文档插图",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    ReferenceImages(msg.docImages, onOpen = onImageClick)
                }
                if (msg.referenceImages.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Image, null,
                            Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "  来源图片",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    ReferenceImages(msg.referenceImages, onOpen = onImageClick)
                }
                if (msg.references.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ReferenceList(msg.references)
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
                msg.error?.let { err ->
                    // 服务端有时把同一条错误既塞进正文、又在 error 字段里给一遍。
                    // 正文已经写清楚了就别再红字重复一次 —— 同一句话出现两遍，
                    // 看起来像报了两个错。
                    if (err.isNotBlank() && !body.contains(err.trim())) {
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
private fun EmptyState(
    kbName: String?,
    docCount: Int,
    onSuggestion: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 空态是「欢迎 + 指路」：一个托盘 + 一句标题 + 一行当前知识库，
        // 剩下的空间交给三条可以直接点的示例问句 —— 比一大段说明文字有用
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Forum, null,
                    Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Text(
            "向知识库提问",
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            buildString {
                append(kbName ?: "未选择知识库")
                if (docCount > 0) append(" · $docCount 篇文档")
            },
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Column(
            Modifier
                .padding(top = 26.dp)
                .fillMaxWidth()
                .widthIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SuggestRow("这台设备的默认管理地址是多少？", onSuggestion)
            SuggestRow("报错代码一般怎么排查？", onSuggestion)
            SuggestRow("把配置步骤整理成一份清单", onSuggestion)
        }
    }
}

/** 空态里的一条示例问句：点一下填进输入框，不直接发送（用户还能改） */
@Composable
private fun SuggestRow(text: String, onPick: (String) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(text) }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.Outlined.ArrowOutward, null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
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
    Surface(color = MaterialTheme.colorScheme.surface) {
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
                // 附件入口并进输入行：原来它在输入框下面单独一行 + 一句说明，
                // 一行里只放一个回形针，说明文字还占了半屏宽
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(
                            0.8.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.medium
                        )
                        .clickable(onClick = onPickAttachments),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = "添加附件",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.size(8.dp))

                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 150.dp),
                    placeholder = {
                        Text(
                            "提问…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    // 输入框统一 medium(12)，与搜索条、列表卡片同一档
                    shape = MaterialTheme.shapes.medium,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Spacer(Modifier.size(8.dp))

                if (vm.streaming) {
                    FilledIconButton(
                        onClick = { vm.stopStreaming() },
                        modifier = Modifier.size(48.dp),
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
                        modifier = Modifier.size(48.dp),
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送"
                        )
                    }
                }
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

/**
 * 来源配图：横向一排缩略图，点开看大图。
 *
 * 图片来自 references 帧里 `image_*` 分块的 image_info（公有云 OSS 公网地址，
 * 或服务端按 `resource_urls=public` 换出来的临时 http 地址）。
 * 横向滚动而不是网格：手机上一屏塞两张、一眼能看出「还有更多」。
 */
@Composable
private fun ReferenceImages(urls: List<String>, onOpen: (String) -> Unit = {}) {
    val base = LocalApiBase.current

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        urls.take(6).forEach { raw ->
            val src = remember(raw, base) { resolveImageUrl(raw, base) }
            if (src.isBlank()) return@forEach
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(src)
                    .crossfade(true)
                    .build(),
                imageLoader = com.jinge.weknora.ui.markdown.LocalAppImageLoader.current
                    ?: LocalContext.current.imageLoader,
                contentDescription = "知识库图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width = 150.dp, height = 108.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpen(src) },
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "图片加载失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
}

/**
 * 参考来源。默认收成一行（「参考 3 篇」），点开才展开标题与摘要。
 *
 * 不默认展开的原因：答案本身才是主体，来源是佐证；
 * 但也不能不给 —— 用户需要知道这段回答是「从哪本手册里翻出来的」。
 */
@Composable
private fun ReferenceList(refs: List<ChatReference>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Description, null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "  参考 ${refs.size} 篇",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起来源" else "展开来源",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    refs.take(8).forEach { r ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                r.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (r.snippet.isNotBlank()) {
                                Text(
                                    r.snippet,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (r.url.isNotBlank()) {
                                Text(
                                    r.url.substringAfter("://").substringBefore('/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
