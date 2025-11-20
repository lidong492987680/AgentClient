package com.example.agentclient.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.agentclient.core.Config
import com.example.agentclient.core.DeviceIdManager
import com.example.agentclient.core.Logger
import com.example.agentclient.data.TaskQueue
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 心跳服务
 * 负责定期向服务器发送心跳，上报状态并接收命令
 * 
 * 职责：
 * 1. 维护心跳定时任务
 * 2. 网络通信
 * 3. 委托 DeviceStatusCollector 收集状态
 * 4. 委托 CommandProcessor 处理命令
 */
class HeartbeatService private constructor(private val context: Context) {

    private val logger = Logger.getInstance(context)
    private val config = Config.getInstance(context)
    private val deviceIdManager = DeviceIdManager.getInstance(context)
    private val apiClient = ApiClient.getInstance(context)
    private val taskQueue = TaskQueue.getInstance(context)

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 委托对象
    private val commandProcessor = CommandProcessor(taskQueue, logger)
    private val deviceStatusCollector = DeviceStatusCollector(context, logger)

    // 状态标志
    private val isRunning = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)
    private val lastHeartbeatTime = AtomicLong(0)
    private val lastSuccessTime = AtomicLong(0)

    // 心跳任务
    private var heartbeatJob: Job? = null
    private var heartbeatRunnable: Runnable? = null

    // 当前状态
    private var currentScriptStatus: ScriptStatus? = null
    private var lastError: ErrorInfo? = null
    private var accessibilityEnabled = false

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val FAILURE_BACKOFF_MULTIPLIER = 2

        @Volatile
        private var instance: HeartbeatService? = null

        fun getInstance(context: Context): HeartbeatService {
            return instance ?: synchronized(this) {
                instance ?: HeartbeatService(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 心跳请求数据
     */
    data class HeartbeatRequest(
        @SerializedName("device_id")
        val deviceId: String,

        @SerializedName("server_device_id")
        val serverDeviceId: String? = null,

        @SerializedName("app_version")
        val appVersion: String,

        @SerializedName("model")
        val model: String,

        @SerializedName("os_version")
        val osVersion: String,

        @SerializedName("battery")
        val battery: Float,

        @SerializedName("charging")
        val charging: Boolean,

        @SerializedName("network")
        val network: String,

        @SerializedName("group_tag")
        val groupTag: String? = null,

        @SerializedName("script")
        val script: ScriptStatus? = null,

        @SerializedName("accessibility_enabled")
        val accessibilityEnabled: Boolean,

        @SerializedName("last_error")
        val lastError: ErrorInfo? = null,

        @SerializedName("resource_stats")
        val resourceStats: ResourceStats? = null,

        @SerializedName("results")
        val results: List<TaskQueue.TaskResult>? = null
    )

    /**
     * 心跳响应数据
     */
    data class HeartbeatResponse(
        @SerializedName("success")
        val success: Boolean = true,

        @SerializedName("config")
        val config: Map<String, Any>? = null,

        @SerializedName("commands")
        val commands: List<Command>? = null,

        @SerializedName("server_time")
        val serverTime: Long? = null,

        @SerializedName("message")
        val message: String? = null
    )

    /**
     * 服务器命令
     */
    data class Command(
        @SerializedName("id")
        val id: String,

        @SerializedName("type")
        val type: String,

        @SerializedName("params")
        val params: Map<String, Any> = emptyMap(),

        @SerializedName("expires_at")
        val expiresAt: Long? = null,

        @SerializedName("priority")
        val priority: Int = 50
    )

    /**
     * 脚本状态
     */
    data class ScriptStatus(
        @SerializedName("key")
        val key: String,

        @SerializedName("status")
        val status: String, // running, idle, error, paused

        @SerializedName("last_success")
        val lastSuccess: Long? = null,

        @SerializedName("daily_runtime_min")
        val dailyRuntimeMin: Int = 0,

        @SerializedName("current_state")
        val currentState: String? = null,

        @SerializedName("start_time")
        val startTime: Long? = null
    )

    /**
     * 错误信息
     */
    data class ErrorInfo(
        @SerializedName("code")
        val code: String,

        @SerializedName("message")
        val message: String? = null,

        @SerializedName("time")
        val time: Long = System.currentTimeMillis(),

        @SerializedName("count")
        val count: Int = 1
    )

    /**
     * 资源统计
     */
    data class ResourceStats(
        @SerializedName("cpu_percent")
        val cpuPercent: Int = 0,

        @SerializedName("memory_mb")
        val memoryMb: Int = 0,

        @SerializedName("storage_available_mb")
        val storageAvailableMb: Long = 0
    )

    /**
     * 启动心跳服务
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            logger.warn("HeartbeatService", "Service already running")
            return
        }

        logger.info("HeartbeatService", "Starting heartbeat service")

        // 重置计数器
        consecutiveFailures.set(0)

        // 立即发送第一次心跳
        sendHeartbeat()

        // 启动定期心跳
        scheduleNextHeartbeat()
    }

    /**
     * 停止心跳服务
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            logger.warn("HeartbeatService", "Service not running")
            return
        }

        logger.info("HeartbeatService", "Stopping heartbeat service")

        // 取消心跳任务
        heartbeatJob?.cancel()
        heartbeatRunnable?.let { handler.removeCallbacks(it) }

        // 发送最后一次心跳（可选）
        sendFinalHeartbeat()
    }

    /**
     * 调度下一次心跳
     */
    private fun scheduleNextHeartbeat() {
        if (!isRunning.get()) return

        val interval = calculateHeartbeatInterval()

        heartbeatRunnable = Runnable {
            if (isRunning.get()) {
                sendHeartbeat()
                scheduleNextHeartbeat()
            }
        }

        heartbeatRunnable?.let {
            handler.postDelayed(it, interval)
        }

        logger.debug("HeartbeatService", "Next heartbeat scheduled in ${interval}ms")
    }

    /**
     * 计算心跳间隔
     */
    private fun calculateHeartbeatInterval(): Long {
        val baseInterval = config.get().heartbeatIntervalSec * 1000L

        // 如果连续失败，使用指数退避
        return if (consecutiveFailures.get() > 0) {
            val backoffFactor = Math.pow(
                FAILURE_BACKOFF_MULTIPLIER.toDouble(),
                consecutiveFailures.get().coerceAtMost(5).toDouble()
            ).toLong()
            (baseInterval * backoffFactor).coerceAtMost(300000L) // 最多5分钟
        } else {
            baseInterval
        }
    }

    /**
     * 发送心跳
     */
    private fun sendHeartbeat() {
        if (!isRunning.get()) return

        lastHeartbeatTime.set(System.currentTimeMillis())

        scope.launch {
            try {
                val request = buildHeartbeatRequest()
                logger.debug("HeartbeatService", "Sending heartbeat...")

                val response = apiClient.post(
                    path = "/heartbeat",
                    body = request,
                    clazz = HeartbeatResponse::class.java
                )

                handleHeartbeatResponse(response)

                // 重置失败计数
                consecutiveFailures.set(0)
                lastSuccessTime.set(System.currentTimeMillis())

                logger.info("HeartbeatService", "Heartbeat sent successfully")

            } catch (e: CancellationException) {
                // 协程被主动取消，不算失败
                throw e
            } catch (e: Exception) {
                handleHeartbeatFailure(e)
            }
        }
    }

    /**
     * 发送最后一次心跳
     */
    private fun sendFinalHeartbeat() {
        scope.launch {
            try {
                val request = buildHeartbeatRequest().copy(
                    script = currentScriptStatus?.copy(status = "stopped")
                )

                apiClient.post(
                    path = "/heartbeat",
                    body = request,
                    clazz = HeartbeatResponse::class.java
                )

                logger.info("HeartbeatService", "Final heartbeat sent")

            } catch (e: Exception) {
                logger.error("HeartbeatService", "Failed to send final heartbeat", e)
            }
        }
    }

    /**
     * 构建心跳请求
     */
    private fun buildHeartbeatRequest(): HeartbeatRequest {
        // 使用 DeviceStatusCollector 收集设备状态
        val status = deviceStatusCollector.collectDeviceStatus()
        
        return HeartbeatRequest(
            deviceId = deviceIdManager.getDeviceId(),
            serverDeviceId = deviceIdManager.getServerDeviceId(),
            appVersion = status.appVersion,
            model = status.model,
            osVersion = status.osVersion,
            battery = status.battery,
            charging = status.charging,
            network = status.network,
            groupTag = status.groupTag,
            script = currentScriptStatus,
            accessibilityEnabled = accessibilityEnabled,
            lastError = lastError,
            resourceStats = status.resourceStats,
            results = taskQueue.consumeTaskResults()
        )
    }

    /**
     * 处理心跳响应
     */
    private fun handleHeartbeatResponse(response: HeartbeatResponse) {
        // 更新配置
        response.config?.let { configMap ->
            logger.info("HeartbeatService", "Updating configuration from server")
            config.updatePartial(configMap)
        }

        // 委托 CommandProcessor 处理命令
        response.commands?.let { commands ->
            if (commands.isNotEmpty()) {
                logger.info("HeartbeatService", "Received ${commands.size} commands")
                commandProcessor.process(commands)
            }
        }

        // 同步服务器时间（可选）
        response.serverTime?.let { serverTime ->
            val timeDiff = Math.abs(serverTime - System.currentTimeMillis())
            if (timeDiff > 60000) { // 超过1分钟的时差
                logger.warn("HeartbeatService", "Time difference with server: ${timeDiff}ms")
            }
        }
    }

    /**
     * 处理心跳失败
     */
    private fun handleHeartbeatFailure(error: Throwable) {
        val failures = consecutiveFailures.incrementAndGet()

        logger.error("HeartbeatService", "Heartbeat failed (${failures}/${MAX_CONSECUTIVE_FAILURES})", error)

        // 记录错误
        lastError = ErrorInfo(
            code = "HEARTBEAT_FAILURE",
            message = error.message,
            count = failures
        )

        // 达到最大失败次数，触发降级
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            logger.error("HeartbeatService", "Max consecutive failures reached, entering degraded mode")
            enterDegradedMode()
        }
    }

    /**
     * 进入降级模式
     */
    private fun enterDegradedMode() {
        // 通知脚本引擎降低执行频率
        // 暂停非关键功能（如截图上传）
        // 这里需要与其他模块配合
    }

    /**
     * 更新脚本状态
     */
    fun updateScriptStatus(status: ScriptStatus) {
        currentScriptStatus = status
    }

    /**
     * 更新无障碍状态
     */
    fun updateAccessibilityStatus(enabled: Boolean) {
        accessibilityEnabled = enabled
    }

    /**
     * 报告错误
     */
    fun reportError(code: String, message: String?) {
        lastError = ErrorInfo(code = code, message = message)
    }

    /**
     * 获取服务状态
     */
    fun getStatus(): ServiceStatus {
        return ServiceStatus(
            isRunning = isRunning.get(),
            lastHeartbeatTime = lastHeartbeatTime.get(),
            lastSuccessTime = lastSuccessTime.get(),
            consecutiveFailures = consecutiveFailures.get(),
            currentScriptStatus = currentScriptStatus,
            lastError = lastError
        )
    }

    data class ServiceStatus(
        val isRunning: Boolean,
        val lastHeartbeatTime: Long,
        val lastSuccessTime: Long,
        val consecutiveFailures: Int,
        val currentScriptStatus: ScriptStatus?,
        val lastError: ErrorInfo?
    )
}