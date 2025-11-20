package com.example.agentclient.scripts.engine

import com.example.agentclient.core.Logger
import com.example.agentclient.scripts.behavior.HumanizedAction

/**
 * 测试脚本
 * 用于验证脚本执行链路是否正常
 * 
 * 功能：
 * - 执行5次简单的滚动操作
 * - 每次操作之间有延迟
 * - 完成后自动停止
 */
class TestScript(
    humanizedAction: HumanizedAction,
    logger: Logger
) : BaseScript(humanizedAction, logger) {
    
    // 步骤计数器
    private var stepCount = 0
    
    // 最大步骤数
    private val maxSteps = 5
    
    override fun getScriptName(): String = "TestScript"
    
    /**
     * 脚本启动时调用
     */
    override suspend fun onStart() {
        logger.info(getScriptName(), "TestScript started")
        stepCount = 0
    }
    
    /**
     * 每个步骤执行的逻辑
     * 这里实现简单的上下滚动操作
     */
    override suspend fun onStep() {
        stepCount++
        logger.info(getScriptName(), "Executing step $stepCount/$maxSteps")
        
        // 交替进行向上和向下滚动
        val direction = if (stepCount % 2 == 0) {
            HumanizedAction.ScrollDirection.UP
        } else {
            HumanizedAction.ScrollDirection.DOWN
        }
        
        // 执行滚动操作
        val success = humanizedAction.scroll(direction,300f)
        logger.debug(getScriptName(), "Scroll $direction result: $success")
        
        // 达到最大步骤数后标记为完成
        if (stepCount >= maxSteps) {
            logger.info(getScriptName(), "Reached max steps, marking as finished")
            markFinished()
        }
    }
    
    /**
     * 脚本停止时调用
     */
    override suspend fun onStop() {
        logger.info(getScriptName(), "TestScript finished with steps=$stepCount")
    }
}
