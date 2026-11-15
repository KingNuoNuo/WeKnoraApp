package com.jinge.weknora

import android.os.Bundle
import android.content.Intent
import android.webkit.WebView
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.jinge.weknora.ui.theme.WeKnoraTheme
import com.jinge.weknora.data.WeKnoraApi
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

class DocumentReaderActivity : ComponentActivity() {
    private var cached: File? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WeKnoraTheme {
            var html by remember { mutableStateOf<String?>(null) }
            var imageFile by remember { mutableStateOf<File?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    val file = withContext(Dispatchers.IO) {
                            val sourceUrl = intent.getStringExtra("url") ?: error("缺少文档地址")
                            val digest = MessageDigest.getInstance("SHA-256").digest(sourceUrl.toByteArray())
                                .joinToString("") { "%02x".format(it) }.take(24)
                            val file = File(cacheDir, "weknora-doc-$digest.bin")
                            cached = file
                            val request = Request.Builder().url(sourceUrl)
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
                                }.build().let(WeKnoraApi::networkAware)
                            if (!file.exists() || file.length() == 0L) client.newCall(request.build()).execute().use { response ->
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

                        file
                    }
                    if (intent.getStringExtra("format") == "pdf") {
                        startActivity(Intent(this@DocumentReaderActivity, NativePdfActivity::class.java)
                            .setData(FileProvider.getUriForFile(this@DocumentReaderActivity, "$packageName.fileprovider", file))
                            .putExtra("title", intent.getStringExtra("title"))
                            .putExtra("cached_path", file.absolutePath)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        finish()
                    } else if (intent.getStringExtra("format") == "image") {
                        imageFile = file
                    } else html = withContext(Dispatchers.IO) { when(intent.getStringExtra("format")) {
                        "docx" -> com.jinge.weknora.data.WordPreview.toHtml(file)
                        "pptx" -> com.jinge.weknora.data.PowerPointPreview.toHtml(file)
                        "text" -> """<!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{font:16px/1.7 sans-serif;margin:18px;color:#202522}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><body><pre>${TextUtils.htmlEncode(file.readText())}</pre></body></html>"""
                        else -> file.readText()
                    } }
                } catch (e: CancellationException) { throw e }
                catch(e: Exception) { error = e.message ?: "文档打开失败" }
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    // 顶栏与系统状态栏同色，白字；不再出现「深墨状态栏 + 浅色顶栏」的断层
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(colorResource(R.color.sys_bar_top)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { finish() },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) { Text("返回") }
                        Text(
                            intent.getStringExtra("title").orEmpty(),
                            Modifier.weight(1f),
                            maxLines = 1,
                            color = Color.White
                        )
                    }
                    when {
                        error != null -> Text(error!!, Modifier.padding(16.dp))
                        imageFile != null -> AsyncImage(
                            model = imageFile,
                            contentDescription = intent.getStringExtra("title"),
                            modifier = Modifier.fillMaxSize()
                        )
                        html == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> AndroidView(factory = { context -> WebView(context).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            loadDataWithBaseURL(null, html!!, "text/html", "UTF-8", null)
                        } }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
                    }
                }
            }
        } }
    }
}
