package com.jinge.weknora

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jinge.weknora.ui.AppViewModel
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
    // 未配置时只显示配置页。错误/结果由 SetupScreen 自己以卡片常驻显示 ——
    // 这里不能再挂一个 LaunchedEffect 去 consume，否则提示会闪一下就没了。
    if (!vm.configured) {
        SetupScreen(vm)
        return
    }
    MainScaffold(vm)
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
    // 优先用库内总数，未加载时退回当前列表长度
    val docCount = if (vm.kbDocCount > 0) vm.kbDocCount else vm.docs.size
    return when (tab) {
        Tab.CHAT -> {
            val agent = vm.agents.firstOrNull { it.id == vm.selectedAgentId }
            "${agent?.name ?: "知识库直答"} · $docCount 篇文档"
        }
        Tab.DOCS -> "$docCount 篇文档"
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
        snackbarHost = { SnackbarHost(snackHost) },
        // 顶栏不用 TopAppBar：它的高度锁死 56/64dp，两行标题会被裁，
        // 也做不出「品牌深底 + 渐变标识」这套头部。
        topBar = {
            Surface(color = brandTopBarColor(), contentColor = Brand.OnTopBar) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 品牌标：与桌面图标同色的圆角方块，一眼对上
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(brandMarkShape(28.dp))
                                .background(Brand.Accent)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                vm.currentKb?.name ?: "未选择知识库",
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                                color = Brand.OnTopBar
                            )
                            Text(
                                topBarSubtitle(vm, tab),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.OnTopBar.copy(alpha = Brand.ON_TOP_BAR_SECONDARY_ALPHA)
                            )
                        }
                        IconButton(onClick = { vm.newSession() }) {
                            Icon(Icons.Filled.AddComment, contentDescription = "新会话")
                        }
                        Box {
                            IconButton(onClick = { kbMenu = true }) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = "切换知识库")
                            }
                            DropdownMenu(
                                expanded = kbMenu,
                                onDismissRequest = { kbMenu = false }
                            ) {
                                vm.knowledgeBases.forEach { kb ->
                                    DropdownMenuItem(
                                        text = { Text(kb.name) },
                                        onClick = { vm.selectKb(kb); kbMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
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
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
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
                    Tab.DOCS -> DocsScreen(vm, onPickFiles = { picker.launch(arrayOf("*/*")) })
                    Tab.ARTIFACTS -> ArtifactsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}
