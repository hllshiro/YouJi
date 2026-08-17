package com.youji.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 照片EXIF元信息读取工具
 */
object ExifUtil {

    data class PhotoMetadata(
        val takenAt: Long? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val make: String? = null,
        val model: String? = null,
        val width: Int = 0,
        val height: Int = 0
    )

    /**
     * 从文件路径读取EXIF信息
     */
    fun readMetadata(filePath: String): PhotoMetadata {
        return try {
            val exif = ExifInterface(filePath)
            val latLong = exif.latLong
            val takenAt = parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                ?: parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME))

            // 获取图片尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, options)

            PhotoMetadata(
                takenAt = takenAt,
                latitude = latLong?.get(0),
                longitude = latLong?.get(1),
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                width = options.outWidth,
                height = options.outHeight
            )
        } catch (e: Exception) {
            e.printStackTrace()
            PhotoMetadata()
        }
    }

    /**
     * 从Uri读取EXIF信息
     */
    fun readMetadata(context: Context, uri: Uri): PhotoMetadata {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val latLong = exif.latLong
                val takenAt = parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                    ?: parseDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME))

                // 获取图片尺寸
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                PhotoMetadata(
                    takenAt = takenAt,
                    latitude = latLong?.get(0),
                    longitude = latLong?.get(1),
                    make = exif.getAttribute(ExifInterface.TAG_MAKE),
                    model = exif.getAttribute(ExifInterface.TAG_MODEL),
                    width = options.outWidth ?: 0,
                    height = options.outHeight ?: 0
                )
            } ?: PhotoMetadata()
        } catch (e: Exception) {
            e.printStackTrace()
            PhotoMetadata()
        }
    }

    private fun parseDateTime(dateTimeStr: String?): Long? {
        if (dateTimeStr.isNullOrBlank()) return null
        return try {
            // EXIF格式: "yyyy:MM:dd HH:mm:ss"
            val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
            format.parse(dateTimeStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将经纬度转换为度分秒格式字符串
     */
    fun formatLatLong(latitude: Double, longitude: Double): String {
        val latHem = if (latitude >= 0) "N" else "S"
        val lonHem = if (longitude >= 0) "E" else "W"
        val absLat = Math.abs(latitude)
        val absLon = Math.abs(longitude)
        return String.format(
            Locale.getDefault(),
            "%.4f°%s, %.4f°%s",
            absLat, latHem, absLon, lonHem
        )
    }
}

/**
 * 文件工具类
 */
object FileUtil {

    /**
     * 创建拍照临时文件
     */
    fun createImageFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = File(context.filesDir, "photos").apply { mkdirs() }
        return File(storageDir, "JPEG_${timeStamp}_.jpg")
    }

    /**
     * 将Uri的内容复制到应用私有目录
     */
    fun copyUriToInternal(context: Context, uri: Uri, fileName: String? = null): File? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                null
            } else {
                val storageDir = File(context.filesDir, "photos").apply { mkdirs() }
                val name = fileName ?: "IMG_${System.currentTimeMillis()}.jpg"
                val destFile = File(storageDir, name)
                FileOutputStream(destFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()
                destFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 删除文件
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 删除文件列表
     */
    fun deleteFiles(filePaths: List<String>) {
        filePaths.forEach { deleteFile(it) }
    }
}
