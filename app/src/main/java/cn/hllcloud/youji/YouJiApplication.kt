package cn.hllcloud.youji

import android.app.Application
import cn.hllcloud.youji.data.AppDatabase
import cn.hllcloud.youji.data.AppPreferencesRepository
import cn.hllcloud.youji.data.TravelRepository
import cn.hllcloud.youji.data.VlmSettingsRepository
import cn.hllcloud.youji.data.WritingStyleRepository
import cn.hllcloud.youji.util.VlmClient
import cn.hllcloud.youji.util.WorkflowEngine
import cn.hllcloud.youji.util.geocoder.AmapGeocoderService
import cn.hllcloud.youji.util.geocoder.CompositeGeocoderService
import cn.hllcloud.youji.util.geocoder.GeocoderService
import cn.hllcloud.youji.util.geocoder.NominatimGeocoderService
import cn.hllcloud.youji.util.geocoder.SystemGeocoderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 全局 Application 类。
 *
 * 持有 App 范围单例：[database]、[repository]、[vlmSettingsRepository]、
 * [writingStyleRepository]、[geocoderService]、[workflowEngine]。
 *
 * 启动时执行 [markUnfinishedTasksAsPaused]：将上一会话遗留的 pending/running 任务
 * 静默转为 paused，等待用户在主页主动恢复。对应设计 V3 第 2.4 节。
 */
class YouJiApplication : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }

    val repository by lazy {
        TravelRepository(
            database.photoDao(),
            database.travelNoteDao(),
            database.workflowTaskDao()
        )
    }

    val vlmSettingsRepository by lazy { VlmSettingsRepository(this) }

    /**
     * 应用级别偏好（如 [setupCompleted] 标记），对应设计 V3 第 2.5 节
     * 首次启动引导页的状态记忆。与 VlmSettings 独立存储，避免污染。
     */
    val appPreferencesRepository by lazy { AppPreferencesRepository(this) }

    val writingStyleRepository by lazy {
        WritingStyleRepository(database.writingStyleDao())
    }

    /**
     * 地理编码回退链：System → Nominatim → 失败返回仅坐标。
     * 高德服务在有 key 时动态加入（详见 [buildGeocoderService]）。
     */
    val geocoderService: GeocoderService by lazy { buildGeocoderService() }

    val vlmClient by lazy { VlmClient() }

    val workflowEngine by lazy {
        WorkflowEngine(
            taskDao = database.workflowTaskDao(),
            phaseResultDao = database.workflowPhaseResultDao(),
            photoDao = database.photoDao(),
            geocoderService = geocoderService,
            vlmClient = vlmClient,
            vlmSettingsRepository = vlmSettingsRepository,
            repository = repository,
            writingStyleRepository = writingStyleRepository
        )
    }

    /**
     * Application 级别协程作用域，用于 [markUnfinishedTasksAsPaused] 等启动时副作用。
     * 不与任何 ViewModel 绑定，避免 UI 退出影响启动逻辑。
     */
    private val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private var INSTANCE: YouJiApplication? = null
        fun get(): YouJiApplication = INSTANCE
            ?: throw IllegalStateException("Application not created")
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        // 启动时静默将未完成任务（pending/running）转为 paused。
        // 设计 V3 第 2.4 节：静默完成，不弹任何对话框，由主页待恢复区块呈现。
        markUnfinishedTasksAsPaused()
    }

    /**
     * 静默将 pending/running 状态的 workflow_task 全部更新为 paused。
     * 在 [onCreate] 中通过 [appScope] 异步执行，不阻塞 UI 启动。
     */
    private fun markUnfinishedTasksAsPaused() {
        appScope.launch {
            try {
                database.workflowTaskDao().markUnfinishedAsPaused()
            } catch (e: Exception) {
                // 启动阶段失败不影响主功能，仅打印日志
                e.printStackTrace()
            }
        }
    }

    /**
     * 构建地理编码服务回退链。
     *
     * 顺序：
     * 1. SystemGeocoderService（系统 Geocoder，离线可用，准确度依赖厂商）
     * 2. AmapGeocoderService（高德，仅在用户配置 Key 时插入，精度高）
     * 3. NominatimGeocoderService（在线兜底）
     *
     * 高德 Key 由设置页 [AppPreferencesRepository.amapKey] 提供。
     * 由于 [geocoderService] 在首次访问时初始化（lazy），
     * 用户在设置页修改 Key 后需要重启 App 才能生效。
     */
    private fun buildGeocoderService(): GeocoderService {
        val services = mutableListOf<GeocoderService>(
            SystemGeocoderService(this),
            NominatimGeocoderService()
        )
        // 启动时读取高德 Key，非空则插入到 Nominatim 之前（index=1）
        runBlocking {
            appPreferencesRepository.amapKey.first().takeIf { it.isNotBlank() }?.let { amapKey ->
                services.add(1, AmapGeocoderService(amapKey))
            }
        }
        return CompositeGeocoderService(services)
    }
}
