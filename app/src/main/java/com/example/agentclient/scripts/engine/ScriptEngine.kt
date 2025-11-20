package com.example.agentclient.scripts.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.agentclient.accessibility.AgentAccessibilityService
import com.example.agentclient.core.Config
import com.example.agentclient.core.Logger
import com.example.agentclient.data.TaskQueue
import com.example.agentclient.network.HeartbeatService
import com.example.agentclient.scripts.BaseScript
import com.example.agentclient.scripts.TestScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * スクリプトエンジン
 * 自動化スクリプトの管理・実行を担当する
 */
class ScriptEngine private constructor(private val context: Context) {

    private val logger = Logger.getInstance(context)
    private val config = Config.getInstance(context)
    private val taskQueue = TaskQueue.getInstance(context)
    private val heartbeatService = HeartbeatService.getInstance(context)

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // スクリプト登録
    private val scriptRegistry = ConcurrentHashMap<String, () -> BaseScript>()

    // 現在のスクリプト
    private var currentScript: BaseScript? = null
    private var currentScriptKey: String? = null

    // 状態
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val scriptStartTime = AtomicLong(0)
    private val lastTickTime = AtomicLong(0)

    // Tick ジョブ
    private var tickJob: Job? = null

    companion object {
        @Volatile
        private var instance: ScriptEngine? = null

        fun getInstance(context: Context): ScriptEngine {
            return instance ?: synchronized(this) {
                instance ?: ScriptEngine(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }
    }

    enum class ScriptState {
        IDLE,
        STARTING,
        RUNNING,
        PAUSED,
        STOPPING,
        ERROR
    }

    data class ScriptResult(
        val scriptKey: String,
        val success: Boolean,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val runTime: Long = 0,
        val completedTasks: Int = 0
    )

    /**
     * 初期化
     */
    private fun initialize() {
        registerBuiltinScripts()
        startTaskProcessor()
        logger.info("ScriptEngine", "Script engine initialized")
    }

    /**
     * 内蔵スクリプト登録
     */
    private fun registerBuiltinScripts() {
        registerScript("test_script") { TestScript(context) }
        // TODO: 他のゲーム用スクリプトをここに登録
    }

    /**
     * タスク処理ループ（修正版）
     *
     * - 以前は「スクリプトが動いていないときだけ」キューを消費していた。
     * - その結果、RUNNING 中は STOP/PAUSE/RESUME が永遠に処理されない問題があった。
     * - 今は常に processNextTask() を呼び出し、各タスクの中で「現在の状態に応じて実行可否」を判断する。
     */
    private fun startTaskProcessor() {
        scope.launch {
            while (true) {
                delay(1000) // 1秒ごとに1件だけ見る（十分）
                processNextTask()
            }
        }
    }

    /**
     * 次のタスクを処理
     */
    private fun processNextTask() {
        val task = taskQueue.getNextTask() ?: return

        logger.info("ScriptEngine", "Processing task: ${task.taskId} (${task.type})")

        scope.launch {
            try {
                when (task.type) {
                    TaskQueue.TaskType.START_SCRIPT -> {
                        // すでにスクリプトが動いている場合は、新しい START_SCRIPT を受け付けない
                        if (isRunning.get()) {
                            logger.warn(
                                "ScriptEngine",
                                "START_SCRIPT ignored because another script is already running: $currentScriptKey"
                            )
                            taskQueue.markTaskResult(
                                task,
                                TaskQueue.TaskStatus.FAILED,
                                "SCRIPT_ALREADY_RUNNING",
                                "Another script ($currentScriptKey) is already running"
                            )
                        } else {
                            val scriptKey = task.payload["script_key"] as? String
                            val params = task.payload["params"] as? Map<String, Any> ?: emptyMap()

                            if (scriptKey != null) {
                                startScript(scriptKey, params)
                                taskQueue.markTaskResult(
                                    task,
                                    TaskQueue.TaskStatus.COMPLETED
                                )
                            } else {
                                taskQueue.markTaskResult(
                                    task,
                                    TaskQueue.TaskStatus.FAILED,
                                    "INVALID_PARAMS",
                                    "Missing script_key"
                                )
                            }
                        }
                    }

                    TaskQueue.TaskType.STOP_SCRIPT -> {
                        // 実行中でなくても STOP は「状態リセット」として受け付ける
                        stopScript()
                        taskQueue.markTaskResult(
                            task,
                            TaskQueue.TaskStatus.COMPLETED
                        )
                    }

                    TaskQueue.TaskType.PAUSE -> {
                        pauseScript()
                        taskQueue.markTaskResult(
                            task,
                            TaskQueue.TaskStatus.COMPLETED
                        )
                    }

                    TaskQueue.TaskType.RESUME -> {
                        resumeScript()
                        taskQueue.markTaskResult(
                            task,
                            TaskQueue.TaskStatus.COMPLETED
                        )
                    }

                    else -> {
                        logger.warn("ScriptEngine", "Unhandled task type: ${task.type}")
                        taskQueue.markTaskResult(
                            task,
                            TaskQueue.TaskStatus.FAILED,
                            "UNSUPPORTED_TYPE",
                            "Task type not supported by ScriptEngine"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("ScriptEngine", "Error processing task", e)
                taskQueue.markTaskResult(
                    task,
                    TaskQueue.TaskStatus.FAILED,
                    "EXECUTION_ERROR",
                    e.message
                )

                if (task.canRetry() && isRetriableError(e)) {
                    taskQueue.retryTask(task, 5000) // 5秒後に再試行
                }
            }
        }
    }

    /**
     * スクリプト登録
     */
    fun registerScript(key: String, factory: () -> BaseScript) {
        scriptRegistry[key] = factory
        logger.info("ScriptEngine", "Script registered: $key")
    }

    /**
     * スクリプト開始
     */
    suspend fun startScript(key: String, params: Map<String, Any> = emptyMap()) {
        if (!AgentAccessibilityService.isEnabled()) {
            logger.error("ScriptEngine", "Accessibility service not enabled")
            heartbeatService.reportError("ACCESSIBILITY_DISABLED", "Accessibility service not enabled")
            return
        }

        if (!config.get().isInAllowedTime()) {
            logger.warn("ScriptEngine", "Not in allowed time range")
            heartbeatService.reportError("NOT_IN_ALLOWED_TIME", "Script execution not allowed at this time")
            return
        }

        // 既存スクリプト停止
        if (currentScript != null) {
            logger.info("ScriptEngine", "Stopping current script before starting new one")
            stopScript()
        }

        val factory = scriptRegistry[key]
        if (factory == null) {
            logger.error("ScriptEngine", "Script not found: $key")
            heartbeatService.reportError("SCRIPT_NOT_FOUND", "Script $key not registered")
            return
        }

        val script = factory()
        currentScript = script
        currentScriptKey = key

        isRunning.set(true)
        isPaused.set(false)
        scriptStartTime.set(System.currentTimeMillis())

        updateHeartbeatStatus(ScriptState.STARTING)
        logger.info("ScriptEngine", "Starting script: $key")

        try {
            script.onStart(params)
            startTickLoop()
            updateHeartbeatStatus(ScriptState.RUNNING)
        } catch (e: Exception) {
            logger.error("ScriptEngine", "Failed to start script", e)
            handleScriptError(e)
        }
    }

    /**
     * スクリプト停止
     */
    fun stopScript() {
        if (currentScript == null) {
            logger.warn("ScriptEngine", "No script running")
            // 何も動いていなくても状態だけ IDLE にしておく
            updateHeartbeatStatus(ScriptState.IDLE)
            return
        }

        logger.info("ScriptEngine", "Stopping script: $currentScriptKey")

        stopTickLoop()

        try {
            currentScript?.onStop()
        } catch (e: Exception) {
            logger.error("ScriptEngine", "Error stopping script", e)
        }

        currentScript = null
        currentScriptKey = null
        isRunning.set(false)
        isPaused.set(false)

        updateHeartbeatStatus(ScriptState.IDLE)
    }

    fun pauseScript() {
        if (!isRunning.get()) {
            logger.warn("ScriptEngine", "No script running to pause")
            return
        }

        if (isPaused.get()) {
            logger.debug("ScriptEngine", "Script already paused")
            return
        }

        isPaused.set(true)
        currentScript?.onPause()
        updateHeartbeatStatus(ScriptState.PAUSED)
        logger.info("ScriptEngine", "Script paused")
    }

    fun resumeScript() {
        if (!isRunning.get()) {
            logger.warn("ScriptEngine", "No script running to resume")
            return
        }

        if (!isPaused.get()) {
            logger.debug("ScriptEngine", "Script is not paused")
            return
        }

        isPaused.set(false)
        currentScript?.onResume()
        updateHeartbeatStatus(ScriptState.RUNNING)
        logger.info("ScriptEngine", "Script resumed")
    }

    private fun startTickLoop() {
        val tickInterval = config.get().scriptTickMs
        tickJob = scope.launch {
            while (isRunning.get()) {
                if (!isPaused.get()) {
                    tick()
                }
                delay(tickInterval)
            }
        }
    }

    private fun stopTickLoop() {
        tickJob?.cancel()
        tickJob = null
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val runtime = now - scriptStartTime.get()
        val maxRuntime = config.get().maxScriptRuntimeMin * 60 * 1000L

        if (runtime > maxRuntime) {
            logger.warn("ScriptEngine", "Script exceeded max runtime, stopping")
            stopScript()
            heartbeatService.reportError("SCRIPT_TIMEOUT", "Script exceeded maximum runtime")
            return
        }

        try {
            currentScript?.onTick()
            lastTickTime.set(now)
        } catch (e: Exception) {
            logger.error("ScriptEngine", "Error in script tick", e)
            handleScriptError(e)
        }
    }

    private fun handleScriptError(error: Throwable) {
        currentScript?.onError(error)

        when (error) {
            is ScriptException -> {
                when (error.type) {
                    ScriptException.Type.RECOVERABLE -> {
                        logger.warn("ScriptEngine", "Recoverable error: ${error.message}")
                    }

                    ScriptException.Type.FATAL -> {
                        logger.error("ScriptEngine", "Fatal error: ${error.message}")
                        stopScript()
                        heartbeatService.reportError("SCRIPT_FATAL_ERROR", error.message)
                    }

                    ScriptException.Type.TIMEOUT -> {
                        logger.error("ScriptEngine", "Timeout error: ${error.message}")
                        currentScript?.reset()
                    }
                }
            }

            else -> {
                stopScript()
                heartbeatService.reportError("SCRIPT_ERROR", error.message)
            }
        }

        updateHeartbeatStatus(ScriptState.ERROR)
    }

    private fun isRetriableError(error: Throwable): Boolean {
        return error is ScriptException && error.type == ScriptException.Type.RECOVERABLE
    }

    private fun updateHeartbeatStatus(state: ScriptState) {
        val status = HeartbeatService.ScriptStatus(
            key = currentScriptKey ?: "",
            status = state.name.lowercase(),
            lastSuccess = if (state == ScriptState.IDLE) System.currentTimeMillis() else null,
            dailyRuntimeMin = ((System.currentTimeMillis() - scriptStartTime.get()) / 60000).toInt(),
            currentState = currentScript?.getStatistics()?.currentState,
            startTime = scriptStartTime.get()
        )
        heartbeatService.updateScriptStatus(status)
    }

    fun getStatus(): EngineStatus {
        return EngineStatus(
            isRunning = isRunning.get(),
            isPaused = isPaused.get(),
            currentScriptKey = currentScriptKey,
            scriptStartTime = scriptStartTime.get(),
            lastTickTime = lastTickTime.get(),
            registeredScripts = scriptRegistry.keys.toList()
        )
    }

    data class EngineStatus(
        val isRunning: Boolean,
        val isPaused: Boolean,
        val currentScriptKey: String?,
        val scriptStartTime: Long,
        val lastTickTime: Long,
        val registeredScripts: List<String>
    )
}

/**
 * スクリプト例外
 */
class ScriptException(
    message: String,
    val type: Type,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class Type {
        RECOVERABLE,
        FATAL,
        TIMEOUT
    }
}
