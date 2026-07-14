package cc.aurora.bot.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能指标收集器，用于跟踪关键操作的延迟和计数。
 *
 * 使用 AtomicLong 和 System.nanoTime() 实现无锁、高精度的追踪。
 * 线程安全，可在多线程环境中使用。
 *
 * 追踪指标：
 * - AI API 调用延迟（总耗时、调用次数）
 * - 消息处理时间（从接收到回复完成的总耗时）
 * - 指令执行时间（单个指令的执行耗时）
 * - HTTP 请求延迟（HTTPServer 请求处理耗时）
 */
object MetricsCollector {

    // ===================== AI API 调用指标 =====================

    /** AI API 调用总耗时（纳秒） */
    private val aiCallTotalLatencyNs = AtomicLong(0L)

    /** AI API 调用次数 */
    private val aiCallCount = AtomicLong(0L)

    /** AI API 调用最大耗时（纳秒） */
    private val aiCallMaxLatencyNs = AtomicLong(0L)

    /** AI API 调用最小耗时（纳秒），初始化为最大值 */
    private val aiCallMinLatencyNs = AtomicLong(Long.MAX_VALUE)

    // ===================== 消息处理指标 =====================

    /** 消息处理总耗时（纳秒） */
    private val messageProcessingTotalLatencyNs = AtomicLong(0L)

    /** 消息处理次数 */
    private val messageProcessingCount = AtomicLong(0L)

    /** 消息处理最大耗时（纳秒） */
    private val messageProcessingMaxLatencyNs = AtomicLong(0L)

    /** 消息处理最小耗时（纳秒） */
    private val messageProcessingMinLatencyNs = AtomicLong(Long.MAX_VALUE)

    // ===================== 指令执行指标 =====================

    /** 指令执行总耗时（纳秒） */
    private val commandExecutionTotalLatencyNs = AtomicLong(0L)

    /** 指令执行次数 */
    private val commandExecutionCount = AtomicLong(0L)

    /** 指令执行最大耗时（纳秒） */
    private val commandExecutionMaxLatencyNs = AtomicLong(0L)

    /** 指令执行最小耗时（纳秒） */
    private val commandExecutionMinLatencyNs = AtomicLong(Long.MAX_VALUE)

    // ===================== HTTP 请求指标 =====================

    /** HTTP 请求总耗时（纳秒） */
    private val httpRequestTotalLatencyNs = AtomicLong(0L)

    /** HTTP 请求次数 */
    private val httpRequestCount = AtomicLong(0L)

    /** HTTP 请求最大耗时（纳秒） */
    private val httpRequestMaxLatencyNs = AtomicLong(0L)

    /** HTTP 请求最小耗时（纳秒） */
    private val httpRequestMinLatencyNs = AtomicLong(Long.MAX_VALUE)

    // ===================== 各端点累积耗时（按 URI 分类） =====================

    /** 按 URI 分类的 HTTP 请求耗时追踪：uri -> (totalLatencyNs, count, maxNs, minNs) */
    private val httpEndpointMetrics = ConcurrentHashMap<String, EndpointMetrics>()

    /**
     * 单个端点的累计指标
     */
    private class EndpointMetrics {
        val totalLatencyNs = AtomicLong(0L)
        val count = AtomicLong(0L)
        val maxLatencyNs = AtomicLong(0L)
        val minLatencyNs = AtomicLong(Long.MAX_VALUE)
    }

    // ===================== 记录方法 =====================

    /**
     * 记录一次 AI API 调用的耗时。
     *
     * @param latencyNs 耗时（纳秒），通过 System.nanoTime() 差值计算
     */
    fun recordAiCallLatency(latencyNs: Long) {
        aiCallTotalLatencyNs.addAndGet(latencyNs)
        aiCallCount.incrementAndGet()
        updateMax(aiCallMaxLatencyNs, latencyNs)
        updateMin(aiCallMinLatencyNs, latencyNs)
    }

    /**
     * 记录一次消息处理的耗时。
     *
     * @param latencyNs 耗时（纳秒）
     */
    fun recordMessageProcessingLatency(latencyNs: Long) {
        messageProcessingTotalLatencyNs.addAndGet(latencyNs)
        messageProcessingCount.incrementAndGet()
        updateMax(messageProcessingMaxLatencyNs, latencyNs)
        updateMin(messageProcessingMinLatencyNs, latencyNs)
    }

    /**
     * 记录一次指令执行的耗时。
     *
     * @param latencyNs 耗时（纳秒）
     */
    fun recordCommandExecutionLatency(latencyNs: Long) {
        commandExecutionTotalLatencyNs.addAndGet(latencyNs)
        commandExecutionCount.incrementAndGet()
        updateMax(commandExecutionMaxLatencyNs, latencyNs)
        updateMin(commandExecutionMinLatencyNs, latencyNs)
    }

    /**
     * 记录一次 HTTP 请求的耗时。
     *
     * @param latencyNs 耗时（纳秒）
     * @param uri 请求的 URI，用于按端点分类统计
     */
    fun recordHttpRequestLatency(latencyNs: Long, uri: String = "") {
        httpRequestTotalLatencyNs.addAndGet(latencyNs)
        httpRequestCount.incrementAndGet()
        updateMax(httpRequestMaxLatencyNs, latencyNs)
        updateMin(httpRequestMinLatencyNs, latencyNs)

        // 按端点分类统计
        if (uri.isNotEmpty()) {
            val metrics = httpEndpointMetrics.getOrPut(uri) { EndpointMetrics() }
            metrics.totalLatencyNs.addAndGet(latencyNs)
            metrics.count.incrementAndGet()
            updateMax(metrics.maxLatencyNs, latencyNs)
            updateMin(metrics.minLatencyNs, latencyNs)
        }
    }

    // ===================== 便捷方法：计时包装器 =====================

    /**
     * 对 AI API 调用进行计时。
     * 用法：
     * ```
     * val result = MetricsCollector.timeAiCall {
     *     AiService.chat(...)
     * }
     * ```
     *
     * @param block 要执行的 AI 调用代码块
     * @return 代码块的返回值
     */
    inline fun <T> timeAiCall(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            recordAiCallLatency(System.nanoTime() - start)
        }
    }

    /**
     * 对消息处理进行计时。
     *
     * @param block 要执行的消息处理代码块
     * @return 代码块的返回值
     */
    inline fun <T> timeMessageProcessing(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            recordMessageProcessingLatency(System.nanoTime() - start)
        }
    }

    /**
     * 对指令执行进行计时。
     *
     * @param block 要执行的指令处理代码块
     * @return 代码块的返回值
     */
    inline fun <T> timeCommandExecution(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            recordCommandExecutionLatency(System.nanoTime() - start)
        }
    }

    /**
     * 对 HTTP 请求处理进行计时。
     *
     * @param uri 请求 URI
     * @param block 要执行的请求处理代码块
     * @return 代码块的返回值
     */
    inline fun <T> timeHttpRequest(uri: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            recordHttpRequestLatency(System.nanoTime() - start, uri)
        }
    }

    // ===================== 查询方法 =====================

    /**
     * 获取所有指标的汇总快照。
     *
     * @return 包含所有指标统计的映射
     */
    fun getSummary(): Map<String, Any> {
        return mapOf(
            "aiCalls" to mapOf(
                "count" to aiCallCount.get(),
                "totalLatencyMs" to nsToMs(aiCallTotalLatencyNs.get()),
                "avgLatencyMs" to avgMs(aiCallTotalLatencyNs.get(), aiCallCount.get()),
                "maxLatencyMs" to nsToMs(aiCallMaxLatencyNs.get()),
                "minLatencyMs" to minMs(aiCallMinLatencyNs.get())
            ),
            "messageProcessing" to mapOf(
                "count" to messageProcessingCount.get(),
                "totalLatencyMs" to nsToMs(messageProcessingTotalLatencyNs.get()),
                "avgLatencyMs" to avgMs(messageProcessingTotalLatencyNs.get(), messageProcessingCount.get()),
                "maxLatencyMs" to nsToMs(messageProcessingMaxLatencyNs.get()),
                "minLatencyMs" to minMs(messageProcessingMinLatencyNs.get())
            ),
            "commandExecution" to mapOf(
                "count" to commandExecutionCount.get(),
                "totalLatencyMs" to nsToMs(commandExecutionTotalLatencyNs.get()),
                "avgLatencyMs" to avgMs(commandExecutionTotalLatencyNs.get(), commandExecutionCount.get()),
                "maxLatencyMs" to nsToMs(commandExecutionMaxLatencyNs.get()),
                "minLatencyMs" to minMs(commandExecutionMinLatencyNs.get())
            ),
            "httpRequests" to mapOf(
                "count" to httpRequestCount.get(),
                "totalLatencyMs" to nsToMs(httpRequestTotalLatencyNs.get()),
                "avgLatencyMs" to avgMs(httpRequestTotalLatencyNs.get(), httpRequestCount.get()),
                "maxLatencyMs" to nsToMs(httpRequestMaxLatencyNs.get()),
                "minLatencyMs" to minMs(httpRequestMinLatencyNs.get())
            ),
            "httpEndpoints" to getEndpointSummaries()
        )
    }

    /**
     * 获取按 URI 分类的 HTTP 端点指标汇总。
     */
    private fun getEndpointSummaries(): Map<String, Map<String, Any>> {
        val result = mutableMapOf<String, Map<String, Any>>()
        for ((uri, metrics) in httpEndpointMetrics) {
            result[uri] = mapOf(
                "count" to metrics.count.get(),
                "totalLatencyMs" to nsToMs(metrics.totalLatencyNs.get()),
                "avgLatencyMs" to avgMs(metrics.totalLatencyNs.get(), metrics.count.get()),
                "maxLatencyMs" to nsToMs(metrics.maxLatencyNs.get()),
                "minLatencyMs" to minMs(metrics.minLatencyNs.get())
            )
        }
        return result
    }

    /**
     * 重置所有指标。
     */
    fun reset() {
        aiCallTotalLatencyNs.set(0L)
        aiCallCount.set(0L)
        aiCallMaxLatencyNs.set(0L)
        aiCallMinLatencyNs.set(Long.MAX_VALUE)

        messageProcessingTotalLatencyNs.set(0L)
        messageProcessingCount.set(0L)
        messageProcessingMaxLatencyNs.set(0L)
        messageProcessingMinLatencyNs.set(Long.MAX_VALUE)

        commandExecutionTotalLatencyNs.set(0L)
        commandExecutionCount.set(0L)
        commandExecutionMaxLatencyNs.set(0L)
        commandExecutionMinLatencyNs.set(Long.MAX_VALUE)

        httpRequestTotalLatencyNs.set(0L)
        httpRequestCount.set(0L)
        httpRequestMaxLatencyNs.set(0L)
        httpRequestMinLatencyNs.set(Long.MAX_VALUE)

        httpEndpointMetrics.clear()
    }

    // ===================== 内部辅助方法 =====================

    /**
     * 更新最大值（AtomicLong CAS 循环）
     */
    private fun updateMax(holder: AtomicLong, newValue: Long) {
        var current = holder.get()
        while (newValue > current && !holder.compareAndSet(current, newValue)) {
            current = holder.get()
        }
    }

    /**
     * 更新最小值（AtomicLong CAS 循环）
     */
    private fun updateMin(holder: AtomicLong, newValue: Long) {
        var current = holder.get()
        while (newValue < current && !holder.compareAndSet(current, newValue)) {
            current = holder.get()
        }
    }

    /**
     * 纳秒转毫秒
     */
    private fun nsToMs(ns: Long): Double {
        return ns / 1_000_000.0
    }

    /**
     * 计算平均耗时（毫秒），避免除零
     */
    private fun avgMs(totalNs: Long, count: Long): Double {
        return if (count > 0) nsToMs(totalNs) / count else 0.0
    }

    /**
     * 最小耗时（毫秒），处理初始值 Long.MAX_VALUE 的情况
     */
    private fun minMs(minNs: Long): Double {
        return if (minNs == Long.MAX_VALUE) 0.0 else nsToMs(minNs)
    }
}