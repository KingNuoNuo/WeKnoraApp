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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(src)
            .crossfade(true)
            .build(),
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
        error = { BrokenImage(src) }
    )
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
