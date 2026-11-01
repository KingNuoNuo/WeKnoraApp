package com.jinge.weknora.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateDownloader {
    suspend fun download(context: Context, url: String): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "下载地址无效" }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val target = File(context.cacheDir, "weknora-update.apk")
        val request = Request.Builder().url(url).header("Accept", "application/vnd.android.package-archive").build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
            val body = response.body ?: error("下载内容为空")
            require(body.contentLength() <= 150L * 1024 * 1024) { "APK 文件过大" }
            target.outputStream().use { out -> body.byteStream().use { input -> input.copyTo(out) } }
        }
        target
    }
}
