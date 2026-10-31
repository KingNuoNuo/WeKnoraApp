package com.jinge.weknora.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 一次更新检查的结果。
 *
 * 「检查失败」和「已是最新」是两件事，必须能分开告诉用户：
 * 把网络错误显示成「已是最新」，等于告诉用户「你没落后」—— 那是编的。
 */
data class UpdateResult(
    val currentVersion: String,
    val currentCode: Long,
    val latest: UpdateInfo? = null,
    val hasUpdate: Boolean = false,
    val error: String? = null
)

data class UpdateInfo(
    val version: String = "",
    val versionCode: Int = 0,
    val notes: String = "",
    /** APK 或下载页地址；点「去下载」用系统浏览器打开 */
    val url: String = ""
)

/**
 * 更新检测。
 *
 * 读的是仓库根目录的 `update.json`（默认 raw.githubusercontent.com），
 * 格式：
 * ```json
 * { "version": "1.1.5", "version_code": 7,
 *   "notes": "本次更新…", "url": "https://…/WeKnora-1.1.5.apk" }
 * ```
 *
 * 为什么不用 GitHub Releases API：这个仓库目前一个 release 都没发，
 * `releases/latest` 直接 404；而且国内访问 api.github.com 经常超时。
 * 一个静态 json 既能放 GitHub、也能放你自己的 NAS，
 * 地址在设置里可改 —— 内网部署的用户不该被墙卡住更新。
 */
class UpdateChecker(context: Context) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 当前安装包的版本名 / versionCode */
    fun current(): Pair<String, Long> = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        (info.versionName ?: "?").removeSuffix("-debug") to code
    } catch (_: PackageManager.NameNotFoundException) {
        "?" to 0L
    }

    suspend fun check(url: String): UpdateResult = withContext(Dispatchers.IO) {
        val (currentName, currentCode) = current()
        val target = url.trim().ifBlank { DEFAULT_URL }
        try {
            val req = Request.Builder()
                .url(target)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateResult(
                        currentName, currentCode,
                        error = "更新源返回 HTTP ${resp.code}"
                    )
                }
                val raw = resp.body?.string().orEmpty()
                val info = parseManifest(raw)
                    ?: return@withContext UpdateResult(
                        currentName, currentCode,
                        error = "更新源内容不是有效的 update.json"
                    )
                val newer = when {
                    info.versionCode > 0 -> info.versionCode.toLong() > currentCode
                    else -> versionGreater(info.version, currentName)
                }
                UpdateResult(
                    currentVersion = currentName,
                    currentCode = currentCode,
                    latest = info,
                    hasUpdate = newer
                )
            }
        } catch (e: Exception) {
            UpdateResult(
                currentName, currentCode,
                error = e.message?.take(120) ?: "网络不可达"
            )
        }
    }

    /**
     * 宽松解析：既接受裸对象，也接受 `{"data":{…}}` 包装；
     * 字段名兼容 versionCode / version_code。
     */
    private fun parseManifest(raw: String): UpdateInfo? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val obj = (root["data"] as? JsonObject) ?: root
        fun str(vararg keys: String): String {
            keys.forEach { k -> (obj[k] as? JsonPrimitive)?.content?.let { if (it.isNotBlank()) return it.trim() } }
            return ""
        }
        val version = str("version", "version_name", "tag_name")
        val code = str("version_code", "versionCode").toIntOrNull() ?: 0
        if (version.isBlank() && code == 0) return null
        return UpdateInfo(
            version = version.ifBlank { "新版本" },
            versionCode = code,
            notes = str("notes", "changelog", "body"),
            url = str("url", "download_url", "apk")
        )
    }

    /** 语义化版本比较：1.1.10 > 1.1.9（字符串比较会得到相反结果） */
    private fun versionGreater(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v")
            .split('.', '-', '+')
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    companion object {
        /**
         * 默认更新源。换仓库 / 换 NAS 在设置页改，不用重新发版。
         */
        const val DEFAULT_URL =
            "https://raw.githubusercontent.com/KingNuoNuo/WeKnoraApp/main/update.json"
    }
}
