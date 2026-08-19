package cn.hllcloud.youji.util

import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.WritingStyleEntity

/**
 * 本地游记内容生成器（不依赖 VLM）。
 *
 * 基于 EXIF 元信息 + 地理编码结果，按 [WritingStyleEntity.promptGuideline]
 * 调整文案，生成带 `[PHOTO:id]` 标记的正文：
 * - 标记位置对应每张照片在正文中所属段落，便于策略 B（仅删除）通过本地正则
 *   直接删除对应段落而不调用 VLM，最大化省钱。
 * - 标记格式固定为 `[PHOTO:${photo.id}]`，位于所属段落末尾独立成行。
 *
 * 对应设计文档 V3 第 4.3 节 runLocalGen + 第 5.3 节策略 B。
 */
object LocalContentGenerator {

    /**
     * 基于照片列表 + 风格生成游记内容。
     *
     * @param photos 已包含 EXIF 与 geocode 结果的照片列表
     * @param style 选中的写作风格；null 时按"纪实"内置风格兜底
     * @param title 可选标题，留空时自动生成
     */
    fun generateContent(
        photos: List<PhotoEntity>,
        style: WritingStyleEntity? = null,
        title: String = ""
    ): GeneratedContent {
        val sortedPhotos = photos.sortedBy { it.takenAt ?: it.createdAt }
        val locationSummary = extractLocationSummary(sortedPhotos)
        val dateRange = extractDateRange(sortedPhotos)
        val coverPhoto = sortedPhotos.firstOrNull()

        val finalTitle = title.ifBlank { generateTitle(sortedPhotos, locationSummary, dateRange) }
        val content = buildContent(sortedPhotos, locationSummary, dateRange, style)

        return GeneratedContent(
            title = finalTitle,
            content = content,
            locationSummary = locationSummary,
            startDate = dateRange?.first,
            endDate = dateRange?.second,
            coverPhotoPath = coverPhoto?.filePath
        )
    }

    private fun generateTitle(
        photos: List<PhotoEntity>,
        locationSummary: String?,
        dateRange: Pair<Long, Long>?
    ): String {
        if (!locationSummary.isNullOrBlank()) {
            val days = dateRange?.let { DateFormatUtil.daysBetween(it.first, it.second) } ?: 1
            return if (days > 1) {
                "${locationSummary}${days}日游"
            } else {
                "$locationSummary · 游记"
            }
        }
        dateRange?.let {
            return "${DateFormatUtil.formatShort(it.first)}的旅行记忆"
        }
        return "游记 · ${DateFormatUtil.formatDate(photos.firstOrNull()?.createdAt)}"
    }

    /**
     * 按风格构建正文。
     *
     * 风格的 promptGuideline 主要影响开篇/结尾语气 + 段落措辞倾向：
     * - 纪实：客观平实，强调时间地点事件
     * - 美化：在纪实基础上加情感和意境
     * - 自定义：透传用户填写的 promptGuideline 作为语气提示
     *
     * 段落结构固定为：开篇 + 按天分组（每张照片一段，末尾带 `[PHOTO:id]`）+ 结尾。
     */
    private fun buildContent(
        photos: List<PhotoEntity>,
        locationSummary: String?,
        dateRange: Pair<Long, Long>?,
        style: WritingStyleEntity?
    ): String {
        val sb = StringBuilder()

        sb.appendLine(buildOpening(locationSummary, dateRange, photos.size, style))
        sb.appendLine()

        val photosByDay = groupPhotosByDay(photos)
        val dayCount = photosByDay.size

        photosByDay.entries.forEachIndexed { dayIndex, (dayTimestamp, dayPhotos) ->
            val dayNum = if (dayCount > 1) "Day${dayIndex + 1}" else null
            sb.appendLine(buildDaySection(dayTimestamp, dayPhotos, dayNum, style))
            sb.appendLine()
        }

        sb.appendLine(buildClosing(locationSummary, photos, style))

        return sb.toString().trim()
    }

    private fun buildOpening(
        locationSummary: String?,
        dateRange: Pair<Long, Long>?,
        photoCount: Int,
        style: WritingStyleEntity?
    ): String {
        val location = if (!locationSummary.isNullOrBlank()) "前往${locationSummary}" else "开启这段旅程"
        val time = dateRange?.let {
            val start = DateFormatUtil.formatDate(it.first)
            val end = DateFormatUtil.formatDate(it.second)
            if (start == end) "，从${start}开始" else "，${start}到${end}"
        } ?: ""
        val photos = "共记录了${photoCount}个珍贵瞬间"

        // 美化风格 → 加入情感和意境；纪实/自定义 → 客观平实
        val baseSentence = when (style?.name) {
            "美化" ->
                "这次${location}${time}，${photos}。每一张照片都是一段故事，让我们一起回顾这段美好时光。"
            else -> style?.openingTone?.takeIf { it.isNotBlank() }?.let { tone ->
                "这次${location}${time}，${photos}。$tone"
            } ?: "这次${location}${time}，${photos}。"
        }
        return baseSentence
    }

    private fun groupPhotosByDay(photos: List<PhotoEntity>): Map<Long, List<PhotoEntity>> {
        return photos.groupBy { photo ->
            val timestamp = photo.takenAt ?: photo.createdAt
            DateFormatUtil.getStartOfDay(timestamp)
        }.toSortedMap()
    }

    /**
     * 构建单天段落。
     *
     * 每张照片独立一段：时间 · 地点 描述 + 照片内容描述，
     * 段末追加 `[PHOTO:id]` 标记独占一行，便于策略 B 正则匹配删除。
     */
    private fun buildDaySection(
        dayTimestamp: Long,
        dayPhotos: List<PhotoEntity>,
        dayNum: String?,
        style: WritingStyleEntity?
    ): String {
        val sb = StringBuilder()
        val dateStr = DateFormatUtil.formatDate(dayTimestamp)
        val header = if (dayNum != null) "【${dayNum} · ${dateStr}】" else "【${dateStr}】"
        sb.appendLine(header)

        val locations = dayPhotos.mapNotNull { it.locationName }.distinct()
        if (locations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("今天的行程涵盖了：${locations.joinToString("、")}${styleToneSuffix(style)}")
        }

        dayPhotos.forEachIndexed { index, photo ->
            val timeStr = photo.takenAt?.let { DateFormatUtil.formatTime(it) }
            val loc = photo.locationName
            val timeLoc = listOfNotNull(timeStr, loc).joinToString(" · ")
            if (timeLoc.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("▸ ${timeLoc}")
            }
            photo.description?.let { desc ->
                sb.appendLine("  ${desc}")
            }
            // 段末追加照片标记，便于策略 B 删除该段落
            sb.appendLine("[PHOTO:${photo.id}]")
        }

        return sb.toString().trimEnd()
    }

    private fun buildClosing(
        locationSummary: String?,
        photos: List<PhotoEntity>,
        style: WritingStyleEntity?
    ): String {
        val location = if (!locationSummary.isNullOrBlank()) locationSummary else "这里"
        val uniqueLocations = photos.mapNotNull { it.locationName }.distinct().size
        val days = groupPhotosByDay(photos).size

        val base = when {
            days > 1 && uniqueLocations > 1 ->
                "${days}天的旅程，走过${uniqueLocations}个地方，每一刻都值得珍藏。${location}留给我的不仅是风景，更是心中那份美好。期待下一次出发！"
            days > 1 ->
                "${days}天的旅程转瞬即逝，每一天都充满了惊喜与感动。${location}这段时光，将永远留在记忆深处。"
            uniqueLocations > 1 ->
                "一天里走过${uniqueLocations}个地方，虽然脚步匆匆，但风景和心情都满载而归。这一天的${location}之旅，收获满满。"
            else ->
                "美好的时光总是短暂，${location}的这段旅程虽已结束，但那些画面将永远定格在照片里，也定格在心中。"
        }

        // 美化风格 → 强调情感意境；自定义风格 → 透传 closingTone
        return when (style?.name) {
            "美化" -> base
            else -> style?.closingTone?.takeIf { it.isNotBlank() }?.let { tone ->
                "$base\n$tone"
            } ?: base
        }
    }

    /**
     * 风格语气后缀：用于在段落中体现 promptGuideline 的指导意图。
     * 当前实现仅在"今天的行程涵盖了"句尾附加微调，避免过度生成。
     */
    private fun styleToneSuffix(style: WritingStyleEntity?): String {
        if (style == null) return ""
        return when (style.name) {
            "美化" -> "，每一处都值得驻足品味"
            else -> ""
        }
    }

    private fun extractLocationSummary(photos: List<PhotoEntity>): String? {
        val locations = photos.mapNotNull { it.locationName }
        if (locations.isEmpty()) return null

        val frequency = locations.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

        return when {
            frequency.size == 1 -> frequency.first().first
            frequency.size <= 3 -> frequency.joinToString(" · ") { it.first }
            else -> {
                val top2 = frequency.take(2).joinToString(" · ") { it.first }
                "${top2}等地"
            }
        }
    }

    private fun extractDateRange(photos: List<PhotoEntity>): Pair<Long, Long>? {
        val times = photos.mapNotNull { it.takenAt }.sorted()
        if (times.isEmpty()) {
            val createTimes = photos.map { it.createdAt }.sorted()
            if (createTimes.isEmpty()) return null
            return DateFormatUtil.getStartOfDay(createTimes.first()) to
                    DateFormatUtil.getEndOfDay(createTimes.last())
        }
        return DateFormatUtil.getStartOfDay(times.first()) to
                DateFormatUtil.getEndOfDay(times.last())
    }
}

data class GeneratedContent(
    val title: String,
    val content: String,
    val locationSummary: String?,
    val startDate: Long?,
    val endDate: Long?,
    val coverPhotoPath: String?
)
