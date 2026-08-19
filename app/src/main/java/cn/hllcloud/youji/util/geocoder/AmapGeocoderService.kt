package cn.hllcloud.youji.util.geocoder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 高德 Web 服务逆地理编码实现。
 *
 * - 接口：https://restapi.amap.com/v3/geocode/regeo
 * - 需要用户配置高德 Key，[isAvailable] 仅在 Key 非空时返回 true。
 * - **注意**：高德 location 参数顺序为 `lng,lat`（经度在前），与 EXIF 的 `lat,lng` 相反，
 *   本实现内部已做翻转，调用方按 (lat, lng) 传入即可。
 *
 * @param amapKey 高德 Web 服务 Key
 */
class AmapGeocoderService(private val amapKey: String) : GeocoderService {

    override fun isAvailable(): Boolean = amapKey.isNotBlank()

    override suspend fun reverse(lat: Double, lng: Double): Result<GeocodeAddress> {
        if (amapKey.isBlank()) {
            return Result.failure(IllegalStateException("高德 Key 未配置"))
        }
        return withContext(Dispatchers.IO) {
            try {
                // 高德 location 顺序为 lng,lat（经度在前），与传入的 lat,lng 相反
                val location = "$lng,$lat"
                val urlStr = buildString {
                    append("https://restapi.amap.com/v3/geocode/regeo")
                    append("?key=").append(amapKey)
                    append("&location=").append(location)
                    append("&extensions=base")
                    append("&output=JSON")
                }
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS

                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val response = readAll(stream)

                    if (code !in 200..299) {
                        return@withContext Result.failure(
                            RuntimeException("高德请求失败 (HTTP $code): ${response.take(200)}")
                        )
                    }

                    val json = JSONObject(response)
                    val status = json.optString("status")
                    if (status != "1") {
                        val info = json.optString("info", "未知错误")
                        val infocode = json.optString("infocode", "")
                        return@withContext Result.failure(
                            RuntimeException("高德逆地理编码失败: $info (infocode=$infocode)")
                        )
                    }

                    val regeocode = json.optJSONObject("regeocode")
                        ?: return@withContext Result.failure(IOException("高德响应缺少 regeocode 字段"))
                    val formatted = regeocode.optString("formatted_address").trim()
                    if (formatted.isEmpty()) {
                        return@withContext Result.failure(IOException("高德返回空地址"))
                    }

                    val address = regeocode.optJSONObject("address")
                    // 高德对直辖市，city 字段返回 "[]" 字符串，需过滤
                    val province = address?.optString("province")?.takeIf { it.isNotBlank() && it != "[]" }
                    val city = address?.optString("city")?.takeIf { it.isNotBlank() && it != "[]" }
                    val district = address?.optString("district")?.takeIf { it.isNotBlank() && it != "[]" }

                    Result.success(
                        GeocodeAddress(
                            formatted = formatted,
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
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 20000
    }
}
