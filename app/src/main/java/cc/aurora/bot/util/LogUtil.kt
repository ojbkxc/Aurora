package cc.aurora.bot.util

import android.util.Log

/**
 * Aurora 统一日志工具类。
 *
 * 功能：
 * - 集中化的日志输出，支持多个日志级别
 * - 自动格式化 TAG 为 "Aurora/<ModuleName>" 格式
 * - 根据 ConfigManager 中的 KEY_LOG_LEVEL 设置最低日志级别
 * - 支持自定义模块名称自动附加
 *
 * 使用方式：
 * ```
 * val log = LogUtil.forModule("HttpServer")
 * log.d("Server started on port 5888")
 * log.e("Failed to start server", exception)
 * ```
 */
object LogUtil {

    /**
     * 日志级别枚举（与 android.util.Log 保持一致）
     */
    enum class Level(val value: Int) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
        NONE(Int.MAX_VALUE)
    }

    /** 全局 TAG 前缀 */
    private const val TAG_PREFIX = "Aurora"

    /** 当前全局最低日志级别，低于此级别的日志将被忽略 */
    @Volatile
    var globalLogLevel: Level = Level.DEBUG

    /**
     * 为指定模块创建一个模块日志记录器。
     *
     * @param moduleName 模块名称，将被格式化为 "Aurora/<ModuleName>"
     * @return 模块日志记录器实例
     */
    fun forModule(moduleName: String): ModuleLogger {
        return ModuleLogger("$TAG_PREFIX/$moduleName")
    }

    /**
     * 模块日志记录器，自动附加模块 TAG。
     */
    class ModuleLogger(private val tag: String) {

        /**
         * 检查指定级别是否应该输出
         */
        private fun isLoggable(level: Level): Boolean {
            return level.value >= globalLogLevel.value
        }

        /**
         * VERBOSE 级别日志
         */
        fun v(message: String) {
            if (isLoggable(Level.VERBOSE)) {
                Log.v(tag, message)
            }
        }

        /**
         * DEBUG 级别日志
         */
        fun d(message: String) {
            if (isLoggable(Level.DEBUG)) {
                Log.d(tag, message)
            }
        }

        /**
         * INFO 级别日志
         */
        fun i(message: String) {
            if (isLoggable(Level.INFO)) {
                Log.i(tag, message)
            }
        }

        /**
         * WARN 级别日志
         */
        fun w(message: String) {
            if (isLoggable(Level.WARN)) {
                Log.w(tag, message)
            }
        }

        /**
         * WARN 级别日志（带异常）
         */
        fun w(message: String, throwable: Throwable) {
            if (isLoggable(Level.WARN)) {
                Log.w(tag, message, throwable)
            }
        }

        /**
         * ERROR 级别日志
         */
        fun e(message: String) {
            if (isLoggable(Level.ERROR)) {
                Log.e(tag, message)
            }
        }

        /**
         * ERROR 级别日志（带异常）
         */
        fun e(message: String, throwable: Throwable) {
            if (isLoggable(Level.ERROR)) {
                Log.e(tag, message, throwable)
            }
        }

        /**
         * 获取当前 logger 的 TAG 字符串
         */
        fun getTag(): String = tag
    }

    /**
     * 根据 ConfigManager 的 logLevel 配置更新全局日志级别。
     *
     * @param levelName 日志级别名称（verbose, debug, info, warn, error, none）
     */
    fun updateLogLevel(levelName: String) {
        globalLogLevel = when (levelName.lowercase()) {
            "verbose" -> Level.VERBOSE
            "debug" -> Level.DEBUG
            "info" -> Level.INFO
            "warn" -> Level.WARN
            "error" -> Level.ERROR
            "none" -> Level.NONE
            else -> Level.DEBUG
        }
    }

    /**
     * 获取当前日志级别的字符串表示
     */
    fun getCurrentLogLevelName(): String {
        return globalLogLevel.name.lowercase()
    }
}