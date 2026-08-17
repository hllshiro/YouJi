package com.youji.app.util

import com.youji.app.data.VlmSettings
import com.youji.app.data.entity.PhotoEntity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * VLM大模型客户端
 * 支持用户自定义配置API接入
 */
class VlmClient {

    /**
     * VLM请求参数
     */
    data class VlmRequest(
        val prompt: String,
        val images: List<String> = emptyList(), // base64编码的图片
        val locationInfo: String = ""
    )

    /**
     * 调用VLM生成游记内容
     * 注意：实际部署时需要在后台线程调用
     */
    suspend fun generateTravelContent(
        settings: VlmSettings,
        photos: List<PhotoEntity>,
        customPrompt: String? = null
    ): Result<String> {
        if (!settings.enabled || settings.apiUrl.isBlank()) {
            return Result.failure(IllegalStateException("VLM未启用或API地址未配置"))
        }

        return try {
            // 构建提示词
            val prompt = buildPrompt(settings, photos, customPrompt)

            // 构建图片位置信息
            val locationInfo = buildLocationInfo(photos)

            // 发送请求（这里是标准实现，实际接入时根据API文档调整）
            val response = sendHttpRequest(settings, prompt, locationInfo)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 构建发送给VLM的提示词
     */
    private fun buildPrompt(
        settings: VlmSettings,
        photos: List<PhotoEntity>,
        customPrompt: String?
    ): String {
        val basePrompt = customPrompt ?: settings.customPrompt
        val photoDetails = buildPhotoDetails(photos)
        return """
$basePrompt

【旅行照片详情】
$photoDetails

请基于以上信息，生成游记正文内容：
        """.trimIndent()
    }

    /**
     * 构建照片详情文本
     */
    private fun buildPhotoDetails(photos: List<PhotoEntity>): String {
        val sb = StringBuilder()
        photos.forEachIndexed { index, photo ->
            sb.appendLine("照片${index + 1}:")
            sb.appendLine("  - 文件名: ${photo.fileName}")
            photo.takenAt?.let { sb.appendLine("  - 拍摄时间: ${DateFormatUtil.formatFull(it)}") }
            photo.locationName?.let { sb.appendLine("  - 位置: $it") }
            if (photo.latitude != null && photo.longitude != null) {
                sb.appendLine("  - 坐标: ${ExifUtil.formatLatLong(photo.latitude, photo.longitude)}")
            }
            photo.description?.let { sb.appendLine("  - 描述: $it") }
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 构建位置信息摘要
     */
    private fun buildLocationInfo(photos: List<PhotoEntity>): String {
        val locations = photos.mapNotNull { it.locationName }.distinct()
        val dateRange = buildDateRange(photos)
        return "途经地点: ${locations.joinToString("、")}\n时间: $dateRange"
    }

    /**
     * 构建日期范围
     */
    private fun buildDateRange(photos: List<PhotoEntity>): String {
        val times = photos.mapNotNull { it.takenAt }.sorted()
        if (times.isEmpty()) return "未知"
        return if (times.size == 1) {
            DateFormatUtil.formatFull(times.first())
        } else {
            "${DateFormatUtil.formatDate(times.first())} ~ ${DateFormatUtil.formatDate(times.last())}"
        }
    }

    /**
     * 发送HTTP请求到VLM API
     * 这是一个通用的OpenAI兼容格式实现，用户可以根据自己的API文档修改
     */
    private fun sendHttpRequest(
        settings: VlmSettings,
        prompt: String,
        locationInfo: String
    ): String {
        val url = URL(settings.apiUrl)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.setRequestProperty("Content-Type", "application/json")
            if (settings.apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            connection.doOutput = true

            // 构建请求体 (OpenAI兼容格式)
            val requestBody = JSONObject().apply {
                put("model", settings.modelName.ifBlank { "default" })
                put("messages", listOf(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", "$prompt\n\n附加信息:\n$locationInfo")
                    }
                ))
                put("temperature", 0.7)
                put("max_tokens", 2000)
            }

            // 写入请求体
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            // 读取响应
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                sb.toString()
            }

            if (responseCode !in 200..299) {
                throw RuntimeException("API请求失败 (HTTP $responseCode): $response")
            }

            // 解析响应 (OpenAI兼容格式)
            val jsonResponse = JSONObject(response)
            val choices = jsonResponse.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content")?.trim() ?: response
            } else {
                response
            }
        } finally {
            connection.disconnect()
        }
    }
}
