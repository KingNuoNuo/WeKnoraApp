package com.jinge.weknora.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID

object ImageSaver {
    fun save(context: Context, bitmap: Bitmap) {
        val name = "WeKnora_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
        var legacyFile: File? = null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WeKnora")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WeKnora")
                check(directory.isDirectory || directory.mkdirs()) { "无法创建图片目录" }
                legacyFile = File(directory, name)
                put(MediaStore.Images.Media.DATA, legacyFile!!.absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法保存图片")
        try {
            val output = resolver.openOutputStream(uri) ?: error("无法写入图片")
            output.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片编码失败" } }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            legacyFile?.delete()
            throw e
        }
    }
}
