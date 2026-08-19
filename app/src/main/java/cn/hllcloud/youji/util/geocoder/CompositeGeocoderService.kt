package cn.hllcloud.youji.util.geocoder

/**
 * 地理编码服务异常。组合服务全部失败时抛出/封装此异常。
 */
class GeocodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 回退链组合的 [GeocoderService]。
 *
 * 按构造时传入的 [primaryServices] 顺序（优先级从高到低）依次尝试，
 * 第一个成功的即返回；全部失败则返回 [Result.failure]，异常 message 汇总各服务失败原因便于调试。
 *
 * 设计文档 8.5 节回退链路：
 * `系统 Geocoder` → `高德(有 key 时)` → `Nominatim(免 key)` → 失败 → 返回仅坐标
 *
 * @param primaryServices 按优先级排序的服务列表
 */
class CompositeGeocoderService(
    private val primaryServices: List<GeocoderService>
) : GeocoderService {

    override fun isAvailable(): Boolean = primaryServices.any { it.isAvailable() }

    override suspend fun reverse(lat: Double, lng: Double): Result<GeocodeAddress> {
        if (primaryServices.isEmpty()) {
            return Result.failure(GeocodeException("未配置任何地理编码服务"))
        }

        val errors = mutableListOf<String>()
        for (service in primaryServices) {
            val name = service.javaClass.simpleName

            // 跳过预检测不可用的服务（如未配置高德 Key、系统 Geocoder 不存在），
            // 避免无意义的网络调用。对应回退链中"高德(有 key 时)"的语义。
            if (!service.isAvailable()) {
                errors.add("$name: 不可用")
                continue
            }

            val result = try {
                service.reverse(lat, lng)
            } catch (e: Exception) {
                errors.add("$name: ${e.message ?: e.javaClass.simpleName}")
                continue
            }

            if (result.isSuccess) {
                return result
            }
            val cause = result.exceptionOrNull()
            errors.add("$name: ${cause?.message ?: cause?.javaClass?.simpleName ?: "失败"}")
        }

        return Result.failure(GeocodeException("所有地理编码服务均不可用: ${errors.joinToString("; ")}"))
    }
}
