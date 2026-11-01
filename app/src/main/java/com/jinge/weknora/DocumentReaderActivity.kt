package com.jinge.weknora

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.jinge.weknora.data.PdfBookmark
import com.jinge.weknora.data.PdfOutline
import androidx.lifecycle.lifecycleScope
import com.jinge.weknora.ui.theme.WeKnoraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Private, authenticated document reader. Originals stay in application cache. */
class DocumentReaderActivity : ComponentActivity() {
    private var document: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var cached: File? = null
    private val lock = Any()
    @Volatile private var closed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeKnoraTheme {
                var ready by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var html by remember { mutableStateOf<String?>(null) }
                val pages = rememberLazyListState()
                val page by remember { derivedStateOf {
                    val layout = pages.layoutInfo
                    layout.visibleItemsInfo.maxByOrNull {
                        minOf(it.offset + it.size, layout.viewportEndOffset) - maxOf(it.offset, layout.viewportStartOffset)
                    }?.index ?: 0
                } }
                val navigationScope = rememberCoroutineScope()
                val goToPage: (Int) -> Unit = { target -> navigationScope.launch { pages.scrollToItem(target) } }
                var zoom by remember { mutableIntStateOf(1) }
                var retry by remember { mutableIntStateOf(0) }
                var bookmarks by remember { mutableStateOf<List<PdfBookmark>>(emptyList()) }
                var outlineError by remember { mutableStateOf(false) }
                var showOutline by remember { mutableStateOf(false) }
                var showJump by remember { mutableStateOf(false) }
                var pageInput by remember { mutableStateOf("") }
                LaunchedEffect(retry) {
                    error = null
                    try {
                        html = withContext(Dispatchers.IO) {
                            synchronized(lock) { document?.close(); document = null; descriptor?.close(); descriptor = null; cached?.delete() }
                            val file = File.createTempFile("reader-", ".bin", cacheDir)
                            cached = file
                            val request = Request.Builder().url(intent.getStringExtra("url") ?: error("缺少文档地址"))
                            intent.getBundleExtra("headers")?.let { headers ->
                                headers.keySet().forEach { key -> headers.getString(key)?.let { request.header(key, it) } }
                            }
                            val initialUrl = request.build().url
                            val authKeys = intent.getBundleExtra("headers")?.keySet().orEmpty()
                            val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS)
                                .followSslRedirects(false)
                                .addNetworkInterceptor { chain ->
                                    val redirected = chain.request()
                                    val sameOrigin = redirected.url.host == initialUrl.host && redirected.url.port == initialUrl.port && redirected.url.scheme == initialUrl.scheme
                                    val safe = redirected.newBuilder()
                                    if (!sameOrigin) authKeys.forEach { safe.removeHeader(it) }
                                    chain.proceed(safe.build())
                                }.build()
                            client.newCall(request.build()).execute().use { response ->
                                check(response.isSuccessful) { "文档读取失败（HTTP ${response.code}）" }
                                val body = response.body ?: error("文档为空")
                                check(body.contentLength() <= 100L * 1024 * 1024) { "文档超过 100 MB，请下载阅读" }
                                body.byteStream().use { input -> file.outputStream().use { output ->
                                    val buffer = ByteArray(32768)
                                    var total = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        total += n
                                        check(total <= 100L * 1024 * 1024) { "文档超过 100 MB，请下载阅读" }
                                        output.write(buffer, 0, n)
                                    }
                                } }
                            }
                            if (intent.getStringExtra("format") == "docx") {
                                com.jinge.weknora.data.WordPreview.toHtml(file)
                            } else {
                                synchronized(lock) {
                                    check(!closed) { "阅读器已关闭" }
                                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                                    document = PdfRenderer(descriptor!!)
                                }
                                bookmarks = try { PdfOutline.read(this@DocumentReaderActivity, file) }
                                catch (_: Exception) { outlineError = true; emptyList() }
                                null
                            }
                        }
                        ready = true
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) { error = e.message ?: "无法打开文档" }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { finish() }) { Text("返回") }
                            Text(intent.getStringExtra("title").orEmpty(), Modifier.weight(1f), maxLines = 1)
                        }
                        when {
                            error != null -> Column(Modifier.padding(24.dp)) {
                                Text(error!!)
                                TextButton(onClick = { ready = false; retry++ }) { Text("重试") }
                            }
                            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            html != null -> AndroidView(
                                factory = { context -> WebView(context).apply {
                                    settings.javaScriptEnabled = false
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.blockNetworkLoads = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    loadDataWithBaseURL(null, html!!, "text/html", "UTF-8", null)
                                } },
                                modifier = Modifier.fillMaxSize(),
                                onRelease = { it.destroy() }
                            )
                            else -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { goToPage(page - 1) }, enabled = page > 0) { Text("上一页") }
                                    Text("${page + 1} / ${document?.pageCount ?: 0}", Modifier.padding(top = 12.dp))
                                    TextButton(onClick = { goToPage(page + 1) }, enabled = page + 1 < (document?.pageCount ?: 0)) { Text("下一页") }
                                    TextButton(onClick = { zoom = if (zoom == 1) 2 else 1 }) { Text(if (zoom == 1) "放大" else "适宽") }
                                }
                                Row {
                                    TextButton(onClick = { showOutline = true }) { Text("目录") }
                                    TextButton(onClick = { pageInput = (page + 1).toString(); showJump = true }) { Text("跳转页码") }
                                }
                                LazyColumn(state = pages, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(document?.pageCount ?: 0, key = { it }) { number -> PageImage(number, zoom) }
                                }
                            }
                        }
                    }
                }
                if (showOutline) AlertDialog(
                    onDismissRequest = { showOutline = false },
                    title = { Text("PDF 目录") },
                    text = {
                        if (bookmarks.isEmpty()) Text(if (outlineError) "目录读取失败，仍可按页码跳转。" else "此 PDF 没有内嵌书签目录，可使用页码跳转。")
                        else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                            items(bookmarks) { bookmark ->
                                TextButton(onClick = { bookmark.page?.let(goToPage); showOutline = false }, enabled = bookmark.page != null,
                                    modifier = Modifier.fillMaxWidth().padding(start = (bookmark.depth * 12).dp)) {
                                    Text(bookmark.title, Modifier.weight(1f))
                                    bookmark.page?.let { Text("${it + 1}") }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showOutline = false }) { Text("关闭") } }
                )
                if (showJump) AlertDialog(
                    onDismissRequest = { showJump = false }, title = { Text("跳转页码") },
                    text = { OutlinedTextField(value = pageInput, onValueChange = { pageInput = it.filter(Char::isDigit).take(7) },
                        label = { Text("1 至 ${document?.pageCount ?: 0}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
                    confirmButton = { TextButton(enabled = pageInput.toIntOrNull()?.let { it in 1..(document?.pageCount ?: 0) } == true,
                        onClick = { goToPage(pageInput.toInt() - 1); showJump = false }) { Text("跳转") } },
                    dismissButton = { TextButton(onClick = { showJump = false }) { Text("取消") } }
                )
            }
        }
    }
    @Composable
    private fun PageImage(number: Int, zoom: Int) {
        var bitmap by remember(number, zoom) { mutableStateOf<Bitmap?>(null) }
        var failure by remember(number, zoom) { mutableStateOf(false) }
        LaunchedEffect(number, zoom) {
            try {
                bitmap = withContext(Dispatchers.IO) { synchronized(lock) {
                    document!!.openPage(number).use { p ->
                        val width = (resources.displayMetrics.widthPixels * zoom).coerceAtMost(2200)
                        val height = (width.toDouble() * p.height / p.width).toInt().coerceIn(1, 4400)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(AndroidColor.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failure = true }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), "第 ${number + 1} 页", Modifier.width((image.width / resources.displayMetrics.density).dp).height((image.height / resources.displayMetrics.density).dp))
            else Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
                if (failure) Text("第 ${number + 1} 页加载失败") else CircularProgressIndicator()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closed = true
        synchronized(lock) { document?.close(); document = null; descriptor?.close(); descriptor = null; cached?.delete() }
    }
}
