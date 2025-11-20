package com.example.agentclient.network

import com.example.agentclient.core.Logger
import com.example.agentclient.data.TaskQueue

/**
 * 命令处理器
 * 负责处理从服务器接收到的命令
 */
class CommandProcessor(
    private val taskQueue: TaskQueue,
    private val logger: Logger
) {

    /**
     * 处理命令列表
     */
    fun process(commands: List<HeartbeatService.Command>?) {
        if (commands.isNullOrEmpty()) {
            logger.debug("CommandProcessor", "Received empty or null command list, skipping processing")
            return
        }

        commands.forEach { command ->
            try {
                logger.info("CommandProcessor", "Processing command: ${command.type} (id=${command.id})")
                
                // 将命令转换为任务
                val task = taskQueue.createTaskFromCommand(
                    commandId = command.id,
                    type = command.type,
                    payload = command.params,
                    expiresAt = command.expiresAt
                )

                if (task != null) {
                    // 入队执行
                    taskQueue.addTask(task)
                    logger.debug("CommandProcessor", "Task added to queue: ${task.taskId}")
                } else {
                    logger.warn("CommandProcessor", "Unknown or invalid command type: ${command.type}, id=${command.id}")
                }
            } catch (e: Exception) {
                // 捕获异常，防止单个命令处理失败影响整个流程
                logger.warn("CommandProcessor", "Error processing command: ${command.id}. Error: ${e.message}")
            }
        }
    }
}
