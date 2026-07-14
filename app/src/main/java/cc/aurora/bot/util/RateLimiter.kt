package cc.aurora.bot.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 通用速率限制器。
 *
 * 功能：
 * - 支持按 key（如 wxId、IP）进行速率限制
 * - 使用滑动窗口算法，精确计算时间窗口内的请求数
 * - 线程安全（使用 ConcurrentHashMap + synchronized 细粒度锁）
 * - 支持多种速率限制策略（AI 调用、HTTP 请求等）
 *
 * 使用方式：
 * ```
 * // 创建 AI 调用速率限制器（每用户每分钟 10 次）
 * val aiLimiter = RateLimiter(maxRequests = 10, windowMs = 60_000)
 * if (aiLimiter.tryAcquire("wxid_xxx")) {
 *     callAI(...)
 * } else {
 *     sendReply("请求过于频繁，请稍后再试")
 * }
 *
 * // 创建 HTTP 速率限制器（每 IP 每分钟 60 次）
 * val httpLimiter = RateLimiter(maxRequests = 60, windowMs = 60_000)
 * ```
 *
 * @param maxRequests 时间窗口内允许的最大请求数
 * @param windowMs 时间窗口大小（毫秒），默认 60 秒
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long = TimeUnit.MINUTES.toMillis(1)
) {

    companion object {
        /** AI 调用默认限制：每用户每分钟 10 次 */
        const val DEFAULT_AI_MAX_REQUESTS = 10
        /** HTTP 请求默认限制：每 IP 每分钟 60 次 */
        const val DEFAULT_HTTP_MAX_REQUESTS = 60
        /** 默认时间窗口：1 分钟 */
        const val DEFAULT_WINDOW_MS: Long = 60_000L

        /**
         * 创建 AI 调用速率限制器（默认配置：每用户每分钟 10 次）。
         */
        @JvmStatic
        fun forAiCalls(): RateLimiter {
            return RateLimiter(DEFAULT_AI_MAX_REQUESTS, DEFAULT_WINDOW_MS)
        }

        /**
         * 创建 HTTP 请求速率限制器（默认配置：每 IP 每分钟 60 次）。
         */
        @JvmStatic
        fun forHttpRequests(): RateLimiter {
            return RateLimiter(DEFAULT_HTTP_MAX_REQUESTS, DEFAULT_WINDOW_MS)
        }
    }

    /**
     * 速率限制记录条目：存储时间戳列表。
     */
    private data class RateLimitEntry(
        val timestamps: MutableList<Long> = mutableListOf()
    )

    /**
     * 速率限制记录：key -> 时间戳列表
     */
    private val rateLimitMap = ConcurrentHashMap<String, RateLimitEntry>()

    /**
     * 尝试获取一个许可。
     *
     * 在时间窗口内，如果该 key 的请求数未超过上限，则记录此次请求并返回 true；
     * 否则返回 false。
     *
     * @param key 速率限制的键（如 wxId、IP 地址）
     * @return true 如果请求被允许，false 如果已超过速率限制
     */
    fun tryAcquire(key: String): Boolean {
        if (key.isBlank()) return false

        val now = System.currentTimeMillis()
        val entry = rateLimitMap.getOrPut(key) { RateLimitEntry() }

        synchronized(entry) {
            // 清理过期的时间戳（滑动窗口）
            entry.timestamps.removeAll { now - it > windowMs }

            // 检查是否超过限制
            if (entry.timestamps.size >= maxRequests) {
                return false
            }

            // 记录当前请求
            entry.timestamps.add(now)
            return true
        }
    }

    /**
     * 获取指定 key 在当前窗口内的请求数。
     *
     * @param key 速率限制的键
     * @return 当前窗口内的请求数
     */
    fun getCurrentCount(key: String): Int {
        val entry = rateLimitMap[key] ?: return 0
        val now = System.currentTimeMillis()
        synchronized(entry) {
            entry.timestamps.removeAll { now - it > windowMs }
            return entry.timestamps.size
        }
    }

    /**
     * 获取指定 key 的剩余可用请求数。
     *
     * @param key 速率限制的键
     * @return 当前窗口内剩余可用的请求数，最小为 0
     */
    fun getRemainingCount(key: String): Int {
        val current = getCurrentCount(key)
        return maxOf(0, maxRequests - current)
    }

    /**
     * 重置指定 key 的速率限制记录。
     *
     * @param key 要重置的键
     */
    fun reset(key: String) {
        rateLimitMap.remove(key)
    }

    /**
     * 重置所有速率限制记录。
     */
    fun resetAll() {
        rateLimitMap.clear()
    }

    /**
     * 获取当前被限制的 key 数量。
     *
     * @return 速率限制记录总数
     */
    fun getTrackedKeyCount(): Int {
        return rateLimitMap.size
    }

    /**
     * 获取速率限制配置信息。
     *
     * @return 包含 maxRequests、windowMs 等配置的映射
     */
    fun getConfig(): Map<String, Any> {
        return mapOf(
            "maxRequests" to maxRequests,
            "windowMs" to windowMs,
            "windowSeconds" to windowMs / 1000.0
        )
    }
}