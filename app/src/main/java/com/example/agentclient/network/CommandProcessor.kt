package com.example.agentclient.network

import android.content.Context
import com.example.agentclient.core.Logger
import com.example.agentclient.core.UiDriver

/**
 * 命令处理器
 * 负责处理从服务器接收到的命令并执行实际操作
 * 
 * 支持的命令类型：
 * - start_script: 启动指定脚本
 * - stop_script: 停止当前脚本
 * - set_profile: 设置行为配置
 */
class CommandProcessor(
    private val context: Context,
    private val logger: Logger
) {

    /**
     * 处理命令列表
     */
    fun process(commands: List<HeartbeatService.Command>?) {
        if (commands.isNullOrEmpty()) {
            logger.debug("CommandProcessor", "收到空命令列表，跳过处理")
            return
        }

        val uiDriver = UiDriver.getInstance(context)

        commands.forEach { command ->
            try {
                logger.info("CommandProcessor", "处理命令: ${command.type} (id=${command.id})")
                
                when (command.type) {
                    "start_script" -> {
                        val scriptName = command.params["script_name"] as? String ?: "test_script"
                        logger.info("CommandProcessor", "收到服务器 start_script 命令: $scriptName")
                        uiDriver.startScriptByName(scriptName)
                    }
                    
                    "stop_script" -> {
                        logger.info("CommandProcessor", "收到服务器 stop_script 命令")
                        uiDriver.stopScript()
                    }
                    
                    "set_profile" -> {
                        val profileName = command.params["profile"] as? String ?: "DEFAULT"
                        logger.info("CommandProcessor", "收到服务器 set_profile 命令: $profileName")
                        uiDriver.setBehaviorProfile(profileName)
                    }
                    
                    else -> {
                        logger.warn("CommandProcessor", "未知命令类型: ${command.type}, id=${command.id}")
                    }
                }
            } catch (e: Exception) {
                // 捕获异常，防止单个命令处理失败影响整个流程
                logger.error("CommandProcessor", "处理命令时出错: ${command.id}", e)
            }
        }
    }
}
