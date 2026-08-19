package cn.hllcloud.youji.util.geocoder

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 系统级 [Geocoder] 封装。
 *
 * 注意：Android 原生 Geocoder **不是本地功能**，底层始终走网络请求：
 * - 海外机型走 Google Maps Geocoding API
 * - 国内机型后端被厂商替换成腾讯/高德/百度等国内服务（小米/华为/OPPO/vivo 等）
 *
 * 无网络时 [Geocoder.getFromLocation] 返回空列表或抛 [IOException]。
 * 该方法是同步阻塞网络调用，必须在 IO 调度器执行。
 */
class SystemGeocoderService(private val context: Context) : GeocoderService {

    override fun isAvailable(): Boolean = try {
        Geocoder.isPresent()
    } catch (e: Throwable) {
        // 个别机型 isPresent() 实现异常时按不可用处理
        false
    }

    override suspend fun reverse(lat: Double, lng: Double): Result<GeocodeAddress> =
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context)
                @Suppress("DEPRECATION") // 同步重载在新 API 仍可用，且本服务无 callback 回调入口
                val addresses: List<Address>? = geocoder.getFromLocation(lat, lng, 1)

                if (addresses.isNullOrEmpty()) {
                    return@withContext Result.failure(IOException("系统 Geocoder 返回空结果"))
                }

                val addr = addresses[0]
                val formatted = if (addr.maxAddressLineIndex >= 0) {
                    addr.getAddressLine(0)
                } else {
                    // 极少情况无 addressLine，由行政区分级字段拼接兜底
                    listOfNotNull(addr.countryName, addr.adminArea, addr.locality, addr.subLocality)
                        .filter { it.isNotBlank() }
                        .joinToString("")
                }

                if (formatted.isBlank()) {
                    return@withContext Result.failure(IOException("系统 Geocoder 解析出空地址"))
                }

                Result.success(
                    GeocodeAddress(
                        formatted = formatted,
                        country = addr.countryName?.takeIf { it.isNotBlank() },
                        province = addr.adminArea?.takeIf { it.isNotBlank() },
                        city = (addr.locality ?: addr.subAdminArea)?.takeIf { it.isNotBlank() },
                        district = addr.subLocality?.takeIf { it.isNotBlank() }
                    )
                )
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
