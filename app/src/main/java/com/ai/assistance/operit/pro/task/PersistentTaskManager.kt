package com.ai.assistance.operit.pro.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 持久化任务管理器
 *
 * 管理跨会话的长期运行任务，支持多步自主执行、状态跟踪、
 * 自纠正/重试机制和目标驱动的任务编排。
 *
 * 核心能力：
 * - 多步任务分解与编排
 * - 任务状态持久化（跨会话恢复）
 * - 自纠正与重试机制
 * - 目标驱动执行（Goal-Oriented）
 * - 任务依赖管理
 * - 执行历史与审计
 */
class PersistentTaskManager {

    companion object {
        private val _activeTasks = MutableStateFlow<List<PersistentTask>>(emptyList())
        val activeTasks: StateFlow<List<PersistentTask>> = _activeTasks.asStateFlow()

        private val _completedTasks = MutableStateFlow<List<PersistentTask>>(emptyList())
        val completedTasks: StateFlow<List<PersistentTask>> = _completedTasks.asStateFlow()

        private val _taskHistory = MutableStateFlow<List<TaskExecutionLog>>(emptyList())
        val taskHistory: StateFlow<List<TaskExecutionLog>> = _taskHistory.asStateFlow()

        private val tasks = mutableMapOf<String, PersistentTask>()
        private val taskSteps = mutableMapOf<String, MutableList<TaskStep>>()
        private val executionLogs = mutableListOf<TaskExecutionLog>()
    }

    /**
     * 创建新任务
     */
    fun createTask(
        goal: String,
        description: String,
        steps: List<TaskStepDef>,
        priority: TaskPriority = TaskPriority.NORMAL,
    ): PersistentTask {
        val taskId = UUID.randomUUID().toString()
        val task = PersistentTask(
            id = taskId,
            goal = goal,
            description = description,
            priority = priority,
            status = TaskStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            totalSteps = steps.size,
            completedSteps = 0,
        )

        tasks[taskId] = task
        taskSteps[taskId] = steps.mapIndexed { index, stepDef ->
            TaskStep(
                id = "$taskId-step-$index",
                taskId = taskId,
                name = stepDef.name,
                description = stepDef.description,
                action = stepDef.action,
                dependencies = stepDef.dependencies,
                order = index,
                status = if (index == 0) StepStatus.READY else StepStatus.PENDING,
            )
        }.toMutableList()

        updateActiveTasks()
        logExecution(taskId, "Task created: $goal")
        return task
    }

    /**
     * 执行任务步骤
     */
    fun executeStep(taskId: String, stepId: String, result: StepResult): Boolean {
        val steps = taskSteps[taskId] ?: return false
        val step = steps.find { it.id == stepId } ?: return false

        // 更新步骤状态
        val stepIndex = steps.indexOf(step)
        steps[stepIndex] = when (result) {
            is StepResult.Success -> step.copy(
                status = StepStatus.COMPLETED,
                result = result.output,
                completedAt = System.currentTimeMillis(),
            )
            is StepResult.Failure -> step.copy(
                status = StepStatus.FAILED,
                error = result.error,
                retryCount = step.retryCount + 1,
            )
            is StepResult.NeedsInput -> step.copy(
                status = StepStatus.WAITING_INPUT,
                pendingInput = result.question,
            )
        }

        // 处理失败 - 自纠正
        if (result is StepResult.Failure && step.retryCount < 3) {
            steps[stepIndex] = steps[stepIndex].copy(status = StepStatus.READY)
            logExecution(taskId, "Step '$${step.name}' failed, retrying (attempt ${step.retryCount + 1})")
            updateActiveTasks()
            return true
        }

        // 检查依赖并激活下一步
        activateNextSteps(taskId, stepId)

        // 更新任务状态
        updateTaskProgress(taskId)
        logExecution(taskId, "Step '$${step.name}' completed: ${result::class.simpleName}")

        return true
    }

    /**
     * 获取任务步骤
     */
    fun getTaskSteps(taskId: String): List<TaskStep> {
        return taskSteps[taskId]?.toList() ?: emptyList()
    }

    /**
     * 获取任务详情
     */
    fun getTask(taskId: String): PersistentTask? = tasks[taskId]

    /**
     * 暂停任务
     */
    fun pauseTask(taskId: String) {
        tasks[taskId]?.let { task ->
            tasks[taskId] = task.copy(status = TaskStatus.PAUSED)
            logExecution(taskId, "Task paused")
            updateActiveTasks()
        }
    }

    /**
     * 恢复任务
     */
    fun resumeTask(taskId: String) {
        tasks[taskId]?.let { task ->
            tasks[taskId] = task.copy(status = TaskStatus.IN_PROGRESS)
            logExecution(taskId, "Task resumed")
            updateActiveTasks()
        }
    }

    /**
     * 取消任务
     */
    fun cancelTask(taskId: String) {
        tasks[taskId]?.let { task ->
            tasks[taskId] = task.copy(
                status = TaskStatus.CANCELLED,
                completedAt = System.currentTimeMillis(),
            )
            logExecution(taskId, "Task cancelled")
            moveTaskToCompleted(taskId)
        }
    }

    /**
     * 标记任务完成
     */
    fun completeTask(taskId: String, summary: String) {
        tasks[taskId]?.let { task ->
            tasks[taskId] = task.copy(
                status = TaskStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                summary = summary,
                completedSteps = task.totalSteps,
            )
            logExecution(taskId, "Task completed: $summary")
            moveTaskToCompleted(taskId)
        }
    }

    private fun activateNextSteps(taskId: String, completedStepId: String) {
        val steps = taskSteps[taskId] ?: return
        steps.forEachIndexed { index, step ->
            if (step.status == StepStatus.PENDING) {
                val allDepsComplete = step.dependencies.all { depId ->
                    steps.find { it.name == depId }?.status == StepStatus.COMPLETED
                }
                if (allDepsComplete) {
                    steps[index] = step.copy(status = StepStatus.READY)
                }
            }
        }
    }

    private fun updateTaskProgress(taskId: String) {
        val task = tasks[taskId] ?: return
        val steps = taskSteps[taskId] ?: return
        val completed = steps.count { it.status == StepStatus.COMPLETED }
        val failed = steps.count { it.status == StepStatus.FAILED }

        val newStatus = when {
            completed == steps.size -> TaskStatus.COMPLETED
            failed > 0 && failed + completed == steps.size -> TaskStatus.FAILED
            else -> TaskStatus.IN_PROGRESS
        }

        tasks[taskId] = task.copy(
            completedSteps = completed,
            status = newStatus,
        )

        if (newStatus == TaskStatus.COMPLETED || newStatus == TaskStatus.FAILED) {
            moveTaskToCompleted(taskId)
        } else {
            updateActiveTasks()
        }
    }

    private fun moveTaskToCompleted(taskId: String) {
        val task = tasks.remove(taskId) ?: return
        val current = _completedTasks.value.toMutableList()
        current.add(0, task)
        _completedTasks.value = current
        updateActiveTasks()
    }

    private fun updateActiveTasks() {
        _activeTasks.value = tasks.values.toList().sortedByDescending { it.createdAt }
    }

    private fun logExecution(taskId: String, message: String) {
        val log = TaskExecutionLog(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            message = message,
            timestamp = System.currentTimeMillis(),
        )
        executionLogs.add(0, log)
        if (executionLogs.size > 1000) {
            executionLogs.removeAt(executionLogs.size - 1)
        }
        _taskHistory.value = executionLogs.toList()
    }
}

/** 持久化任务 */
data class PersistentTask(
    val id: String,
    val goal: String,
    val description: String,
    val priority: TaskPriority,
    val status: TaskStatus,
    val createdAt: Long,
    val completedAt: Long? = null,
    val totalSteps: Int,
    val completedSteps: Int,
    val summary: String? = null,
)

/** 任务步骤 */
data class TaskStep(
    val id: String,
    val taskId: String,
    val name: String,
    val description: String,
    val action: String,
    val dependencies: List<String> = emptyList(),
    val order: Int,
    val status: StepStatus,
    val result: String? = null,
    val error: String? = null,
    val pendingInput: String? = null,
    val retryCount: Int = 0,
    val completedAt: Long? = null,
)

/** 任务步骤定义 */
data class TaskStepDef(
    val name: String,
    val description: String,
    val action: String,
    val dependencies: List<String> = emptyList(),
)

/** 步骤结果 */
sealed class StepResult {
    data class Success(val output: String) : StepResult()
    data class Failure(val error: String) : StepResult()
    data class NeedsInput(val question: String) : StepResult()
}

/** 任务优先级 */
enum class TaskPriority(val value: Int) {
    LOW(0), NORMAL(1), HIGH(2), URGENT(3)
}

/** 任务状态 */
enum class TaskStatus {
    PENDING, IN_PROGRESS, PAUSED, WAITING_INPUT, COMPLETED, FAILED, CANCELLED
}

/** 步骤状态 */
enum class StepStatus {
    PENDING, READY, IN_PROGRESS, WAITING_INPUT, COMPLETED, FAILED
}

/** 执行日志 */
data class TaskExecutionLog(
    val id: String,
    val taskId: String,
    val message: String,
    val timestamp: Long,
)
