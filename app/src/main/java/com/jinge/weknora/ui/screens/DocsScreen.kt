package com.jinge.weknora.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinge.weknora.data.FolderInfo
import com.jinge.weknora.data.Knowledge
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.theme.onNeutralContainer
import com.jinge.weknora.ui.theme.onSuccessContainer
import com.jinge.weknora.ui.theme.neutralContainer
import com.jinge.weknora.ui.theme.successContainer
import com.jinge.weknora.ui.theme.monoStyle

/**
 * 文档管理页。
 *
 * 自上而下：搜索 → 目录筛选 → 批量操作 → 计数 → 文档卡片列表。
 *
 * 视觉上做了两件事：
 *   1. 每行是独立卡片（白底 + 细描边），不再是连成一片的文字墙 ——
 *      361 条时前者能扫，后者只能一行行读。
 *   2. 状态用色块 pill 而不是彩色文字。颜色即语义：
 *      绿=已就绪、蓝=进行中、红=失败、灰=已取消。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsScreen(
    vm: AppViewModel,
    onPickFiles: () -> Unit,
    allKbs: Boolean = false
) {
    val ctx = LocalContext.current

    // 「全部知识库」是对话用的检索范围；文档列表必须挂在具体库上，
    // 这里给一句明确的指路，而不是显示一个空列表
    if (allKbs) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Inbox, null,
                        Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "当前是「全部知识库」",
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "文档列表需要按具体知识库查看。点右上角的切换按钮选一个库即可。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SearchRow(vm)
            FolderBar(vm)
            if (vm.selectedDocs.isNotEmpty()) BatchBar(vm)
            CountRow(vm)

            when {
                vm.docsLoading && vm.docs.isEmpty() -> LoadingState()
                vm.docs.isEmpty() -> EmptyState(vm.docFilter.isNotBlank())
                else -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vm.docs, key = { it.id }) { doc ->
                        DocCard(
                            doc = doc,
                            // 已经在具体目录里了，路径就是冗余信息
                            showFolder = vm.currentFolder.isBlank(),
                            selecting = vm.selectedDocs.isNotEmpty(),
                            selected = vm.selectedDocs.contains(doc.id),
                            onToggle = { vm.toggleSelectDoc(doc.id) },
                            onDownload = { vm.downloadDoc(ctx, doc) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onPickFiles,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = "上传文件")
        }
    }
}

@Composable
private fun SearchRow(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 一条完整的搜索框：放大镜在里、刷新在里、回车即搜。
        // 之前是「输入框 + 框外挂着两个孤零零的图标按钮」，框和图标不在同一视觉层，
        // 中间还隔着一道缝，看着像两套控件拼起来的。
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                Modifier
                    .height(46.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search, null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BasicTextField(
                    value = vm.docFilter,
                    onValueChange = { vm.docFilter = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.loadDocs() }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (vm.docFilter.isEmpty()) {
                                Text(
                                    "搜索文档",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    }
                )
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable {
                            vm.loadDocs()
                            vm.loadFolders()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 目录筛选条：全部 / 根目录 / 各目录，计数取自 folders 接口 */
@Composable
private fun FolderBar(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FolderChip(
            label = "全部",
            count = if (vm.kbDocCount > 0) vm.kbDocCount else null,
            selected = vm.currentFolder.isEmpty(),
            onClick = { vm.selectFolder("") }
        )
        // 只有确实有根目录文档时才出现，避免点进去是空的
        if (vm.rootDocCount > 0) {
            FolderChip(
                label = "根目录",
                count = vm.rootDocCount,
                selected = vm.currentFolder == "/",
                onClick = { vm.selectFolder("/") }
            )
        }
        vm.folders.forEach { f ->
            FolderChip(
                label = f.shownName,
                count = f.shownCount,
                selected = vm.currentFolder == f.path,
                onClick = { vm.selectFolder(f.path) }
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (count == null) label else "$label · $count", maxLines = 1) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Folder, null, Modifier.size(16.dp)) }
        } else null
    )
}

/**
 * 批量操作条。
 *
 * 长按任意一行进入选择模式后出现。做成一条**主色容器底的模式条**：
 * 左边「已选 N」、中间操作、右边「完成」。
 * 之前是四个 AssistChip 排在页面底色上，跟筛选 chip 长得一模一样，
 * 用户分不清「这是筛选还是操作」，而且「已选 1」排在最后，被挤出屏幕只露半个字。
 */
@Composable
private fun BatchBar(vm: AppViewModel) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已选 ${vm.selectedDocs.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarAction("重建") { vm.reparseSelected() }
                BarAction("取消解析") { vm.cancelSelected() }
                BarAction("删除", danger = true) { vm.deleteSelected() }
                MoveMenu(vm)
            }
            TextButton(onClick = { vm.clearSelection() }) {
                Text(
                    "完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** 模式条上的文字操作。不用 AssistChip：chip 的描边会和「筛选」混淆 */
@Composable
private fun BarAction(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** 移动已勾选文档到某个目录 */
@Composable
private fun MoveMenu(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarAction("移动") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("根目录") },
                onClick = { open = false; vm.moveSelectedToFolder("") }
            )
            vm.folders.forEach { f: FolderInfo ->
                DropdownMenuItem(
                    text = { Text(f.shownName, maxLines = 1) },
                    onClick = { open = false; vm.moveSelectedToFolder(f.path) }
                )
            }
        }
    }
}

/**
 * 计数行。用户只需要知道「一共有多少」；
 * 只有当拉到的条数少于总数（翻页中 / 拉取异常）时才显示分数 ——
 * 正常情况写 "361 / 361" 只会让人以为哪里出了问题。
 */
@Composable
private fun CountRow(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildString {
                if (vm.docsLoading && vm.docs.isEmpty()) {
                    // 还没读到就报「共 0 篇」是把「不知道」说成了「没有」，
                    // 空库和加载中在用户眼里完全是两件事
                    append("正在读取…")
                } else {
                    val loaded = vm.docs.size
                    if (vm.docsTotal > loaded) append("$loaded / ${vm.docsTotal} 篇")
                    else append("共 $loaded 篇")
                    if (vm.currentFolder.isNotBlank()) {
                        append(if (vm.currentFolder == "/") " · 根目录" else " · ${vm.currentFolder}")
                    }
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (vm.docsLoading) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(
            "正在读取全部文档…",
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 空态与对话页保持同一套写法：图标 + 标题 + 说明 */
@Composable
private fun EmptyState(searching: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 与对话页空态同一套托盘：圆角方块 + 中性底，形状和尺寸一致才像同一个 App
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Inbox, null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            if (searching) "没有匹配的文档" else "这个目录还是空的",
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            if (searching) "换个关键词，或清空搜索框看看全部"
            else "点右下角按钮上传文件，解析完成后就能在对话里检索",
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 文档卡片行。
 *
 * 左：类型角标（显示扩展名，中性色 —— 它只是信息，不是状态）
 * 中：标题 + 状态 pill + 目录
 * 右：下载
 *
 * 勾选框**只在批量选择模式里出现**：361 篇文档时每行左边挂一个大方框，
 * 读起来满屏都是「空复选框」，而 99% 的时间根本不在选文件。
 * 进选择模式的方式是长按任意一行；进去以后点整行即可勾选。
 */
@Composable
private fun DocCard(
    doc: Knowledge,
    showFolder: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            0.8.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(doc.id, selecting) {
                detectTapGestures(
                    onLongPress = { if (!selecting) onToggle() },
                    onTap = { if (selecting) onToggle() }
                )
            }
    ) {
        Row(
            Modifier.padding(start = if (selecting) 4.dp else 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }
            TypeBadge(doc.title)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    prettyTitle(doc.title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(doc)
                    if (showFolder && doc.folderPath.isNotBlank()) {
                        Text(
                            doc.folderPath,
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载原文",
                    Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 展示用标题：去掉 ASCII 扩展名、下划线换成空格。
 *
 * 服务端这批文档名是 `VSP_One_SDS_Cloud_for_AWS_Product_Documentation_Library.html`
 * 这种机器名，两行还读不完。去掉扩展名（角标里已经有了）并把下划线换成空格后，
 * 同一条在手机上能读成一句话，扫列表时不用再逐字拼。
 * 只剥 ASCII 扩展名 —— 中文名里的「1.2.3 配置」不能被误当成扩展名切掉。
 */
private fun prettyTitle(raw: String): String =
    raw.replace(Regex("\\.([A-Za-z0-9]{1,5})$"), "").replace('_', ' ').trim()

/** 类型角标：把扩展名显示出来，比一个通用文件图标信息量大。等宽字体读起来像仪表刻度 */
@Composable
private fun TypeBadge(title: String) {
    // 只认 ASCII 扩展名：中文名里的「1.2.3 配置」不能被误判成扩展名
    val ext = Regex("\\.([A-Za-z0-9]{1,5})$")
        .find(title)?.groupValues?.get(1)?.uppercase() ?: "FILE"
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.size(width = 36.dp, height = 34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                ext,
                style = monoStyle(MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 状态 pill。颜色 = 语义，四态一色一义：
 * 绿 已就绪 / 蓝 进行中 / 红 失败 / 灰 已取消。
 */
@Composable
private fun StatusPill(doc: Knowledge) {
    val cancelled = doc.parseStatus == "cancelled" || doc.parseStatus == "canceled"
    val bg: Color
    val fg: Color
    when {
        doc.isFailed && !cancelled -> {
            bg = MaterialTheme.colorScheme.errorContainer
            fg = MaterialTheme.colorScheme.onErrorContainer
        }
        cancelled -> {
            bg = neutralContainer()
            fg = onNeutralContainer()
        }
        doc.isBusy -> {
            bg = MaterialTheme.colorScheme.tertiaryContainer
            fg = MaterialTheme.colorScheme.onTertiaryContainer
        }
        else -> {
            bg = successContainer()
            fg = onSuccessContainer()
        }
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = bg) {
        Text(
            doc.statusLabel,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}
