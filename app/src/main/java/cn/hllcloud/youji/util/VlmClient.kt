package cn.hllcloud.youji.util

import cn.hllcloud.youji.data.VlmSettings
import cn.hllcloud.youji.data.entity.PhotoEntity
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
     * 实现策略（两阶段，确保结果可靠）：
     *
     * 阶段1 - 连通性测试：
     *   根据用户配置的 apiUrl 自动推断 base URL（去掉 /v1/chat/completions、
     *   /chat/completions 等路径后缀），向 GET {base}/v1/models 或 {base}/models
     *   发请求，验证 API 地址可达且鉴权通过。
     *   - 404 表示 baseUrl 不对（用户填错路径或缺少/多了 v1）
     *   - 401/403 表示 apiKey 无效
     *   - 200 表示连通正常
     *
     * 阶段2 - Vision 探测：
     *   向用户配置的 apiUrl 发送一个最小 Vision 请求（1x1 PNG + 文字提示）。
     *   - 200 且响应包含 choices[].message.content 视为支持 Vision
     *   - 4xx 且错误信息含 image/vision/unsupported 等关键词视为不支持 Vision
     *   - 其他 4xx 视为请求格式或参数错误（不一定是 Vision 问题）
     *
     * 这样可以避免 404 被误判为"测试通过"。
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

        // ===== 阶段1：连通性测试 =====
        val connectivityResult = testConnectivity(settings)
        if (!connectivityResult.first) {
            return VisionTestResult(
                success = false,
                supportsVision = false,
                message = connectivityResult.second,
                latencyMs = System.currentTimeMillis() - startMs
            )
        }

        // ===== 阶段2：Vision 探测 =====
        return try {
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
                            lowerResp.contains("does not support")) ||
                        (lowerResp.contains("image") && lowerResp.contains("not support"))
                    return VisionTestResult(
                        success = true,  // API本身可达且鉴权通过（连通性已验证）
                        supportsVision = false,
                        message = if (notSupportVision) {
                            "API连通正常，但该模型不支持图像理解 (HTTP $responseCode)"
                        } else {
                            "API连通正常，但Vision请求失败 (HTTP $responseCode): ${response.take(200)}"
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
                message = "Vision探测请求异常: ${e.message ?: e.javaClass.simpleName}",
                latencyMs = System.currentTimeMillis() - startMs
            )
        }
    }

    /**
     * 连通性测试：根据 apiUrl 推断 base URL，调用 GET /models
     *
     * v1 路径段由用户在 apiUrl 中自行包含或省略，本方法不做猜测。
     * - 用户填 https://api.openai.com/v1 → 探测 https://api.openai.com/v1/models
     * - 用户填 https://api.deepseek.com → 探测 https://api.deepseek.com/models
     * - 用户填 https://api.openai.com/v1/chat/completions → 去掉后缀 → 探测 .../v1/models
     *
     * @return Pair<Boolean, String> first=true 表示连通正常
     */
    private fun testConnectivity(settings: VlmSettings): Pair<Boolean, String> {
        val apiUrl = settings.apiUrl.trim().trimEnd('/')
        // 仅去掉 /chat/completions 后缀，保留用户原本的 v1 设置
        val baseUrl = apiUrl.removeSuffix("/chat/completions")
        val modelsUrl = "$baseUrl/models"

        return try {
            val url = URL(modelsUrl)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                if (settings.apiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }

                val code = connection.responseCode
                if (code in 200..299) {
                    return Pair(true, "API连通正常")
                }

                when (code) {
                    401, 403 -> Pair(false, "鉴权失败 (HTTP $code)：apiKey 无效或过期")
                    404 -> Pair(false, "路径不存在 (HTTP 404)：$modelsUrl。请检查 baseUrl 是否正确（含/不含 /v1 由服务商决定）")
                    else -> Pair(false, "请求失败 (HTTP $code)")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Pair(false, "连接失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 拉取可用模型列表（OpenAI 兼容 GET /models）。
     *
     * 与 testConnectivity 一致：v1 路径段由用户在 apiUrl 中自行决定，本方法不猜测。
     * 返回模型 id 列表（按字母序升序）。
     */
    suspend fun fetchModels(settings: VlmSettings): Result<List<String>> {
        if (settings.apiUrl.isBlank()) {
            return Result.failure(IllegalStateException("API地址未配置"))
        }

        val apiUrl = settings.apiUrl.trim().trimEnd('/')
        val baseUrl = apiUrl.removeSuffix("/chat/completions")
        val modelsUrl = "$baseUrl/models"

        return try {
            val url = URL(modelsUrl)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                if (settings.apiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    sb.toString()
                }

                if (code !in 200..299) {
                    return Result.failure(RuntimeException("获取模型列表失败 (HTTP $code): ${response.take(200)}"))
                }

                // OpenAI 兼容格式: { "data": [ { "id": "gpt-4o", ... }, ... ] }
                val jsonResponse = JSONObject(response)
                val dataArray = jsonResponse.optJSONArray("data")
                val models = mutableListOf<String>()
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val id = dataArray.getJSONObject(i).optString("id")
                        if (id.isNotBlank()) models.add(id)
                    }
                }
                // 部分服务商返回 { "models": [ { "id": "..." } ] } 或直接数组
                if (models.isEmpty()) {
                    val modelsArray = jsonResponse.optJSONArray("models")
                    if (modelsArray != null) {
                        for (i in 0 until modelsArray.length()) {
                            val item = modelsArray.optJSONObject(i)
                            val id = item?.optString("id") ?: modelsArray.optString(i)
                            if (id.isNotBlank()) models.add(id)
                        }
                    }
                }

                models.sort()
                if (models.isEmpty()) {
                    Result.failure(IllegalStateException("响应中未找到模型列表，请检查 API 格式"))
                } else {
                    Result.success(models)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
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
