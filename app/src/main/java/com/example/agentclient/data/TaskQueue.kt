package com.example.agentclient.data

import android.content.Context
import com.example.agentclient.core.Logger
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 任务队列管理器
 * 负责缓存和管理从服务器接收的任务
 */
class TaskQueue private constructor(private val context: Context) {

    private val logger = Logger.getInstance(context)
    private val gson = Gson()

    // 优先级队列，按优先级和创建时间排序
    private val queue = PriorityBlockingQueue<Task>(100, TaskComparator())

    // 已完成任务的结果缓存
    private val completedTasks = ConcurrentHashMap<String, TaskResult>()

    // 任务ID生成器
    private val taskIdGenerator = AtomicLong(System.currentTimeMillis())

    companion object {
        @Volatile
        private var instance: TaskQueue? = null

        fun getInstance(context: Context): TaskQueue {
            return instance ?: synchronized(this) {
                instance ?: TaskQueue(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 任务类型
     */
    enum class TaskType(val priority: Int) {
        STOP_SCRIPT(100),        // 最高优先级
        PAUSE(90),
        RESUME(85),
        START_SCRIPT(80),
        UPDATE_CONFIG(70),
        SCREENSHOT(60),
        CHECK_UPDATE(50),
        SET_ACCOUNT(40),
        SET_GROUP_TAG(30),
        CUSTOM(20)              // 最低优先级
    }

    /**
     * 任务状态
     */
    enum class TaskStatus {
        PENDING,    // 待执行
        EXECUTING,  // 执行中
        COMPLETED,  // 已完成
        FAILED,     // 失败
        EXPIRED,    // 已过期
        CANCELLED   // 已取消
    }

    /**
     * 任务数据类
     */
    data class Task(
        @SerializedName("task_id")
        val taskId: String,

        @SerializedName("type")
        val type: TaskType,

        @SerializedName("payload")
        val payload: Map<String, Any> = emptyMap(),

        @SerializedName("priority")
        val priority: Int = 50,

        @SerializedName("expires_at")
        val expiresAt: Long = System.currentTimeMillis() + 3600000, // 默认1小时过期

        @SerializedName("retry_count")
        var retryCount: Int = 0,

        @SerializedName("max_retries")
        val maxRetries: Int = 3,

        @SerializedName("created_at")
        val createdAt: Long = System.currentTimeMillis(),

        @SerializedName("status")
        var status: TaskStatus = TaskStatus.PENDING,

        @SerializedName("command_id")
        val commandId: String? = null,  // 服务器命令ID

        @SerializedName("source")
        val source: String = "server"   // 任务来源
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt

        fun canRetry(): Boolean = retryCount < maxRetries

        fun getEffectivePriority(): Int {
            // 综合考虑类型优先级和自定义优先级
            return type.priority * 1000 + priority
        }
    }

    /**
     * 任务执行结果
     */
    data class TaskResult(
        @SerializedName("task_id")
        val taskId: String,

        @SerializedName("command_id")
        val commandId: String?,

        @SerializedName("status")
        val status: TaskStatus,

        @SerializedName("error_code")
        val errorCode: String? = null,

        @SerializedName("error_message")
        val errorMessage: String? = null,

        @SerializedName("result_data")
        val resultData: Map<String, Any>? = null,

        @SerializedName("executed_at")
        val executedAt: Long = System.currentTimeMillis(),

        @SerializedName("duration_ms")
        val durationMs: Long = 0
    )

    /**
     * 任务比较器
     */
    private class TaskComparator : Comparator<Task> {
        override fun compare(t1: Task, t2: Task): Int {
            // 先按有效优先级排序（高优先级在前）
            val priorityDiff = t2.getEffectivePriority() - t1.getEffectivePriority()
            if (priorityDiff != 0) return priorityDiff

            // 优先级相同时，按创建时间排序（早创建的在前）
            return (t1.createdAt - t2.createdAt).toInt()
        }
    }

    /**
     * 添加任务
     */
    fun addTask(task: Task): Boolean {
        // 检查是否已过期
        if (task.isExpired()) {
            logger.warn("TaskQueue", "Task ${task.taskId} is already expired, discarding")
            markTaskResult(task, TaskStatus.EXPIRED, "TASK_EXPIRED", "Task expired before execution")
            return false
        }

        // 检查是否重复任务
        if (queue.any { it.taskId == task.taskId }) {
            logger.warn("TaskQueue", "Task ${task.taskId} already exists in queue")
            return false
        }

        // 特殊处理：如果是STOP_SCRIPT，清除所有START_SCRIPT任务
        if (task.type == TaskType.STOP_SCRIPT) {
            clearTasksByType(TaskType.START_SCRIPT)
        }

        val added = queue.offer(task)
        if (added) {
            logger.info("TaskQueue", "Task added: ${task.taskId} (${task.type})")
        } else {
            logger.error("TaskQueue", "Failed to add task: ${task.taskId}")
        }

        return added
    }

    /**
     * 批量添加任务
     */
    fun addTasks(tasks: List<Task>) {
        tasks.forEach { addTask(it) }
    }

    /**
     * 获取下一个任务
     */
    fun getNextTask(): Task? {
        // 清理过期任务
        cleanExpiredTasks()

        // 获取队列头部任务
        var task = queue.poll()

        // 跳过已取消的任务
        while (task != null && task.status == TaskStatus.CANCELLED) {
            logger.debug("TaskQueue", "Skipping cancelled task: ${task.taskId}")
            task = queue.poll()
        }

        // 检查任务是否过期
        if (task?.isExpired() == true) {
            logger.warn("TaskQueue", "Task ${task.taskId} expired, marking as expired")
            markTaskResult(task, TaskStatus.EXPIRED, "TASK_EXPIRED", "Task expired before execution")
            return getNextTask() // 递归获取下一个
        }

        // 标记任务为执行中
        task?.let {
            it.status = TaskStatus.EXECUTING
            logger.info("TaskQueue", "Task dequeued for execution: ${it.taskId} (${it.type})")
        }

        return task
    }

    /**
     * 查看队列头部任务（不移除）
     */
    fun peekNextTask(): Task? {
        cleanExpiredTasks()
        return queue.peek()
    }

    /**
     * 将任务重新入队（重试）
     */
    fun retryTask(task: Task, delay: Long = 0): Boolean {
        if (!task.canRetry()) {
            logger.error("TaskQueue", "Task ${task.taskId} exceeded max retries (${task.maxRetries})")
            markTaskResult(task, TaskStatus.FAILED, "MAX_RETRIES_EXCEEDED",
                "Maximum retry attempts exceeded")
            return false
        }

        task.retryCount++
        task.status = TaskStatus.PENDING

        if (delay > 0) {
            // 延迟重新入队
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                addTask(task)
            }, delay)
        } else {
            addTask(task)
        }

        logger.info("TaskQueue", "Task ${task.taskId} queued for retry (attempt ${task.retryCount}/${task.maxRetries})")
        return true
    }

    /**
     * 标记任务结果
     */
    fun markTaskResult(
        task: Task,
        status: TaskStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        resultData: Map<String, Any>? = null,
        durationMs: Long = 0
    ) {
        val result = TaskResult(
            taskId = task.taskId,
            commandId = task.commandId,
            status = status,
            errorCode = errorCode,
            errorMessage = errorMessage,
            resultData = resultData,
            durationMs = durationMs
        )

        completedTasks[task.taskId] = result

        // 限制缓存大小
        if (completedTasks.size > 100) {
            // 删除最老的结果
            val oldestKey = completedTasks.keys.minByOrNull {
                completedTasks[it]?.executedAt ?: Long.MAX_VALUE
            }
            oldestKey?.let { completedTasks.remove(it) }
        }

        logger.info("TaskQueue", "Task ${task.taskId} marked as $status" +
                if (errorCode != null) " with error: $errorCode" else "")
    }

    /**
     * 获取已完成任务的结果
     */
    fun getTaskResults(): List<TaskResult> {
        return completedTasks.values.toList()
    }

    /**
     * 获取并清空已完成任务的结果
     */
    fun consumeTaskResults(): List<TaskResult> {
        val results = completedTasks.values.toList()
        completedTasks.clear()
        return results
    }

    /**
     * 取消任务
     */
    fun cancelTask(taskId: String): Boolean {
        val task = queue.find { it.taskId == taskId }
        return if (task != null) {
            task.status = TaskStatus.CANCELLED
            queue.remove(task)
            logger.info("TaskQueue", "Task $taskId cancelled")
            true
        } else {
            false
        }
    }

    /**
     * 清除指定类型的任务
     */
    fun clearTasksByType(type: TaskType) {
        val removed = queue.removeAll { it.type == type }
        if (removed) {
            logger.info("TaskQueue", "Cleared all tasks of type $type")
        }
    }

    /**
     * 清理过期任务
     */
    private fun cleanExpiredTasks() {
        val expired = queue.filter { it.isExpired() }
        expired.forEach { task ->
            queue.remove(task)
            markTaskResult(task, TaskStatus.EXPIRED, "TASK_EXPIRED", "Task expired in queue")
        }

        if (expired.isNotEmpty()) {
            logger.info("TaskQueue", "Cleaned ${expired.size} expired tasks")
        }
    }

    /**
     * 清空队列
     */
    fun clear() {
        queue.clear()
        completedTasks.clear()
        logger.warn("TaskQueue", "Task queue cleared")
    }

    /**
     * 获取队列状态
     */
    fun getQueueStatus(): QueueStatus {
        return QueueStatus(
            pendingCount = queue.size,
            completedCount = completedTasks.size,
            nextTask = peekNextTask()
        )
    }

    data class QueueStatus(
        val pendingCount: Int,
        val completedCount: Int,
        val nextTask: Task?
    )

    /**
     * 生成任务ID
     */
    fun generateTaskId(): String {
        return "task_${taskIdGenerator.incrementAndGet()}_${System.currentTimeMillis()}"
    }

    /**
     * 从服务器命令创建任务
     */
    fun createTaskFromCommand(
        commandId: String,
        type: String,
        payload: Map<String, Any>,
        expiresAt: Long? = null
    ): Task? {
        val taskType = try {
            TaskType.valueOf(type.uppercase())
        } catch (e: Exception) {
            logger.error("TaskQueue", "Unknown task type: $type")
            return null
        }

        return Task(
            taskId = generateTaskId(),
            commandId = commandId,
            type = taskType,
            payload = payload,
            expiresAt = expiresAt ?: (System.currentTimeMillis() + 3600000),
            source = "server"
        )
    }
}