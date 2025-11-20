package com.example.agentclient.core

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 全局配置管理
 * 支持本地默认值、远程覆盖、热更新
 */
class Config private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val logger = Logger.getInstance(context)

    // 当前生效的配置
    private var currentConfig: ConfigData = loadConfig()

    companion object {
        private const val PREFS_NAME = "agent_config"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_LAST_UPDATE = "last_update_time"

        @Volatile
        private var instance: Config? = null

        fun getInstance(context: Context): Config {
            return instance ?: synchronized(this) {
                instance ?: Config(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 配置数据类
     */
    data class ConfigData(
        @SerializedName("base_url")
        val baseUrl: String = "https://api.example.com",

        @SerializedName("heartbeat_interval_sec")
        val heartbeatIntervalSec: Int = 30,

        @SerializedName("script_tick_ms")
        val scriptTickMs: Long = 1000,

        @SerializedName("screenshot_min_interval_sec")
        val screenshotMinIntervalSec: Int = 30,

        @SerializedName("max_script_runtime_min")
        val maxScriptRuntimeMin: Int = 120,

        @SerializedName("max_concurrent_scripts")
        val maxConcurrentScripts: Int = 1,

        // 风控参数
        @SerializedName("daily_max_runtime_min")
        val dailyMaxRuntimeMin: Int = 600, // 每日最多运行10小时

        @SerializedName("min_rest_sec")
        val minRestSec: Int = 5,

        @SerializedName("max_rest_sec")
        val maxRestSec: Int = 30,

        @SerializedName("allowed_hours")
        val allowedHours: List<Int> = (8..23).toList(), // 默认8:00-23:59允许运行

        @SerializedName("night_mode_enabled")
        val nightModeEnabled: Boolean = true,

        // 资源控制
        @SerializedName("max_cpu_percent")
        val maxCpuPercent: Int = 60,

        @SerializedName("max_memory_mb")
        val maxMemoryMb: Int = 512,

        // 错误阈值
        @SerializedName("max_errors_per_window")
        val maxErrorsPerWindow: Int = 50,

        @SerializedName("error_window_min")
        val errorWindowMin: Int = 10,

        // 调试模式
        @SerializedName("debug_mode")
        val debugMode: Boolean = false,

        @SerializedName("developer_mode")
        val developerMode: Boolean = false,

        // 版本信息
        @SerializedName("min_app_version")
        val minAppVersion: String? = null,

        @SerializedName("force_update")
        val forceUpdate: Boolean = false
    ) {
        /**
         * ✅ 把“是否在允许运行时间内”的判断放到 ConfigData 里
         * 这样就可以 config.get().isInAllowedTime() 直接调用
         */
        fun isInAllowedTime(): Boolean {
            if (!nightModeEnabled) {
                return true
            }

            val currentHour = java.util.Calendar.getInstance()
                .get(java.util.Calendar.HOUR_OF_DAY)

            // allowedHours 是 List<Int>，形如 [8,9,...,23]
            return currentHour in allowedHours
        }
    }

    /**
     * 操作节奏配置
     */
    data class RhythmConfig(
        val minDelay: Long = 500,
        val maxDelay: Long = 2000,
        val clickOffsetPixels: Int = 5,
        val swipeSpeedVariation: Float = 0.2f
    )

    /**
     * 获取当前配置
     */
    fun get(): ConfigData {
        return currentConfig
    }

    /**
     * 更新配置（来自服务器）
     */
    fun updateFromRemote(remoteConfig: ConfigData) {
        try {
            // 合并配置（远程覆盖本地）
            currentConfig = mergeConfig(currentConfig, remoteConfig)

            // 保存到本地
            saveConfig(currentConfig)

            // 记录更新时间
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()

            logger.info("Config", "Configuration updated from remote")

        } catch (e: Exception) {
            logger.error("Config", "Failed to update configuration", e)
        }
    }

    /**
     * 部分更新配置
     */
    fun updatePartial(updates: Map<String, Any>) {
        try {
            val json = gson.toJson(currentConfig)
            val map = gson.fromJson(json, Map::class.java).toMutableMap()

            // 应用更新
            updates.forEach { (key, value) ->
                map[key] = value
            }

            // 转换回ConfigData
            val updatedJson = gson.toJson(map)
            currentConfig = gson.fromJson(updatedJson, ConfigData::class.java)

            saveConfig(currentConfig)

            logger.info("Config", "Configuration partially updated: ${updates.keys}")

        } catch (e: Exception) {
            logger.error("Config", "Failed to update configuration partially", e)
        }
    }

    /**
     * 从本地加载配置
     */
    private fun loadConfig(): ConfigData {
        return try {
            val json = prefs.getString(KEY_CONFIG_JSON, null)
            if (json != null) {
                gson.fromJson(json, ConfigData::class.java)
            } else {
                ConfigData() // 使用默认值
            }
        } catch (e: Exception) {
            logger.error("Config", "Failed to load configuration, using defaults", e)
            ConfigData()
        }
    }

    /**
     * 保存配置到本地
     */
    private fun saveConfig(config: ConfigData) {
        try {
            val json = gson.toJson(config)
            prefs.edit().putString(KEY_CONFIG_JSON, json).apply()
        } catch (e: Exception) {
            logger.error("Config", "Failed to save configuration", e)
        }
    }

    /**
     * 合并配置
     */
    private fun mergeConfig(local: ConfigData, remote: ConfigData): ConfigData {
        // 约束：服务端下发的 remoteConfig 必须是完整配置，未提供的字段将使用服务器端默认值。
        // 当前实现：直接整体覆盖本地配置。
        // 这里简单地用远程配置覆盖
        // 实际可以根据字段做更复杂的合并逻辑
        return remote
    }

    /**
     * 重置为默认配置
     */
    fun reset() {
        currentConfig = ConfigData()
        saveConfig(currentConfig)
        logger.warn("Config", "Configuration reset to defaults")
    }

    /**
     * 获取节奏配置
     */
    fun getRhythmConfig(scriptKey: String? = null): RhythmConfig {
        // 这里可以根据不同脚本返回不同的节奏配置
        // 暂时返回默认值
        return RhythmConfig()
    }

    /**
     * ✅ 兼容原来的用法：Config.isInAllowedTime()
     * 内部直接调用 currentConfig.isInAllowedTime()
     */
    fun isInAllowedTime(): Boolean {
        return currentConfig.isInAllowedTime()
    }

    /**
     * 获取最后更新时间
     */
    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0)
    }
}
