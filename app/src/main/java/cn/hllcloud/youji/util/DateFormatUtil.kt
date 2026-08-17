package cn.hllcloud.youji.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 日期格式化工具
 */
object DateFormatUtil {

    private val sdfFull = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
    private val sdfDate = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
    private val sdfShort = SimpleDateFormat("MM月dd日", Locale.CHINA)
    private val sdfTime = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val sdfFileName = SimpleDateFormat("yyyyMMdd", Locale.CHINA)

    /**
     * 格式化完整日期时间
     */
    fun formatFull(timestamp: Long?): String {
        if (timestamp == null) return ""
        return sdfFull.format(Date(timestamp))
    }

    /**
     * 格式化日期（年月日）
     */
    fun formatDate(timestamp: Long?): String {
        if (timestamp == null) return ""
        return sdfDate.format(Date(timestamp))
    }

    /**
     * 格式化短日期（月日）
     */
    fun formatShort(timestamp: Long?): String {
        if (timestamp == null) return ""
        return sdfShort.format(Date(timestamp))
    }

    /**
     * 格式化时间
     */
    fun formatTime(timestamp: Long?): String {
        if (timestamp == null) return ""
        return sdfTime.format(Date(timestamp))
    }

    /**
     * 格式化为文件名用的日期
     */
    fun formatFileName(timestamp: Long): String {
        return sdfFileName.format(Date(timestamp))
    }

    /**
     * 格式化日期范围
     */
    fun formatDateRange(startDate: Long?, endDate: Long?): String {
        return when {
            startDate == null && endDate == null -> ""
            startDate != null && endDate == null -> formatDate(startDate)
            startDate == null && endDate != null -> formatDate(endDate)
            else -> {
                val startCal = Calendar.getInstance().apply { time = Date(startDate!!) }
                val endCal = Calendar.getInstance().apply { time = Date(endDate!!) }
                if (startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR)) {
                    if (startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH) &&
                        startCal.get(Calendar.DAY_OF_MONTH) == endCal.get(Calendar.DAY_OF_MONTH)
                    ) {
                        formatDate(startDate)
                    } else {
                        "${sdfShort.format(Date(startDate!!))} - ${sdfShort.format(Date(endDate!!))}"
                    }
                } else {
                    "${formatDate(startDate)} - ${formatDate(endDate)}"
                }
            }
        }
    }

    /**
     * 计算两个日期之间的天数
     */
    fun daysBetween(startDate: Long?, endDate: Long?): Int {
        if (startDate == null || endDate == null) return 1
        val diff = Math.abs(endDate - startDate)
        return TimeUnit.MILLISECONDS.toDays(diff).toInt() + 1
    }

    /**
     * 获取一天的开始时间戳
     */
    fun getStartOfDay(timestamp: Long): Long {
        val calendar = Calendar.getInstance().apply {
            time = Date(timestamp)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /**
     * 获取一天的结束时间戳
     */
    fun getEndOfDay(timestamp: Long): Long {
        val calendar = Calendar.getInstance().apply {
            time = Date(timestamp)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return calendar.timeInMillis
    }

    /**
     * 获取今天的开始时间戳
     */
    fun todayStart(): Long = getStartOfDay(System.currentTimeMillis())

    /**
     * 获取今天的结束时间戳
     */
    fun todayEnd(): Long = getEndOfDay(System.currentTimeMillis())
}
