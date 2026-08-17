package com.youji.app.util

import com.youji.app.data.entity.PhotoEntity
import com.youji.app.data.entity.TravelNoteEntity
import kotlin.math.abs

/**
 * 本地游记内容生成器（不依赖VLM）
 * 基于照片元信息智能生成游记模板内容
 */
object LocalContentGenerator {

    /**
     * 基于照片列表生成游记内容
     */
    fun generateContent(photos: List<PhotoEntity>, title: String = ""): GeneratedContent {
        val sortedPhotos = photos.sortedBy { it.takenAt ?: it.createdAt }
        val locationSummary = extractLocationSummary(sortedPhotos)
        val dateRange = extractDateRange(sortedPhotos)
        val coverPhoto = sortedPhotos.firstOrNull()

        val finalTitle = title.ifBlank { generateTitle(sortedPhotos, locationSummary, dateRange) }
        val content = buildContent(sortedPhotos, locationSummary, dateRange)

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
        // 优先用地名
        if (!locationSummary.isNullOrBlank()) {
            val days = dateRange?.let { DateFormatUtil.daysBetween(it.first, it.second) } ?: 1
            return if (days > 1) {
                "${locationSummary}${days}日游"
            } else {
                "$locationSummary · 游记"
            }
        }
        // 其次用日期
        dateRange?.let {
            return "${DateFormatUtil.formatShort(it.first)}的旅行记忆"
        }
        // 兜底
        return "游记 · ${DateFormatUtil.formatDate(photos.firstOrNull()?.createdAt)}"
    }

    private fun buildContent(
        photos: List<PhotoEntity>,
        locationSummary: String?,
        dateRange: Pair<Long, Long>?
    ): String {
        val sb = StringBuilder()

        // 开篇
        sb.appendLine(buildOpening(locationSummary, dateRange, photos.size))
        sb.appendLine()

        // 按天分组展示
        val photosByDay = groupPhotosByDay(photos)
        val dayCount = photosByDay.size

        photosByDay.entries.forEachIndexed { dayIndex, (dayTimestamp, dayPhotos) ->
            val dayNum = if (dayCount > 1) "Day${dayIndex + 1}" else null
            sb.appendLine(buildDaySection(dayTimestamp, dayPhotos, dayNum))
            sb.appendLine()
        }

        // 结尾
        sb.appendLine(buildClosing(locationSummary, photos))

        return sb.toString().trim()
    }

    private fun buildOpening(
        locationSummary: String?,
        dateRange: Pair<Long, Long>?,
        photoCount: Int
    ): String {
        val location = if (!locationSummary.isNullOrBlank()) "前往${locationSummary}" else "开启这段旅程"
        val time = dateRange?.let {
            val start = DateFormatUtil.formatDate(it.first)
            val end = DateFormatUtil.formatDate(it.second)
            if (start == end) "，从${start}开始" else "，${start}到${end}"
        } ?: ""
        val photos = "共记录了${photoCount}个珍贵瞬间"

        return "这次${location}${time}，${photos}。每一张照片都是一段故事的开始，让我们一起回顾这段美好时光。"
    }

    private fun groupPhotosByDay(photos: List<PhotoEntity>): Map<Long, List<PhotoEntity>> {
        return photos.groupBy { photo ->
            val timestamp = photo.takenAt ?: photo.createdAt
            DateFormatUtil.getStartOfDay(timestamp)
        }.toSortedMap()
    }

    private fun buildDaySection(
        dayTimestamp: Long,
        dayPhotos: List<PhotoEntity>,
        dayNum: String?
    ): String {
        val sb = StringBuilder()
        val dateStr = DateFormatUtil.formatDate(dayTimestamp)
        val header = if (dayNum != null) "【${dayNum} · ${dateStr}】" else "【${dateStr}】"
        sb.appendLine(header)

        // 收集当天的位置
        val locations = dayPhotos.mapNotNull { it.locationName }.distinct()

        if (locations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("今天的行程涵盖了：${locations.joinToString("、")}。")
        }

        // 每个地点的描述
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
        }

        return sb.toString()
    }

    private fun buildClosing(locationSummary: String?, photos: List<PhotoEntity>): String {
        val location = if (!locationSummary.isNullOrBlank()) locationSummary else "这里"
        val uniqueLocations = photos.mapNotNull { it.locationName }.distinct().size
        val days = groupPhotosByDay(photos).size

        return when {
            days > 1 && uniqueLocations > 1 ->
                "${days}天的旅程，走过${uniqueLocations}个地方，每一刻都值得珍藏。${location}留给我的不仅是风景，更是心中那份美好。期待下一次出发！"
            days > 1 ->
                "${days}天的旅程转瞬即逝，每一天都充满了惊喜与感动。${location}这段时光，将永远留在记忆深处。"
            uniqueLocations > 1 ->
                "一天里走过${uniqueLocations}个地方，虽然脚步匆匆，但风景和心情都满载而归。这一天的${location}之旅，收获满满。"
            else ->
                "美好的时光总是短暂，${location}的这段旅程虽已结束，但那些画面将永远定格在照片里，也定格在心中。"
        }
    }

    private fun extractLocationSummary(photos: List<PhotoEntity>): String? {
        val locations = photos.mapNotNull { it.locationName }
        if (locations.isEmpty()) return null

        // 统计出现频率
        val frequency = locations.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

        return when {
            frequency.size == 1 -> frequency.first().first
            frequency.size <= 3 -> frequency.joinToString(" · ") { it.first }
            else -> {
                // 取前两个最多的，再加上"等地"
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
