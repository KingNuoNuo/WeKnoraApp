package com.jinge.weknora.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jinge.weknora.data.KbStats
import com.jinge.weknora.data.KnowledgeBase
import com.jinge.weknora.data.UpdateChecker
import com.jinge.weknora.data.UpdateResult
import com.jinge.weknora.data.UpdateDownloader
import androidx.core.content.FileProvider
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.theme.monoStyle
import com.jinge.weknora.ui.theme.onSuccessContainer
import com.jinge.weknora.ui.theme.successContainer
import kotlinx.coroutines.launch

/**
 * 设置页：切换/新建知识库、看当前连接、改连接参数。
 *
 * 知识库列表之前用 RadioButton 表示「当前选中」—— 语义是错的：
 * RadioButton 表示「从一组里选一个」，而这里是导航入口，
 * 点它等于切库。改成整行可点 + 选中行高亮 + 右侧对勾。
 *
 * 这一版把每行右侧那三列「总数 / 排队 / 处理」收成了一列：
 * 七个知识库就是二十一遍同样的三个词，数字反而被标签淹没了。
 * 现在只留文档总数（等宽数字 + 「篇」），排队/处理**只在大于 0 时**
 * 以彩色小标出现 —— 有活要干才提示，没活不占地方。
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var connectionExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).clickable { connectionExpanded = !connectionExpanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(title = "连接信息", modifier = Modifier.weight(1f))
            Text(if (connectionExpanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }

        if (connectionExpanded) Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                InfoLine("账号", vm.accountEmail.ifBlank { "未登录" }, mono = false)
                InfoLine("服务地址", vm.baseUrlInput, mono = true)
                if (vm.tenantIdInput.isNotBlank()) {
                    InfoLine("空间 ID", vm.tenantIdInput, mono = true)
                }
                InfoLine("当前文档", "${vm.docs.size} 篇", mono = true)
                InfoLine("会话", "${vm.sessions.size} 个", mono = true)
                OutlinedButton(onClick = { vm.editConfig() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("修改服务地址") }
                OutlinedButton(onClick = { vm.logout() }, shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
            }
        }

        UpdateSection(vm)

        // 底部留白：最后一屏内容不该贴着导航栏
        Spacer(Modifier.size(20.dp))
    }
}

/**
 * 区块标题。左边是标题，右边可以挂计数 / 刷新。
 *
 * 刷新按钮用 Box + clickable 而不是 IconButton：M3 的 IconButton 有 48dp 的
 * 最小触控尺寸，会把这一行撑得比标题高出一大截。
 */
@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (loading) {
            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
        }
        trailing?.let {
            Text(
                it,
                Modifier.padding(start = 8.dp),
                style = monoStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        onRefresh?.let {
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { it() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新文档统计",
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 知识库行：左「名称 + 说明 + 待处理提示」，右「文档总数」+ 选中对勾。
 */
@Composable
private fun KbRow(
    kb: KnowledgeBase,
    stats: KbStats?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            0.8.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    kb.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    kb.description.ifBlank { "ID ${kb.id.take(8)}" },
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 只有真的有活时才提示，避免每行都挂一排永远是 0 的数字
                val queued = stats?.queued ?: 0
                val processing = stats?.processing ?: 0
                if (queued > 0 || processing > 0) {
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (processing > 0) {
                            MiniStat(
                                "处理中 $processing",
                                MaterialTheme.colorScheme.tertiaryContainer,
                                MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (queued > 0) {
                            MiniStat(
                                "排队 $queued",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 文档总数：数字用等宽、右对齐，各行落在同一条竖线上
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stats?.total?.toString() ?: "—",
                    style = monoStyle(MaterialTheme.typography.titleMedium),
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    "篇",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selected)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 对勾槽位恒占宽度：不选中的行也要留空，否则整列数字会左右错位
            Box(
                Modifier.size(width = 26.dp, height = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前知识库",
                        Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** 排队 / 处理中的小标：只在有值时出现，颜色即状态 */
@Composable
private fun MiniStat(label: String, bg: Color, fg: Color) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = bg) {
        Text(
            label,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = fg
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            Modifier.weight(1.7f),
            style = if (mono) monoStyle(MaterialTheme.typography.bodyMedium, FontWeight.Normal)
            else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 关于 / 更新检测。
 *
 * 三件事必须能分清：**正在查 / 已是最新 / 查不到**。
 * 把网络失败显示成「已是最新」是最糟的做法 —— 用户以为没更新，
 * 实际上只是没连上更新源。
 *
 * 更新源可在本页修改：默认是仓库里的 update.json，
 * 内网部署的人可以把它放到自己的 NAS 上，不受 GitHub 连通性影响。
 */
@Composable
private fun UpdateSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    val checker = remember { UpdateChecker(ctx) }
    val current = remember { checker.current() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }
    var editSource by remember { mutableStateOf(false) }

    fun runCheck() {
        if (checking) return
        checking = true
        result = null
        scope.launch {
            result = checker.check(vm.updateUrlInput)
            checking = false
        }
    }

    SectionHeader(title = "关于", modifier = Modifier.padding(top = 10.dp))

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "WeKnora 客户端",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "私有部署知识库 · 安卓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "v${current.first} (${current.second})",
                    style = monoStyle(MaterialTheme.typography.labelMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "  正在检查更新…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                result == null -> Button(
                    onClick = { runCheck() },
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("检查更新") }

                else -> UpdateResultBlock(
                    r = result!!,
                    onRetry = { runCheck() },
                    onDownload = { url ->
                        if (downloading) return@UpdateResultBlock
                        downloading = true
                        downloadError = null
                        scope.launch {
                            runCatching {
                                val file = UpdateDownloader.download(ctx, url)
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
                                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                                } else {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/vnd.android.package-archive")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            }.onFailure { downloadError = it.message ?: "下载失败" }
                            downloading = false
                        }
                    }
                )
            }

            if (downloading) Text("正在下载 APK…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        }
    }

    if (editSource) {
        var draft by remember { mutableStateOf(vm.updateUrlInput) }
        AlertDialog(
            onDismissRequest = { editSource = false },
            title = { Text("更新源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "填一个返回 update.json 的地址。默认指向仓库；内网可以放到自己的 NAS 上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("地址") },
                        singleLine = false,
                        maxLines = 3,
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveUpdateUrl(draft)
                    editSource = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        vm.saveUpdateUrl(UpdateChecker.DEFAULT_URL)
                        editSource = false
                    }) { Text("恢复默认") }
                    TextButton(onClick = { editSource = false }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun UpdateResultBlock(
    r: UpdateResult,
    onRetry: () -> Unit,
    onDownload: (String) -> Unit
) {
    val err = r.error
    when {
        err != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline, null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    "  检查失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onRetry,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("重试") }
        }

        r.hasUpdate -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "发现新版本 v${r.latest?.version.orEmpty()}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    val notes = r.latest?.notes.orEmpty()
                    if (notes.isNotBlank()) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            val url = r.latest?.url.orEmpty()
            Button(
                onClick = { if (url.isNotBlank()) onDownload(url) },
                enabled = url.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (url.isBlank()) "更新源未提供下载地址" else "去下载")
            }
            TextButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) { Text("重新检查") }
        }

        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle, null,
                Modifier.size(16.dp),
                tint = onSuccessContainer()
            )
            Text(
                "  已是最新版本",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = onSuccessContainer()
            )
            TextButton(onClick = onRetry) { Text("重新检查") }
        }
    }
}
