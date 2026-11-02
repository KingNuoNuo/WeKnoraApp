package com.jinge.weknora.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateDownloader {
    suspend fun download(context: Context, url: String, onProgress: suspend (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "下载地址无效" }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val target = File(context.cacheDir, "weknora-update.apk")
        val request = Request.Builder().url(url).header("Accept", "application/vnd.android.package-archive").build()
        val partial = File(context.cacheDir, "weknora-update.part")
        try { client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
            val body = response.body ?: error("下载内容为空")
            require(body.contentLength() <= 150L * 1024 * 1024) { "APK 文件过大" }
            val total = body.contentLength()
            onProgress(0, total)
            partial.outputStream().use { out -> body.byteStream().use { input ->
                val buffer = ByteArray(65536)
                var downloaded = 0L
                var lastReport = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    downloaded += n
                    require(downloaded <= 150L * 1024 * 1024) { "APK 文件过大" }
                    out.write(buffer, 0, n)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport >= 150) { onProgress(downloaded, total); lastReport = now }
                }
                require(total < 0 || downloaded == total) { "下载不完整，请重试" }
                require(downloaded > 0) { "下载内容为空" }
                onProgress(downloaded, total)
            } }
        }
        require(partial.renameTo(target)) { "无法保存安装包" }
        } finally { partial.delete() }
        target
    }
}
