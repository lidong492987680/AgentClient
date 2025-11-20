package com.example.agentclient.scripts.engine

import android.content.Context
import com.example.agentclient.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 脚本调度引擎
 * 负责管理和执行脚本的生命周期
 * 
 * 职责：
 * - 启动/停止脚本
 * - 管理脚本执行的协程
 * - 调用脚本的生命周期方法
 * - 提供脚本运行状态查询
 */
object ScriptEngine {
    
    private var context: Context? = null
    private var logger: Logger? = null
    
    // 脚本注册表：存储脚本工厂函数
    private val scriptFactories: MutableMap<String, (com.example.agentclient.scripts.behavior.HumanizedAction) -> BaseScript> = mutableMapOf()
    
    // 当前正在运行的脚本
    private var currentScript: BaseScript? = null
    
    // 脚本执行的协程任务
    private var scriptJob: Job? = null
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * 初始化 ScriptEngine
     * 必须在使用前调用
     */
    fun initialize(ctx: Context) {
        context = ctx
        logger = Logger.getInstance(ctx)
    }
    
    /**
     * 启动脚本
     * 如果已有脚本在运行，会先停止旧脚本
     */
    fun startScript(script: BaseScript) {
        val log = logger ?: return
        
        // 如果已有脚本在运行，先停止
        if (currentScript != null) {
            log.info("ScriptEngine", "Stopping current script before starting new one")
            stopCurrentScript()
        }
        
        currentScript = script
        
        // 启动协程执行脚本
        scriptJob = scope.launch {
            try {
                log.info("ScriptEngine", "Starting script: ${script.getScriptName()}")
                
                // 调用脚本的 onStart
                script.onStart()
                
                // 循环执行脚本步骤，直到脚本完成或被取消
                while (isActive && !script.isFinished()) {
                    script.runStepSafely()
                    delay(script.stepIntervalMs)
                }
                
                // 调用脚本的 onStop
                script.onStop()
                
                log.info("ScriptEngine", "Script finished: ${script.getScriptName()}")
            } catch (e: Exception) {
                log.error("ScriptEngine", "Error running script", e)
            } finally {
                // 清理
                currentScript = null
                scriptJob = null
            }
        }
    }
    
    /**
     * 停止当前运行的脚本
     */
    fun stopCurrentScript() {
        val log = logger ?: return
        
        // 标记脚本为完成状态
        currentScript?.markFinished()
        
        // 取消协程
        scriptJob?.cancel()
        
        log.info("ScriptEngine", "Script stopped")
        
        // 清理
        currentScript = null
        scriptJob = null
    }
    
    /**
     * 检查是否有脚本正在运行
     */
    fun isRunning(): Boolean {
        return scriptJob?.isActive == true
    }
    
    /**
     * 注册脚本工厂
     * 允许按名称创建和启动脚本
     * 
     * @param name 脚本名称（唯一标识）
     * @param factory 脚本工厂函数，接收 HumanizedAction 返回 BaseScript
     */
    fun registerScript(name: String, factory: (com.example.agentclient.scripts.behavior.HumanizedAction) -> BaseScript) {
        val log = logger
        
        if (scriptFactories.containsKey(name)) {
            log?.warn("ScriptEngine", "脚本 '$name' 已存在，将被覆盖")
        }
        
        scriptFactories[name] = factory
        log?.info("ScriptEngine", "脚本 '$name' 注册成功")
    }
    
    /**
     * 按名称启动脚本
     * 从注册表中查找脚本工厂并创建实例启动
     * 
     * @param name 脚本名称
     * @param humanizedAction HumanizedAction 实例
     * @return true 如果启动成功，false 如果脚本未注册
     */
    fun startScriptByName(name: String, humanizedAction: com.example.agentclient.scripts.behavior.HumanizedAction): Boolean {
        val log = logger ?: return false
        
        val factory = scriptFactories[name]
        if (factory == null) {
            log.warn("ScriptEngine", "脚本 '$name' 未注册，无法启动")
            return false
        }
        
        // 使用工厂创建脚本实例
        val script = factory(humanizedAction)
        
        // 启动脚本
        startScript(script)
        log.info("ScriptEngine", "通过名称启动脚本: $name")
        
        return true
    }
    
    /**
     * 获取当前运行脚本的名称
     */
    fun getCurrentScriptName(): String? {
        return currentScript?.getScriptName()
    }
}

/**
 * 脚本异常类
 * 用于区分不同类型的脚本错误
 */
class ScriptException(
    message: String,
    val type: Type,
    cause: Throwable? = null
) : Exception(message, cause) {
    
    enum class Type {
        RECOVERABLE,  // 可恢复的错误
        FATAL,        // 致命错误
        TIMEOUT       // 超时错误
    }
}
