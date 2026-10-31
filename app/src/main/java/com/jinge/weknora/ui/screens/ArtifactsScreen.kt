package com.jinge.weknora.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinge.weknora.data.Artifact
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.theme.monoStyle

/**
 * 产物页：智能体在对话里生成的文件（导出的表格、画好的图、写的脚本…）。
 *
 * 对齐官方 Web 端首页侧栏那个「产物」页，但**取数路径不同** —— 官方那个
 * 接口（`GET /api/v1/artifacts`）明确不含 API Key 会话，而 App 全程用 API Key，
 * 所以这里走的是按会话聚合，细节见 `AppViewModel.loadArtifacts`。
 *
 * 布局自上而下与文档页保持同一套：搜索 → 类型筛选 → 计数 → 卡片列表。
 * 复用同一套视觉语言（独立卡片 + 类型角标 + 细描边），
 * 这样从「文档」切到「产物」不会有换了个 App 的感觉。
 */
@Composable
fun ArtifactsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    /** 待确认删除的产物。删除不可恢复，必须过一道对话框 */
    var pendingDelete by remember { mutableStateOf<Artifact?>(null) }

    // 进入页面才拉。没聊过天（数据没过期）就直接用缓存，不再扫一遍会话
    LaunchedEffect(Unit) { vm.ensureArtifacts() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArtifactSearchRow(vm)
            // 只有一种类型时筛选条没有意义，藏掉
            if (vm.artifactTypes.size > 1) ArtifactTypeBar(vm)
            ArtifactCountRow(vm)

            val list = vm.visibleArtifacts
            val error = vm.artifactsError
            when {
                vm.artifactsLoading && vm.artifacts.isEmpty() -> ArtifactLoading()
                // 失败优先于空态：拉不到 ≠ 没有。顺序反了就会把网络问题说成「还没有产物」
                vm.artifacts.isEmpty() && error != null ->
                    ArtifactError(error) { vm.loadArtifacts() }
                // 首帧兜底：`artifactsLoading = true` 是在协程体里设的，
                // 首帧它还来得及是 false，只看 loading 会让空态先闪一下再变转圈
                !vm.artifactsLoaded -> ArtifactLoading()
                list.isEmpty() -> ArtifactEmpty(
                    filtered = vm.artifactFilter.isNotBlank() || vm.artifactType.isNotBlank()
                )
                else -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(list, key = { it.key }) { a ->
                        ArtifactCard(
                            a = a,
                            onDownload = { vm.downloadArtifact(ctx, a) },
                            onDelete = { pendingDelete = a }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ArtifactDeleteDialog(
            a = target,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                vm.deleteArtifact(target)
            }
        )
    }
}

/**
 * 搜索行。
 *
 * 只有「刷新」一个按钮：关键词是**本地实时过滤**（见 AppViewModel.visibleArtifacts），
 * 敲字那一刻列表就变了，再挂一个「搜索」按钮属于点了没有任何新效果的控件。
 * 文档页那个搜索按钮是有用的（它才触发服务端 keyword 查询），产物页不该照抄。
 */
@Composable
private fun ArtifactSearchRow(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 与文档页同一条搜索框（关键词是本地实时过滤，回车只用来收键盘）
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
                    value = vm.artifactFilter,
                    onValueChange = { vm.artifactFilter = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (vm.artifactFilter.isEmpty()) {
                                Text(
                                    "搜索产物或会话",
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
                        .clickable { vm.loadArtifacts() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "重新扫描产物",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 类型筛选条。类型是从已拉到的产物里现算的，不写死扩展名清单 */
@Composable
private fun ArtifactTypeBar(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = vm.artifactType.isEmpty(),
            onClick = { vm.artifactType = "" },
            label = { Text("全部") }
        )
        vm.artifactTypes.forEach { t ->
            FilterChip(
                selected = vm.artifactType == t,
                onClick = { vm.artifactType = t },
                label = { Text(t) }
            )
        }
    }
}

@Composable
private fun ArtifactCountRow(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildString {
                when {
                    // 同样的原则：数据没到手之前不要报数。
                    // 「共 0 个产物」在加载中和读取失败时都是编造出来的结论
                    vm.artifactsError != null && vm.artifacts.isEmpty() -> append("未能读取")
                    !vm.artifactsLoaded -> append("正在统计…")
                    else -> {
                        val shown = vm.visibleArtifacts.size
                        val all = vm.artifacts.size
                        if (shown == all) append("共 $all 个产物")
                        else append("$shown / $all 个产物")
                    }
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (vm.artifactsLoading) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ArtifactLoading() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(
            "正在扫描各会话的产物…",
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 加载失败态。
 *
 * 刻意**不**退化成一个空列表 + 一句「还没有产物」：那是编造出来的结论。
 * 「拉不到」和「确实没有」是两件事，混在一起会让人以为攒了半年的产物全没了。
 */
@Composable
private fun ArtifactError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.ErrorOutline, null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Text(
            "产物没读到",
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            message,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 10.dp)) {
            Text("重试")
        }
    }
}

/**
 * 空态。**必须区分「筛没筛」和「有没有」**：
 * 什么都没筛却是空的，说明确实还没产生过产物 —— 这时候要讲清楚
 * 「怎么才会有产物」，否则用户会以为功能坏了。
 */
@Composable
private fun ArtifactEmpty(filtered: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 与文档页 / 对话页同一套空态托盘：圆角方块 + 中性底，只变尺寸不变形状
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
            if (filtered) "没有匹配的产物" else "还没有产物",
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            if (filtered) "换个关键词，或把类型切回「全部」"
            else "在对话里让智能体产出文件（导出表格、画图、写脚本等），" +
                "生成的文件会自动汇总到这里，可直接下载",
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 产物卡片。
 *
 * 左：类型角标（扩展名，中性色 —— 它只是信息，不是状态）
 * 中：文件名 + 「会话标题 · 大小 · 时间」
 * 右：下载 / 删除
 */
@Composable
private fun ArtifactCard(
    a: Artifact,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtifactBadge(a.ext)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    a.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
                Text(
                    buildString {
                        a.sessionTitle.ifBlank { "对话" }.let { append(it) }
                        if (a.sizeLabel.isNotEmpty()) append(" · ").append(a.sizeLabel)
                        if (a.timeLabel.isNotBlank()) append(" · ").append(a.timeLabel)
                        // 同一路径有多次生成时把版本数点出来，省得奇怪「怎么少了几版」
                        if (a.versionCount > 1) append(" · ${a.versionCount} 版")
                    },
                    Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            // 显式 40dp：IconButton 默认触控区是 48dp，两个摞起来要吃掉 96dp 行宽，
            // 长文件名会被压得很窄。删除仍然常驻但与下载同色同尺寸 ——
            // 它靠确认框兜底，不靠视觉恐吓
            IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载",
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除该产物",
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 类型角标：与文档页同一套写法，扩展名比通用文件图标信息量大 */
@Composable
private fun ArtifactBadge(ext: String) {
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
 * 删除确认。
 *
 * 文案里必须出现「不可恢复」：服务端会真的回收对象存储里的字节，
 * 跟「从列表里去掉」完全是两回事，用户有权在点之前知道。
 */
@Composable
private fun ArtifactDeleteDialog(
    a: Artifact,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这个产物？") },
        text = {
            // 注意别在这里写 Markdown：Compose 的 Text 不解析 ** 语法，会原样显示星号
            Text(
                buildString {
                    append(a.displayName)
                    append("\n\n")
                    append("文件会被彻底删除，不可恢复。")
                    if (a.versionCount > 1) {
                        append("该文件还有 ${a.versionCount} 个历史版本，会一并删除。")
                    }
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
