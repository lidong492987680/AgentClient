package com.example.agentclient.core

import android.content.Context
import com.example.agentclient.scripts.behavior.HumanizedAction
import com.example.agentclient.scripts.engine.ScriptEngine
import com.example.agentclient.scripts.engine.TestScript

/**
 * UI操作统一入口
 * 作为门面模式，对外提供简单的脚本控制接口
 * 
 * 职责：
 * - 管理 HumanizedAction 实例
 * - 提供脚本启动/停止接口
 * - 桥接 MainActivity 和 ScriptEngine
 * - 注册所有可用脚本
 */
class UiDriver private constructor(private val context: Context) {
    
    private val logger = Logger.getInstance(context)
    private var humanizedAction: HumanizedAction? = null
    
    // 是否已注册脚本
    private var scriptsRegistered = false
    
    companion object {
        @Volatile
        private var instance: UiDriver? = null
        
        fun getInstance(context: Context): UiDriver {
            return instance ?: synchronized(this) {
                instance ?: UiDriver(context.applicationContext).also {
                    instance = it
                    // 初始化时注册所有脚本
                    it.registerAllScripts()
                }
            }
        }
    }
    
    /**
     * 注册所有可用的脚本
     * 在应用启动时调用一次
     */
    private fun registerAllScripts() {
        if (scriptsRegistered) {
            logger.warn("UiDriver", "脚本已经注册过，跳过重复注册")
            return
        }
        
        // 注册测试脚本
        ScriptEngine.registerScript("test_script") { humanizedAction ->
            TestScript(humanizedAction, logger)
        }
        
        // 将来可以在这里注册更多脚本
        // ScriptEngine.registerScript("game_a_script") { humanizedAction ->
        //     GameAScript(humanizedAction, logger)
        // }
        
        scriptsRegistered = true
        logger.info("UiDriver", "所有脚本注册完成")
    }
    
    /**
     * 启动测试脚本
     * 使用脚本注册系统按名称启动
     */
    fun startTestScript() {
        // 初始化 HumanizedAction（如果还未初始化）
        if (humanizedAction == null) {
            humanizedAction = HumanizedAction(context, BehaviorProfile.DEFAULT)
            logger.info("UiDriver", "HumanizedAction initialized with DEFAULT profile")
        }
        
        // 按名称启动脚本
        val success = ScriptEngine.startScriptByName("test_script", humanizedAction!!)
        
        if (success) {
            logger.info("UiDriver", "测试脚本启动成功")
        } else {
            logger.error("UiDriver", "测试脚本启动失败：脚本未注册")
        }
    }
    
    /**
     * 按名称启动脚本（通用接口，供 CommandProcessor 等使用）
     * 
     * @param scriptName 脚本名称
     * @return true 如果启动成功
     */
    fun startScriptByName(scriptName: String): Boolean {
        // 初始化 HumanizedAction（如果还未初始化）
        if (humanizedAction == null) {
            humanizedAction = HumanizedAction(context, BehaviorProfile.DEFAULT)
            logger.info("UiDriver", "HumanizedAction initialized with DEFAULT profile")
        }
        
        val success = ScriptEngine.startScriptByName(scriptName, humanizedAction!!)
        
        if (success) {
            logger.info("UiDriver", "脚本 '$scriptName' 启动成功")
        } else {
            logger.error("UiDriver", "脚本 '$scriptName' 启动失败：脚本未注册")
        }
        
        return success
    }
    
    /**
     * 停止当前脚本
     */
    fun stopScript() {
        ScriptEngine.stopCurrentScript()
        logger.info("UiDriver", "当前脚本已停止")
    }
    
    /**
     * 检查脚本是否正在运行
     */
    fun isScriptRunning(): Boolean {
        return ScriptEngine.isRunning()
    }
    
    /**
     * 获取当前运行脚本的名称
     */
    fun getCurrentScriptName(): String? {
        return ScriptEngine.getCurrentScriptName()
    }
}
