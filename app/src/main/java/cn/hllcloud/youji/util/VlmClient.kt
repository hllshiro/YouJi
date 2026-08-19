package cn.hllcloud.youji.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import cn.hllcloud.youji.data.VlmSettings
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.WritingStyleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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
     *   通过 resolveUrls 推断 baseUrl，向 GET {base}/models 发请求，
     *   验证 API 地址可达且鉴权通过。
     *   - 404 表示 baseUrl 不对（用户填错路径或缺少/多了 v1）
     *   - 401/403 表示 apiKey 无效
     *   - 200 表示连通正常
     *
     * 阶段2 - Vision 探测：
     *   通过 resolveUrls 推断 chat 端点（自动补全 /chat/completions），
     *   发送一个最小 Vision 请求（32x32 JPEG + detail:"low" + 文字提示）。
     *   - 200 且响应包含 choices[].message.content 视为支持 Vision
     *   - 4xx 且错误信息含 image/vision/unsupported 等关键词视为不支持 Vision
     *   - 404 视为 chat 端点路径错误（success=false）
     *   - 其他 4xx 视为请求格式或参数错误（success=false，不误判为"通过"）
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
            // 生成 32x32 纯色 JPEG 作为测试图片（比 1x1 PNG 更符合规范）
            val testBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            testBitmap.eraseColor(Color.rgb(100, 150, 200))
            val testBaos = ByteArrayOutputStream()
            testBitmap.compress(Bitmap.CompressFormat.JPEG, 80, testBaos)
            testBitmap.recycle()
            val testImageBase64 = Base64.encodeToString(testBaos.toByteArray(), Base64.NO_WRAP)

            // 使用解析后的 chat 端点（自动补全 /chat/completions）
            val (_, chatUrl) = resolveUrls(settings.apiUrl)
            val url = URL(chatUrl)
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
                            put("url", "data:image/jpeg;base64,$testImageBase64")
                            put("detail", "low")
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

                    return when {
                        // 404: chat 端点不存在，说明 apiUrl 路径不对
                        responseCode == 404 -> VisionTestResult(
                            success = false,
                            supportsVision = false,
                            message = "Chat端点不存在 (HTTP 404)：$chatUrl。请检查 apiUrl 路径是否正确（通常填 baseUrl 即可，如 https://api.openai.com/v1，系统会自动补全 /chat/completions）",
                            latencyMs = latency
                        )
                        // 明确不支持 Vision：API 可达，仅模型不支持图像
                        notSupportVision -> VisionTestResult(
                            success = true,
                            supportsVision = false,
                            message = "API连通正常，但该模型不支持图像理解 (HTTP $responseCode)",
                            latencyMs = latency
                        )
                        // 其他错误：视为测试失败，不猜测为"通过"
                        else -> VisionTestResult(
                            success = false,
                            supportsVision = false,
                            message = "Vision请求失败 (HTTP $responseCode): ${response.take(200)}",
                            latencyMs = latency
                        )
                    }
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
     * 从用户输入的 apiUrl 推断 baseUrl 和 chat/completions 端点。
     *
     * v1 路径段由用户自行决定，本方法不猜测。
     * - https://api.openai.com/v1/chat/completions → baseUrl=https://api.openai.com/v1, chatUrl=原样
     * - https://api.openai.com/v1 → baseUrl=原样, chatUrl=原样/chat/completions
     * - https://api.deepseek.com → baseUrl=原样, chatUrl=原样/chat/completions
     *
     * @return Pair(baseUrl, chatUrl)
     */
    private fun resolveUrls(apiUrl: String): Pair<String, String> {
        val url = apiUrl.trim().trimEnd('/')
        val baseUrl = url.removeSuffix("/chat/completions")
        val chatUrl = if (url.endsWith("/chat/completions")) url else "$baseUrl/chat/completions"
        return Pair(baseUrl, chatUrl)
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
        val (baseUrl, _) = resolveUrls(settings.apiUrl)
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

        val (baseUrl, _) = resolveUrls(settings.apiUrl)
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
     * 调用VLM生成游记内容（支持Vision图像理解）
     *
     * 处理流程：
     * 1. 构建文本提示词和位置信息（结构化"照片 N：时间 X、地点 Y"格式 + 风格指导）
     * 2. 读取照片文件，压缩为 JPEG 并 base64 编码
     * 3. 构建 OpenAI 兼容的 Vision 请求（content 为数组，包含 text 和 image_url）
     * 4. 如果没有图片，退化为纯文本请求
     *
     * 注意：应在 IO 线程调用（涉及文件读取和网络请求）
     *
     * @param style 选中的写作风格，非空时使用其 promptGuideline / openingTone /
     *              closingTone 注入 prompt；为空时回退到 settings.customPrompt
     */
    suspend fun generateTravelContent(
        settings: VlmSettings,
        photos: List<PhotoEntity>,
        style: WritingStyleEntity? = null,
        customPrompt: String? = null
    ): Result<String> {
        if (!settings.enabled || settings.apiUrl.isBlank()) {
            return Result.failure(IllegalStateException("VLM未启用或API地址未配置"))
        }

        return try {
            // 构建提示词
            val prompt = buildPrompt(settings, photos, style, customPrompt)

            // 构建图片位置信息
            val locationInfo = buildLocationInfo(photos)

            // 读取并压缩图片为 base64 data URL（长边 1024px，JPEG 80%）
            val imageDataUrls = photos.mapNotNull { photo ->
                ImageUtil.compressAndBase64Encode(photo.filePath)?.let { base64 ->
                    ImageUtil.buildDataUrl(base64)
                }
            }

            // 发送请求
            val response = sendHttpRequest(settings, prompt, locationInfo, imageDataUrls)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 增量生成游记正文。对应设计 V3 第 5.3 节场景三策略 A/C：
     * 仅把新增照片的图像 token 发给 VLM，搭配已有正文做上下文，
     * 让 VLM 在保持原文结构与 [PHOTO:id] 标记的基础上插入新增段落。
     *
     * - 仅 added：直接调用本方法
     * - 混合（added + removed）：调用方先用 [removePhotoFromContent] 本地删除 removed 段落，
     *   再把删除后的正文作为 [originalContent] 传入本方法
     *
     * @param addedPhotos 仅包含新增照片（[PhotoEntity.id] 已入库），不要传 unchanged 照片
     * @param originalContent 已有游记正文（mixed 场景下是删除 removed 后的中间结果）
     */
    suspend fun generateIncrementalContent(
        settings: VlmSettings,
        addedPhotos: List<PhotoEntity>,
        style: WritingStyleEntity? = null,
        originalContent: String
    ): Result<String> {
        if (!settings.enabled || settings.apiUrl.isBlank()) {
            return Result.failure(IllegalStateException("VLM未启用或API地址未配置"))
        }
        if (addedPhotos.isEmpty()) {
            return Result.failure(IllegalArgumentException("新增照片列表为空"))
        }

        return try {
            val prompt = buildIncrementalPrompt(settings, addedPhotos, style, originalContent)
            val locationInfo = buildLocationInfo(addedPhotos)
            val imageDataUrls = addedPhotos.mapNotNull { photo ->
                ImageUtil.compressAndBase64Encode(photo.filePath)?.let { base64 ->
                    ImageUtil.buildDataUrl(base64)
                }
            }
            val response = sendHttpRequest(settings, prompt, locationInfo, imageDataUrls)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 本地删除游记正文中与指定照片 id 关联的段落。对应设计 V3 第 5.4 节策略 B（无 VLM 调用）。
     *
     * 段落识别规则：匹配 `[PHOTO:id]` 标记及其后到下一个 `[PHOTO:` 或文本末尾之间的内容。
     * 即每个 [PHOTO:id] 标记独占一行后接所属段落，删除时连同标记和段落一并移除。
     *
     * 兼容 VLM 可能未严格按规则输出标记的情况：若正则未匹配到任何段，直接原样返回。
     */
    fun removePhotoFromContent(content: String, photoId: Long): String {
        val pattern = Regex("\\[PHOTO:$photoId\\][\\s\\S]*?(?=\\[PHOTO:|$)", RegexOption.MULTILINE)
        return pattern.replace(content, "").trim()
    }

    /**
     * 构建增量 prompt。对应设计 V3 第 5.4 节策略 A 模板。
     *
     * 与全量 [buildPrompt] 的差异：
     * - 不要求重写已有内容，仅要求把新增照片自然融入；
     * - 输出必须是「完整新正文」（原文 + 新增段落），而非仅新增段落；
     * - 保留原文 [PHOTO:id] 标记，新增照片用 [PHOTO:new_id] 标记。
     */
    private fun buildIncrementalPrompt(
        settings: VlmSettings,
        addedPhotos: List<PhotoEntity>,
        style: WritingStyleEntity?,
        originalContent: String
    ): String {
        val guideline = style?.promptGuideline?.takeIf { it.isNotBlank() }
            ?: settings.customPrompt
            ?: DEFAULT_GUIDELINE

        val photoDetails = buildPhotoDetails(addedPhotos)

        return buildString {
            appendLine("你是游记写作助手。以下是已有游记：")
            appendLine()
            appendLine("【已有游记】")
            appendLine(originalContent)
            appendLine()
            appendLine("【风格】")
            if (style != null) {
                appendLine("${style.name}：$guideline")
                style.openingTone?.takeIf { it.isNotBlank() }?.let { appendLine("开篇语气：$it") }
                style.closingTone?.takeIf { it.isNotBlank() }?.let { appendLine("结尾语气：$it") }
            } else {
                appendLine(guideline)
            }
            appendLine()
            appendLine("【新增照片】")
            appendLine(photoDetails)
            appendLine()
            appendLine("【任务】")
            appendLine("请在保持原文风格和结构的基础上，将新增照片自然融入游记。")
            appendLine("可在合适位置插入新段落，不要重写已有内容。")
            appendLine("保留原文中的 [PHOTO:id] 标记，新增照片用 [PHOTO:new_id] 标记（new_id 见上方照片详情中的 id）。")
            appendLine("输出完整的游记正文（原文 + 新增段落），不要输出标题，不要输出说明性文字。")
        }.trim()
    }

    /**
     * 构建发送给VLM的提示词。
     *
     * 风格注入逻辑：
     * - 优先使用 [WritingStyleEntity.promptGuideline] 作为正文指导；
     * - [WritingStyleEntity.openingTone] / [closingTone] 非空时作为开篇/结尾的额外约束；
     * - [style] 为空时回退到调用方传入的 [customPrompt] 或 [VlmSettings.customPrompt]。
     *
     * 上下文结构化：按"照片 N：时间 X、地点 Y"格式列出每张照片，
     * 配合位置坐标与文件名，让模型可结合图像内容与实际位置编写游记。
     */
    private fun buildPrompt(
        settings: VlmSettings,
        photos: List<PhotoEntity>,
        style: WritingStyleEntity?,
        customPrompt: String?
    ): String {
        val guideline = style?.promptGuideline?.takeIf { it.isNotBlank() }
            ?: customPrompt
            ?: settings.customPrompt
            ?: DEFAULT_GUIDELINE

        val openingTone = style?.openingTone?.takeIf { it.isNotBlank() }
        val closingTone = style?.closingTone?.takeIf { it.isNotBlank() }

        val photoDetails = buildPhotoDetails(photos)

        return buildString {
            appendLine("【写作风格指导】")
            appendLine(guideline)
            openingTone?.let { appendLine("开篇语气：$it") }
            closingTone?.let { appendLine("结尾语气：$it") }
            appendLine()
            appendLine("【旅行照片详情】")
            appendLine(photoDetails)
            appendLine()
            appendLine("【输出要求】")
            appendLine("请基于以上照片信息和图片内容，编写一篇结构清晰、文风符合上述指导的游记正文。")
            appendLine("每张照片在正文中应自然对应一段，可在所属段末以独立一行 `[PHOTO:照片id]` 标记该照片归属，便于后续增量更新。")
            appendLine("不要输出标题，标题会另行生成。")
        }.trim()
    }

    /**
     * 结构化照片详情：按"照片 N：时间 X、地点 Y"格式输出。
     * 时间缺失时省略时间，地点缺失时省略地点；两者都缺失时仅列出文件名和坐标。
     */
    private fun buildPhotoDetails(photos: List<PhotoEntity>): String {
        val sb = StringBuilder()
        photos.forEachIndexed { index, photo ->
            val seq = index + 1
            sb.appendLine("照片 $seq（id=${photo.id}）：")
            sb.appendLine("  - 文件名: ${photo.fileName}")
            photo.takenAt?.let {
                sb.appendLine("  - 拍摄时间: ${DateFormatUtil.formatFull(it)}")
            }
            photo.locationName?.let {
                sb.appendLine("  - 地点: $it")
            }
            if (photo.latitude != null && photo.longitude != null) {
                sb.appendLine("  - 坐标: ${ExifUtil.formatLatLong(photo.latitude, photo.longitude)}")
            }
            photo.description?.let {
                sb.appendLine("  - 描述: $it")
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private companion object {
        /** 兜底写作指导，未配置风格且未传入 customPrompt 时使用。 */
        const val DEFAULT_GUIDELINE = "如实记录旅行经历，按时间地点顺序组织，结合照片内容自然过渡，文风平实。"
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
     *
     * OpenAI 兼容格式实现：
     * - 有图片时：content 为数组，包含 text + image_url（Vision 模式）
     * - 无图片时：content 为字符串（纯文本模式）
     *
     * 图片使用 detail:"low" 以降低 token 消耗（固定 85 tokens/图）。
     */
    private fun sendHttpRequest(
        settings: VlmSettings,
        prompt: String,
        locationInfo: String,
        imageDataUrls: List<String> = emptyList()
    ): String {
        val (_, chatUrl) = resolveUrls(settings.apiUrl)
        val url = URL(chatUrl)
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

            val fullPrompt = "$prompt\n\n附加信息:\n$locationInfo"

            val requestBody = JSONObject().apply {
                put("model", settings.modelName.ifBlank { "default" })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        if (imageDataUrls.isEmpty()) {
                            // 无图片：纯文本格式
                            put("content", fullPrompt)
                        } else {
                            // 有图片：OpenAI Vision 格式（content 为数组）
                            val contentArray = JSONArray()
                            contentArray.put(JSONObject().apply {
                                put("type", "text")
                                put("text", fullPrompt)
                            })
                            imageDataUrls.forEach { dataUrl ->
                                contentArray.put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", dataUrl)
                                        put("detail", "low")
                                    })
                                })
                            }
                            put("content", contentArray)
                        }
                    })
                })
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
