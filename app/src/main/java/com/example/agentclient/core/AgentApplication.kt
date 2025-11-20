package com.example.agentclient.core

import android.app.Application
import com.example.agentclient.scripts.engine.ScriptEngine

/**
 * 应用程序入口
 * 负责初始化各个核心组件
 */
class AgentApplication : Application() {

    companion object {
        lateinit var instance: AgentApplication
            private set
    }

    lateinit var deviceId: String
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val deviceIdManager = DeviceIdManager.getInstance(this)
        deviceId = deviceIdManager.getDeviceId()
        // 初始化核心组件
        initializeCore()
    }

    private fun initializeCore() {
        // 初始化设备ID管理器
        val deviceIdManager = DeviceIdManager.getInstance(this)
        val deviceId = deviceIdManager.getDeviceId()

        // 初始化日志系统
        val logger = Logger.getInstance(this)
        logger.info("Application", "AgentClient starting...")
        logger.info("Application", "Device ID: $deviceId")

        // 初始化配置
        val config = Config.getInstance(this)
        logger.info("Application", "Configuration loaded: ${config.get().baseUrl}")

        // 设置调试模式
        if (config.get().debugMode) {
            logger.setMinLevel(Logger.Level.DEBUG)
        } else {
            logger.setMinLevel(Logger.Level.INFO)
        }
        
        // 初始化脚本引擎
        ScriptEngine.initialize(applicationContext)
        logger.info("Application", "ScriptEngine 初始化完成")
        
        // 初始化 UiDriver（会自动注册所有脚本）
        UiDriver.getInstance(applicationContext)
        logger.info("Application", "UiDriver 初始化完成，脚本已注册")
    }
}