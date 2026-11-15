package com.jinge.weknora.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jinge.weknora.data.ImageSaver

/**
 * 服务地址。图片地址可能是相对路径（`/api/v1/files/…`），
 * 需要拼上配置里那台服务端才能加载。由 MainActivity 在根节点提供。
 */
val LocalApiBase = staticCompositionLocalOf { "" }

/**
 * 带鉴权的图片加载器（MainActivity 里构建：只对同源请求加 API Key）。
 *
 * 不用 Coil 自带的 `LocalImageLoader`：它在 2.6 已标记 deprecated，
 * 且文档明确说「provide 不会设置单例」，容易出现两个 ImageLoader 各建一套缓存。
 */
val LocalAppImageLoader = staticCompositionLocalOf<coil.ImageLoader?> { null }

/**
 * 图片加载用的网络客户端（已带同源鉴权与网络切换重解析）。
 *
 * 只在**图片解码失败**时用到：回答正文里可能嵌的不是图片 —— 智能体整理完 PDF
 * 之后会用 `![标题](resource://句柄)` 引用它，服务端如实返回
 * `application/pdf`，Coil 解不出来。这时探一下真实类型，改成文档卡片。
 */
val LocalApiHttpClient = staticCompositionLocalOf<okhttp3.OkHttpClient?> { null }

/** 下载鉴权头。交给内置阅读器打开文档时要用，和接口请求用的是同一套。 */
val LocalApiAuthHeaders = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * 把图片地址补成可加载的绝对地址。
 *
 * 三种来源：
 *  - `https://bucket.oss-cn-…/a.png` —— 公有云 OSS 公网地址，原样用
 *  - `resource://…` —— WeKnora 内部句柄。只有服务端按 `resource_urls=public`
 *    返回时才会换成 http；万一还是句柄，这里交给 Coil 走失败态，
 *    而不是把 `resource://` 当普通网址硬加载
 *  - `/api/v1/…` —— 相对路径，拼服务地址
 */
fun resolveImageUrl(raw: String, base: String): String {
    val url = raw.trim()
    val origin = base.trimEnd('/')
    // 被截断的句柄（`resource://x4Pk...` / `…`）发出去也只会拿到 400，
    // 这里直接判空，由渲染层跳过，不再产生无效请求
    if (url.endsWith("...") || url.endsWith("…")) return ""
    return when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        // 知识库正文里的图：`resource://<handle>`。
        // 服务端有一个 /files 代理专门把这句柄换成字节，且认 X-API-Key
        // （实测 200 image/png），所以拼成 `{base}/files?file_path=<encoded>`。
        // 鉴权头由 MainActivity 里的 Coil ImageLoader 统一加，只加给同源请求。
        url.startsWith("resource://") && origin.isNotBlank() ->
            origin + "/files?file_path=" + android.net.Uri.encode(url)
        url.startsWith("/") && origin.isNotBlank() -> origin + url
        else -> url
    }
}

/** 探测到的远端文件类型。`size` 单位字节，拿不到时为 0。 */
private data class RemoteFile(val mime: String, val size: Long) {
    /** 能被 Coil 直接显示的才算图片。 */
    val isImage: Boolean get() = mime.startsWith("image/")
}

/**
 * 探测结果的进程内缓存。
 *
 * LazyColumn 滚出屏幕再滚回来会重建 item，`remember` 保不住；
 * 同一个句柄在同一屏里也常常出现多次（正文提到 + 图片区都引用它）。
 * 没有这层缓存，每滚一次就多一次网络往返。
 *
 * 资源句柄指向的是不可变对象，缓存不会过期。
 */
private object ProbeCache {
    private val hits = android.util.LruCache<String, RemoteFile>(64)
    private val misses = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    fun find(url: String): RemoteFile? = hits.get(url)

    /** 之前探过且失败 —— 不要再试一遍。 */
    fun knownMiss(url: String): Boolean = misses.contains(url)

    fun put(url: String, value: RemoteFile?) {
        if (value == null) misses.add(url) else hits.put(url, value)
    }
}

/**
 * 探地址背后到底是什么类型。
 *
 * 不用 HEAD：服务端（nginx 后面只挂了 GET）对 HEAD 直接回 404。
 * 改用 `Range: bytes=0-0` —— 只下 1 个字节，响应头里带着 Content-Type，
 * 真实大小在 `Content-Range: bytes 0-0/627770` 里。
 *
 * 任何失败都返回 null，调用方按「普通图片加载失败」处理，不再叠加新错误。
 */
private fun probeRemoteFile(client: okhttp3.OkHttpClient?, url: String): RemoteFile? {
    if (client == null || url.isBlank()) return null
    ProbeCache.find(url)?.let { return it }
    if (ProbeCache.knownMiss(url)) return null
    // 探测只为拿响应头，别让共用的 60 秒读超时把占位框转成转圈地狱
    val quick = client.newBuilder()
        .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val probed = runCatching {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        quick.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val mime = response.header("Content-Type").orEmpty()
                .substringBefore(';').trim().lowercase()
            val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: 0L
            RemoteFile(mime.ifBlank { "application/octet-stream" }, total)
        }
    }.getOrNull()
    ProbeCache.put(url, probed)
    return probed
}

/** 给人看的体积：613 KB / 1.2 MB。 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${(bytes + 1023) / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** mime 到内置阅读器 `format` 参数的映射，认不出就返回 null。 */
private fun readerFormatOf(mime: String): String? = when {
    mime == "application/pdf" -> "pdf"
    mime.startsWith("image/") -> "image"
    mime.startsWith("text/") -> "text"
    mime.contains("wordprocessingml") -> "docx"
    mime.contains("presentationml") -> "pptx"
    mime.contains("html") -> "html"
    mime.contains("json") || mime.contains("xml") -> "text"
    else -> null
}

/**
 * 回答正文里的一张图。
 *
 * 宽度撑满气泡、高度封顶 420dp（用 Fit，不裁切），点一下全屏看原图。
 * 加载失败时显示「图片加载失败 + 域名」，而不是留一块空白 ——
 * 空白在用户看来就是「图没出来」，和「这张图挂了」是两回事。
 */
@Composable
fun MarkdownImage(
    url: String,
    alt: String,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit = {}
) {
    val base = LocalApiBase.current
    val src = remember(url, base) { resolveImageUrl(url, base) }
    if (src.isBlank()) return
    val context = LocalContext.current
    val request = remember(context, src) {
        ImageRequest.Builder(context).data(src).crossfade(true).build()
    }

    SubcomposeAsyncImage(
        model = request,
        imageLoader = LocalAppImageLoader.current ?: LocalContext.current.imageLoader,
        contentDescription = alt.ifBlank { "知识库图片" },
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 420.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { onOpen(src) },
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        error = { ImageFallback(src, alt) }
    )
}

/**
 * 图片加载失败之后的「分诊」。
 *
 * Coil 失败有两种完全不同的原因，不该给同一个表情：
 *  - **真的取不到**（断网、401、404、图挂了）→ 维持原来的「加载失败 + 域名」
 *  - **取到了但不是图片**（最常见的是 PDF：智能体整理完文档后用
 *    `![标题](resource://句柄)` 引用它，服务端如实回 `application/pdf`）
 *    → 换成文档卡片，点一下用内置阅读器打开
 *
 * 探测只在失败时发生，正常显示的图零开销；一屏里同一条消息重绘会走
 * `remember(src)` 缓存，不会反复请求。
 */
@Composable
private fun ImageFallback(src: String, alt: String) {
    val client = LocalApiHttpClient.current
    val headers = LocalApiAuthHeaders.current
    val context = LocalContext.current
    var probed by remember(src) { mutableStateOf(false) }
    var file by remember(src) { mutableStateOf<RemoteFile?>(null) }

    LaunchedEffect(src, client) {
        file = withContext(Dispatchers.IO) { probeRemoteFile(client, src) }
        probed = true
    }

    val remote = file
    when {
        // 探测还没回来：先占住位置，别先闪一下「加载失败」再变成文档卡片
        !probed -> ProbePlaceholder()
        remote != null && !remote.isImage -> {
            val doc = remote
            DocumentCard(
                title = alt.ifBlank { "文档" },
                remote = doc,
                onOpen = { openDocument(context, src, alt, doc, headers) }
            )
        }
        else -> BrokenImage(src)
    }
}

/** 探测期间的中性占位，样式与图片加载中一致。 */
@Composable
private fun ProbePlaceholder() {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 420.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/**
 * 文档卡片：回答里嵌了非图片资源时，用它替掉「加载失败」。
 *
 * 标题取 markdown 的 alt —— 智能体一般会把文档名写在那里，
 * 比句柄可读得多；没写就退化成「文档」。
 */
@Composable
private fun DocumentCard(title: String, remote: RemoteFile, onOpen: () -> Unit) {
    val secondary = listOf(
        readerFormatOf(remote.mime)?.uppercase() ?: remote.mime,
        formatSize(remote.size)
    ).filter { it.isNotBlank() }.joinToString(" · ")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.Description,
                null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    secondary.ifBlank { "点击打开" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 用内置阅读器打开——PDF 走 NativePdfActivity，其余交给 DocumentReaderActivity 分流。 */
private fun openDocument(
    context: android.content.Context,
    src: String,
    title: String,
    remote: RemoteFile,
    headers: Map<String, String>
) {
    val format = readerFormatOf(remote.mime)
    if (format == null) {
        Toast.makeText(context, "暂不支持预览这种文件（${remote.mime}）", Toast.LENGTH_SHORT).show()
        return
    }
    val bundle = android.os.Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } }
    runCatching {
        context.startActivity(
            android.content.Intent(context, com.jinge.weknora.DocumentReaderActivity::class.java)
                .putExtra("url", src)
                .putExtra("title", title.ifBlank { "文档" })
                .putExtra("format", format)
                .putExtra("headers", bundle)
        )
    }.onFailure {
        Toast.makeText(context, "打不开这个文件：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun BrokenImage(src: String) {
    val host = remember(src) {
        src.substringAfter("://", src).substringBefore('/').ifBlank { src }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.BrokenImage, null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f)) {
            Text(
                "图片加载失败",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                host,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 全屏看原图。黑底、Fit、点任意处关闭。
 *
 * 刻意不做双击缩放/拖动：这类手势和「点击关闭」抢事件，
 * 在手机上很容易变成怎么点都关不掉。要看细节直接双指系统缩放页面即可。
 *
 * 用一个铺满父容器的 Box，而不是系统 Dialog：Dialog 会另开一个 window，
 * 部分 ROM（以及这台模拟器）上会拿到错误的 windowBackground / 尺寸，
 * 出现「白底面板、图不显示」的怪状态。应用内覆盖层是确定性的。
 */
@Composable
fun ImageLightbox(src: String, alt: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val loader = LocalAppImageLoader.current ?: context.imageLoader
    val scope = rememberCoroutineScope()
    var saving by remember(src) { mutableStateOf(false) }
    val save: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                try {
                    val result = loader.execute(ImageRequest.Builder(context).data(src).allowHardware(false).size(4096).precision(coil.size.Precision.INEXACT).build())
                    check(result is SuccessResult) { "图片尚未加载成功，请稍后重试" }
                    withContext(Dispatchers.IO) { ImageSaver.save(context, result.drawable.toBitmap()) }
                    Toast.makeText(context, "已保存到相册 Pictures/WeKnora", Toast.LENGTH_LONG).show()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_LONG).show() }
                finally { saving = false }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else Toast.makeText(context, "未获得存储权限，无法保存图片", Toast.LENGTH_LONG).show()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onDismiss)
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(src)
                .crossfade(true)
                .build(),
            imageLoader = LocalAppImageLoader.current ?: LocalContext.current.imageLoader,
            contentDescription = alt.ifBlank { "知识库图片" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Color.White)
                }
            }
        )
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(36.dp)
                .clickable(onClick = onDismiss)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    Modifier.size(20.dp),
                    tint = Color.White
                )
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).clickable(enabled = !saving) {
                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else save()
            }
        ) {
            Text(if (saving) "正在保存…" else "保存到相册", color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}
