package cc.aurora.bot.service.scheduler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 定时消息调度管理器。
 *
 * 功能：
 * - 一次性延迟消息：在指定毫秒后发送一条消息
 * - 周期性消息：按 cron 表达式（简化版）或固定间隔发送消息
 * - 取消、查询已调度的消息
 *
 * 使用方式：
 * ```
 * val scheduler = SchedulerManager
 * scheduler.onMessageReady = { wxId, message -> sendWxMessage(wxId, message) }
 * val id = scheduler.scheduleMessage("wxid_xxx", "你好，这是一条定时消息", 5000)
 * scheduler.cancelSchedule(id)
 * ```
 */
object SchedulerManager {

    /**
     * 定时任务信息。
     *
     * @property id 唯一标识
     * @property wxId 目标微信 ID
     * @property message 要发送的消息内容
     * @property scheduledAtMs 调度创建时间（毫秒时间戳）
     * @property triggerAtMs 触发时间（毫秒时间戳），0 表示周期性任务
     * @property recurring 是否为周期性任务
     * @property intervalMs 周期性任务的间隔（毫秒），0 表示非周期性
     */
    data class ScheduleInfo(
        val id: Long,
        val wxId: String,
        val message: String,
        val scheduledAtMs: Long,
        val triggerAtMs: Long,
        val recurring: Boolean,
        val intervalMs: Long
    )

    /** 线程池大小 */
    private const val THREAD_POOL_SIZE = 4

    /** 调度器 ID 自增计数器 */
    private val idCounter = AtomicLong(0)

    /** 线程池：用于执行定时任务 */
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(THREAD_POOL_SIZE)

    /** 存储所有活跃的调度任务：scheduleId -> (ScheduledFuture, ScheduleInfo) */
    private val scheduledTasks = ConcurrentHashMap<Long, Pair<ScheduledFuture<*>, ScheduleInfo>>()

    /**
     * 消息就绪回调：当定时任务触发时调用。
     * 由 WeChatHooker 在初始化时设置。
     *
     * @param wxId 目标微信 ID
     * @param message 要发送的消息内容
     */
    var onMessageReady: ((wxId: String, message: String) -> Unit)? = null

    /**
     * 调度一条一次性延迟消息。
     *
     * @param wxId 目标微信 ID
     * @param message 要发送的消息内容（最长 500 字符，超出部分会被截断）
     * @param delayMs 延迟毫秒数，最小 1000ms（1 秒）
     * @return 调度 ID，用于后续取消操作；如果 wxId 或 message 为空则返回 -1
     */
    @Synchronized
    fun scheduleMessage(wxId: String, message: String, delayMs: Long): Long {
        if (wxId.isBlank() || message.isBlank()) return -1

        val actualDelay = maxOf(delayMs, 1000L) // 最小延迟 1 秒
        val id = idCounter.incrementAndGet()
        val now = System.currentTimeMillis()
        val truncatedMsg = if (message.length > 500) message.take(500) + "..." else message

        val info = ScheduleInfo(
            id = id,
            wxId = wxId,
            message = truncatedMsg,
            scheduledAtMs = now,
            triggerAtMs = now + actualDelay,
            recurring = false,
            intervalMs = 0
        )

        val future = executor.schedule({
            try {
                onMessageReady?.invoke(wxId, truncatedMsg)
            } finally {
                // 一次性任务完成后自动移除
                scheduledTasks.remove(id)
            }
        }, actualDelay, TimeUnit.MILLISECONDS)

        scheduledTasks[id] = Pair(future, info)
        return id
    }

    /**
     * 调度一条周期性消息。
     *
     * 注意：当前实现使用简化版 cron，仅支持固定间隔（毫秒）的周期性任务。
     * 对于 "0 8 * * *" 这类标准 cron 表达式，会被解析为每天固定时间执行。
     * 支持的 cron 格式：`minute hour * * *`（5 字段标准 cron，仅 day-of-month 和 month 为 * 时有效）。
     *
     * @param wxId 目标微信 ID
     * @param message 要发送的消息内容（最长 500 字符）
     * @param cronExpression cron 表达式，支持 "0 8 * * *" 格式或纯数字毫秒数
     * @return 调度 ID，用于后续取消；如果参数无效则返回 -1
     */
    @Synchronized
    fun scheduleRecurring(wxId: String, message: String, cronExpression: String): Long {
        if (wxId.isBlank() || message.isBlank() || cronExpression.isBlank()) return -1

        val truncatedMsg = if (message.length > 500) message.take(500) + "..." else message

        // 尝试解析 cron 表达式
        val intervalMs = parseCronToInterval(cronExpression)
        if (intervalMs <= 0) return -1

        val id = idCounter.incrementAndGet()
        val now = System.currentTimeMillis()

        val info = ScheduleInfo(
            id = id,
            wxId = wxId,
            message = truncatedMsg,
            scheduledAtMs = now,
            triggerAtMs = 0, // 周期性任务无固定触发时间
            recurring = true,
            intervalMs = intervalMs
        )

        val future = executor.scheduleAtFixedRate({
            try {
                onMessageReady?.invoke(wxId, truncatedMsg)
            } catch (_: Exception) {
                // 忽略发送失败，避免中断周期性任务
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)

        scheduledTasks[id] = Pair(future, info)
        return id
    }

    /**
     * 取消指定的调度任务。
     *
     * @param scheduleId 由 [scheduleMessage] 或 [scheduleRecurring] 返回的调度 ID
     * @return true 如果任务存在并被成功取消，false 如果任务不存在
     */
    fun cancelSchedule(scheduleId: Long): Boolean {
        val entry = scheduledTasks.remove(scheduleId) ?: return false
        val (future, _) = entry
        future.cancel(false) // 不中断正在执行的任务
        return true
    }

    /**
     * 获取所有活跃的调度任务列表。
     *
     * @return 活跃调度任务的 ScheduleInfo 列表，按调度时间升序排列
     */
    fun listSchedules(): List<ScheduleInfo> {
        return scheduledTasks.values
            .map { it.second }
            .sortedBy { it.triggerAtMs }
    }

    /**
     * 获取指定调度任务的详细信息。
     *
     * @param scheduleId 调度 ID
     * @return 任务信息，如果不存在则返回 null
     */
    fun getSchedule(scheduleId: Long): ScheduleInfo? {
        return scheduledTasks[scheduleId]?.second
    }

    /**
     * 获取活跃调度任务数量。
     *
     * @return 当前活跃的调度任务总数
     */
    fun getScheduleCount(): Int {
        return scheduledTasks.size
    }

    /**
     * 取消所有调度任务并关闭线程池。
     * 应在系统关闭时调用。
     */
    fun shutdown() {
        for ((_, entry) in scheduledTasks) {
            entry.first.cancel(false)
        }
        scheduledTasks.clear()
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
        }
    }

    /**
     * 将简化版 cron 表达式解析为时间间隔（毫秒）。
     *
     * 支持的格式：
     * 1. 纯数字字符串（如 "3600000"）-> 直接作为毫秒间隔
     * 2. 标准 5 字段 cron: "minute hour * * *" -> 计算距现在最近的执行时间
     * 3. 带秒数的 cron: "second minute hour * * *" -> 6 字段 cron
     *
     * @param cronExpression cron 表达式字符串
     * @return 毫秒间隔，如果解析失败则返回 -1
     */
    private fun parseCronToInterval(cronExpression: String): Long {
        val trimmed = cronExpression.trim()

        // 尝试解析为纯数字（毫秒）
        val numericMs = trimmed.toLongOrNull()
        if (numericMs != null && numericMs >= 1000) {
            return numericMs
        }

        // 解析标准 cron 表达式
        val parts = trimmed.split("\\s+".toRegex())
        if (parts.size !in 5..6) return -1

        try {
            val minute: Int
            val hour: Int

            if (parts.size == 6) {
                // 6 字段: second minute hour dayOfMonth month dayOfWeek
                minute = parts[1].toInt()
                hour = parts[2].toInt()
            } else {
                // 5 字段: minute hour dayOfMonth month dayOfWeek
                minute = parts[0].toInt()
                hour = parts[1].toInt()
            }

            // 验证取值范围
            if (minute < 0 || minute > 59 || hour < 0 || hour > 23) return -1

            // 计算距今最近的执行时间
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now

            // 设置目标时间
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)

            val targetHour = hour
            // 如果当前时间已过今天的执行时间，则推迟到明天
            if (cal.get(java.util.Calendar.HOUR_OF_DAY) > targetHour ||
                (cal.get(java.util.Calendar.HOUR_OF_DAY) == targetHour &&
                 cal.get(java.util.Calendar.MINUTE) >= minute && cal.get(java.util.Calendar.SECOND) > 0)) {
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, targetHour)

            val delayMs = cal.timeInMillis - now
            return maxOf(delayMs, 60000L) // 至少 1 分钟
        } catch (_: NumberFormatException) {
            return -1
        }
    }
}