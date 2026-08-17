package com.youji.app.util

import com.youji.app.data.VlmSettings
import com.youji.app.data.entity.PhotoEntity
import org.json.JSONArray
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
     * Vision能力测试结果
     */
    data class VisionTestResult(
        val success: Boolean,
        val supportsVision: Boolean,
        val message: String,
        val latencyMs: Long = 0L
    )

    /**
     * 测试API是否支持Vision（图像理解）能力。
     *
     * 实现策略：发送一个最小化的图像理解请求（一张1x1像素的占位PNG，Base64编码），
     * 让模型描述图像。如果模型返回正常文本响应，则认为支持Vision；如果API返回
     * 明确的"不支持图像/模型不支持vision"类错误，则认为不支持。
     *
     * 同时该方法本身也作为API连通性测试：如果连接失败或鉴权失败，会返回失败结果。
     */
    suspend fun testVisionCapability(settings: VlmSettings): VisionTestResult {
        if (settings.apiUrl.isBlank()) {
            return VisionTestResult(
                success = false,
                supportsVision = false,
                message = "API地址未配置"
            )
        }

        val startMs = System.currentTimeMillis()
        return try {
            // 1x1 透明PNG，Base64编码（去掉data:image/png;base64,前缀）
            val tinyPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="

            val url = URL(settings.apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("Content-Type", "application/json")
                if (settings.apiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }
                connection.doOutput = true

                // OpenAI兼容Vision请求格式：content为数组，包含text和image_url
                val contentArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "请用一句话描述这张图片。")
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/png;base64,$tinyPngBase64")
                        })
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", settings.modelName.ifBlank { "gpt-4o-mini" })
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        })
                    })
                    put("max_tokens", 100)
                    put("temperature", 0.1)
                }

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val latency = System.currentTimeMillis() - startMs

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
                    // 检查错误信息是否暗示不支持Vision
                    val lowerResp = response.lowercase()
                    val notSupportVision = lowerResp.contains("vision") &&
                        (lowerResp.contains("not support") || lowerResp.contains("unsupported") ||
                            lowerResp.contains("does not support") || lowerResp.contains("image"))
                    return VisionTestResult(
                        success = !notSupportVision,
                        supportsVision = false,
                        message = if (notSupportVision) {
                            "API返回：该模型不支持图像理解 (HTTP $responseCode)"
                        } else {
                            "API请求失败 (HTTP $responseCode): ${response.take(300)}"
                        },
                        latencyMs = latency
                    )
                }

                // 解析OpenAI兼容响应
                val jsonResponse = JSONObject(response)
                val choices = jsonResponse.optJSONArray("choices")
                val content = if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    message?.optString("content")?.trim()
                } else null

                VisionTestResult(
                    success = true,
                    supportsVision = true,
                    message = if (content.isNullOrBlank()) {
                        "API连通正常，且支持Vision（模型返回为空）"
                    } else {
                        "API连通正常，且支持Vision。模型回复：${content.take(100)}"
                    },
                    latencyMs = latency
                )
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            VisionTestResult(
                success = false,
                supportsVision = false,
                message = "连接失败: ${e.message ?: e.javaClass.simpleName}",
                latencyMs = System.currentTimeMillis() - startMs
            )
        }
    }

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
