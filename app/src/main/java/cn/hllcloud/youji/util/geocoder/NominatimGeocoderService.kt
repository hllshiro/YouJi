package cn.hllcloud.youji.util.geocoder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenStreetMap Nominatim 免 key 兜底实现。
 *
 * - 接口：https://nominatim.openstreetmap.org/reverse
 * - 免 key，但官方使用政策要求 **最大 1 请求/秒**，否则会封 IP。
 *   本实现通过 [mutex] 强制串行，并保证两次请求发起时间间隔 >= [MIN_INTERVAL_MS]（1100ms）。
 * - 必须设置 User-Agent，否则会被拒绝。
 *
 * @see <a href="https://nominatim.org/release-docs/develop/api/Reverse/">Nominatim Reverse API</a>
 */
class NominatimGeocoderService : GeocoderService {

    private val mutex = Mutex()
    private var lastCallTimeMs: Long = 0L

    override fun isAvailable(): Boolean = true
    // Nominatim 只要网络通即可用，无预检测手段；具体可用性由 reverse() 的成功/失败体现。

    override suspend fun reverse(lat: Double, lng: Double): Result<GeocodeAddress> = mutex.withLock {
        // ===== 强制 1.1s 间隔限流（Nominatim 政策：最大 1 请求/秒，否则封 IP）=====
        if (lastCallTimeMs > 0L) {
            val elapsed = System.currentTimeMillis() - lastCallTimeMs
            if (elapsed < MIN_INTERVAL_MS) {
                delay(MIN_INTERVAL_MS - elapsed)
            }
        }
        // 记录本次请求发起时间，作为下一次间隔判断基准（start-to-start 间隔 >= 1100ms）
        lastCallTimeMs = System.currentTimeMillis()

        doReverse(lat, lng)
    }

    private suspend fun doReverse(lat: Double, lng: Double): Result<GeocodeAddress> =
        withContext(Dispatchers.IO) {
            try {
                val urlStr = buildString {
                    append("https://nominatim.openstreetmap.org/reverse")
                    append("?lat=").append(lat)
                    append("&lon=").append(lng)
                    append("&format=jsonv2")
                    append("&accept-language=zh")
                    append("&zoom=18")
                }
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", USER_AGENT)

                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val response = readAll(stream)

                    if (code !in 200..299) {
                        return@withContext Result.failure(
                            RuntimeException("Nominatim 请求失败 (HTTP $code): ${response.take(200)}")
                        )
                    }

                    val json = JSONObject(response)
                    val displayName = json.optString("display_name").trim()
                    if (displayName.isEmpty()) {
                        return@withContext Result.failure(IOException("Nominatim 返回空地址"))
                    }

                    val address = json.optJSONObject("address")
                    val country = address?.optString("country")?.takeIf { it.isNotBlank() }
                    val province = address?.optString("state")?.takeIf { it.isNotBlank() }
                        ?: address?.optString("province")?.takeIf { it.isNotBlank() }
                    // 城市级字段 Nominatim 在不同地区返回 key 不一，按常见优先级取
                    val city = address?.optString("city")?.takeIf { it.isNotBlank() }
                        ?: address?.optString("town")?.takeIf { it.isNotBlank() }
                        ?: address?.optString("village")?.takeIf { it.isNotBlank() }
                        ?: address?.optString("county")?.takeIf { it.isNotBlank() }
                        ?: address?.optString("municipality")?.takeIf { it.isNotBlank() }
                    val district = address?.optString("suburb")?.takeIf { it.isNotBlank() }
                        ?: address?.optString("city_district")?.takeIf { it.isNotBlank() }
                        ?: address?.optString("neighbourhood")?.takeIf { it.isNotBlank() }

                    Result.success(
                        GeocodeAddress(
                            formatted = displayName,
                            country = country,
                            province = province,
                            city = city,
                            district = district
                        )
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun readAll(stream: java.io.InputStream): String =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        }

    companion object {
        private const val USER_AGENT = "YouJi/1.0"
        private const val MIN_INTERVAL_MS = 1100L
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 20000
    }
}
