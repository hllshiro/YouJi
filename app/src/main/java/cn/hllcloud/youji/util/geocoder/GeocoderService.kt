package cn.hllcloud.youji.util.geocoder

/**
 * 地理编码（逆地理反查）服务统一接口。
 *
 * 设计背景见 docs/design/DESIGN_V3.md 8.5 节：Android 原生 Geocoder 必须联网，
 * 国内手机后端被厂商替换成腾讯/高德/百度等国内服务，可用性不稳定。
 * 不同实现（系统 / 高德 / Nominatim）按回退链组合使用，由 [CompositeGeocoderService] 编排。
 */
interface GeocoderService {
    /**
     * 逆地理反查：根据经纬度返回地址信息。
     *
     * 实现需保证线程安全，网络调用必须在 IO 调度器执行。
     * 失败时返回 [Result.failure]，不抛异常。
     *
     * @param lat 纬度
     * @param lng 经度
     */
    suspend fun reverse(lat: Double, lng: Double): Result<GeocodeAddress>

    /**
     * 是否可用。可预检测的实现（如系统 Geocoder、高德 Key）返回真实状态；
     * 无法预检测的实现（如 Nominatim，只要网络通即可）返回 true。
     */
    fun isAvailable(): Boolean
}

/**
 * 逆地理反查结果。
 *
 * @param formatted 完整地址文本，如"河南省郑州市中原区..."
 * @param country 国家
 * @param province 省/州
 * @param city 市
 * @param district 区/县
 */
data class GeocodeAddress(
    val formatted: String,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null
)
