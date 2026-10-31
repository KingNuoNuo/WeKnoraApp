package com.jinge.weknora

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jinge.weknora.data.Session
import com.jinge.weknora.ui.AppViewModel
import com.jinge.weknora.ui.markdown.LocalApiBase
import com.jinge.weknora.ui.markdown.LocalAppImageLoader
import com.jinge.weknora.ui.screens.ArtifactsScreen
import com.jinge.weknora.ui.screens.ChatScreen
import com.jinge.weknora.ui.screens.DocsScreen
import com.jinge.weknora.ui.screens.SettingsScreen
import com.jinge.weknora.ui.screens.SetupScreen
import com.jinge.weknora.ui.theme.Brand
import com.jinge.weknora.ui.theme.WeKnoraTheme
import com.jinge.weknora.ui.theme.brandMarkShape
import com.jinge.weknora.ui.theme.brandTopBarColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeKnoraTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current
    val base = vm.baseUrlInput
    // 图片请求也要带鉴权：正文里的图是 `resource://<handle>`，
    // 需要经服务端 `/files` 代理取字节（实测 API Key 可通行）。
    // 只对“服务端同源”的请求加头 —— 外链图（比如公网 OSS）绝不能带上 key。
    val auth = remember(base, vm.accountEmail, vm.tenantIdInput, vm.configured) { vm.authHeaders() }
    val imageLoader = remember(base, auth) {
        val serverHost = runCatching { java.net.URI(base).host }.getOrNull()
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val sameOrigin = serverHost != null && req.url.host.equals(serverHost, ignoreCase = true)
                if (sameOrigin && auth.isNotEmpty()) {
                    val b = req.newBuilder()
                    auth.forEach { (k, v) -> b.header(k, v) }
                    chain.proceed(b.build())
                } else {
                    chain.proceed(req)
                }
            }
            .build()
        coil.ImageLoader.Builder(ctx)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }

    // 图片地址可能是相对路径（`/api/v1/files/…`），把服务地址往下传一层，
    // 由 ui/markdown/Images.kt 负责拼成绝对地址
    CompositionLocalProvider(
        LocalApiBase provides base,
        LocalAppImageLoader provides imageLoader
    ) {
        // 未配置时只显示配置页。错误/结果由 SetupScreen 自己以卡片常驻显示 ——
        // 这里不能再挂一个 LaunchedEffect 去 consume，否则提示会闪一下就没了。
        if (!vm.configured) {
            SetupScreen(vm)
        } else {
            MainScaffold(vm)
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("对话", Icons.AutoMirrored.Filled.Chat),
    DOCS("文档", Icons.Filled.Description),
    // 「产物」= 智能体在对话里生成的文件，对齐官方 Web 端首页侧栏那一页
    ARTIFACTS("产物", Icons.Filled.FolderZip),
    SETTINGS("设置", Icons.Filled.Settings)
}

/**
 * 顶栏副标题：跟着当前 tab 走。
 *
 * 之前不管在哪个 tab 都写「智能体 · 361 篇文档」—— 站在产物页上这句是**错的**
 * （那一页一个文档也没有），在设置页上也没意义。副标题要么说当前页的事，
 * 要么就别说。
 */
private fun topBarSubtitle(vm: AppViewModel, tab: Tab): String {
    val docCount = vm.displayDocCount
    return when (tab) {
        Tab.CHAT -> {
            val agent = vm.agents.firstOrNull { it.id == vm.selectedAgentId }
            "${agent?.name ?: "知识库直答"} · $docCount 篇文档"
        }
        Tab.DOCS -> if (vm.allKbsSelected) "需选择具体知识库" else "$docCount 篇文档"
        // 还没统计完时别显示 0 —— 那个 0 会被读成「一个产物都没有」
        Tab.ARTIFACTS ->
            if (vm.artifactsLoaded) "${vm.artifacts.size} 个产物" else "正在统计产物…"
        Tab.SETTINGS -> "${vm.knowledgeBases.size} 个知识库"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.CHAT) }
    var kbMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val snackHost = remember { SnackbarHostState() }

    // 系统文件选择器放在最外层：文档页要上传，但 ActivityResult 必须在
    // Activity 的 composition 里注册，不能藏进子页面的分支里（会丢失）。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<@JvmSuppressWildcards Uri> ->
        // 整批交给 ViewModel 串行处理：逐个起协程会让每个文件各刷一次全量列表，
        // busy 状态也会互相覆盖
        vm.uploadAll(uris)
    }

    // 对话页的附件走另一条路：先挂到当前会话（POST /sessions/{sid}/attachments），
    // 提问时只带 attachment_ids，不进知识库。
    val attachPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<@JvmSuppressWildcards Uri> ->
        vm.attachFiles(uris)
    }

    LaunchedEffect(vm.snackText) {
        vm.snackText?.let {
            snackHost.showSnackbar(it)
            vm.consumeSnack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackHost) },
        // 顶栏不用 TopAppBar：它的高度锁死 56/64dp，两行标题会被裁，
        // 也做不出「墨底 + 品牌标」这套头部。
        topBar = {
            Surface(color = brandTopBarColor(), contentColor = Brand.OnTopBar) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 品牌标：和桌面图标同一套「墨底 + 信号青方块」，
                    // 里面放手册图形 —— 之前是个纯色空方块，看着像图标没加载出来
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(brandMarkShape(36.dp))
                            .background(Brand.Accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            Modifier.size(20.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            vm.kbDisplayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = Brand.OnTopBar
                        )
                        Text(
                            topBarSubtitle(vm, tab),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.OnTopBar.copy(alpha = Brand.ON_TOP_BAR_SECONDARY_ALPHA)
                        )
                    }
                    TopBarAction(Icons.Filled.AddComment, "新会话") { vm.newSession() }
                    Spacer(Modifier.width(8.dp))
                    TopBarAction(Icons.Filled.History, "会话历史") { showHistory = true }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TopBarAction(Icons.Filled.SwapHoriz, "切换知识库") { kbMenu = true }
                        DropdownMenu(
                            expanded = kbMenu,
                            onDismissRequest = { kbMenu = false }
                        ) {
                            // 「全部知识库」是默认项：把每个库的 id 一起交给检索，
                            // 不必先挑库再提问
                            DropdownMenuItem(
                                text = { Text("全部知识库（${vm.knowledgeBases.size} 个）") },
                                trailingIcon = if (vm.allKbsSelected) {
                                    { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                                } else null,
                                onClick = { vm.selectAllKb(); kbMenu = false }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            vm.knowledgeBases.forEach { kb ->
                                DropdownMenuItem(
                                    text = { Text(kb.name, maxLines = 1) },
                                    trailingIcon = if (!vm.allKbsSelected && vm.currentKb?.id == kb.id) {
                                        { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                                    } else null,
                                    onClick = { vm.selectKb(kb); kbMenu = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
                // 一根发丝线代替阴影：导航栏和白页面之间需要一道「到此为止」
                HorizontalDivider(
                    thickness = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // 切页淡入淡出，不再是硬切
            Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { t ->
                when (t) {
                    Tab.CHAT -> ChatScreen(
                        vm,
                        onPickAttachments = { attachPicker.launch(arrayOf("*/*")) }
                    )
                    Tab.DOCS -> DocsScreen(vm, onPickFiles = { picker.launch(arrayOf("*/*")) }, allKbs = vm.allKbsSelected)
                    Tab.ARTIFACTS -> ArtifactsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }

    // 会话历史：跨知识库列出最近的会话，点一条就把服务端存的消息拉回来
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            HistorySheet(vm) { session ->
                vm.openSession(session)
                showHistory = false
            }
        }
    }
}

@Composable
private fun HistorySheet(vm: AppViewModel, onPick: (Session) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshHistory() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "会话历史",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            if (vm.historyLoading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(10.dp))

        when {
            vm.historySessions.isEmpty() && !vm.historyLoading -> Text(
                "还没有历史会话",
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                vm.historySessions.forEach { s ->
                    val selected = s.id == vm.currentSessionId
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            0.8.dp,
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onPick(s) }
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                            Text(
                                s.title.ifBlank { "未命名会话" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                s.timeLabel,
                                Modifier.padding(top = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶栏上的动作按钮。
 *
 * 不用 IconButton：它在深色顶栏上只是一枚孤零零的白图标，
 * 边界不可见、触控范围也说不清。给一层 10% 白的圆底，
 * 既是触点提示，也让两个图标看起来是「一组工具」而不是散落的。
 */
@Composable
private fun TopBarAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, Modifier.size(20.dp), tint = Brand.OnTopBar)
    }
}
