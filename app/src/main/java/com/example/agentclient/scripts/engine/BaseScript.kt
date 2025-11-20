package com.example.agentclient.scripts.engine

import com.example.agentclient.core.Logger
import com.example.agentclient.scripts.behavior.HumanizedAction

/**
 * 脚本基类
 * 所有自动化脚本都应继承此类
 * 
 * 设计原则：
 * - 提供统一的执行框架
 * - 处理异常，防止脚本崩溃
 * - 提供生命周期钩子供子类实现
 */
abstract class BaseScript(
    protected val humanizedAction: HumanizedAction,
    protected val logger: Logger
) {
    
    // 步骤执行间隔时间（毫秒）
    open val stepIntervalMs: Long = 2000L
    
    // 脚本完成标志
    private var finished: Boolean = false
    
    // 错误计数（用于异常过多时自动停止）
    private var errorCount: Int = 0
    private val maxErrors = 5
    
    /**
     * 脚本启动时调用
     * 子类应在此处进行初始化操作
     */
    abstract suspend fun onStart()
    
    /**
     * 每个步骤执行时调用
     * 子类应在此处实现主要逻辑
     */
    abstract suspend fun onStep()
    
    /**
     * 脚本停止时调用
     * 子类应在此处进行清理操作
     */
    abstract suspend fun onStop()
    
    /**
     * 标记脚本完成
     * 子类在完成所有步骤后应调用此方法
     */
    fun markFinished() {
        finished = true
        logger.info(getScriptName(), "Script marked as finished")
    }
    
    /**
     * 检查脚本是否完成
     */
    fun isFinished(): Boolean = finished
    
    /**
     * 获取当前状态描述
     * 子类可重写此方法提供更详细的状态信息
     */
    open fun getCurrentState(): String? = null

    /**
     * 错误回调
     * 当脚本执行出错时调用
     */
    var onError: ((Throwable) -> Unit)? = null

    /**
     * 安全执行一个步骤
     * 捕获异常，防止脚本崩溃
     * 异常过多时自动标记为完成
     */
    suspend fun runStepSafely() {
        try {
            onStep()
            errorCount = 0 // 成功执行后重置错误计数
        } catch (e: Exception) {
            errorCount++
            logger.error(getScriptName(), "Error in onStep (count: $errorCount)", e)
            
            // 调用错误回调
            onError?.invoke(e)
            
            // 异常过多时停止脚本
            if (errorCount >= maxErrors) {
                logger.error(getScriptName(), "Too many errors ($errorCount), marking script as finished")
                markFinished()
            }
        }
    }
    
    /**
     * 获取脚本名称（用于日志）
     */
    abstract fun getScriptName(): String
}
