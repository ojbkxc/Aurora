package cc.aurora.bot.model

/**
 * 命令执行结果的密封类层次结构。
 *
 * 用于统一表示命令执行的各种结果状态，支持模式匹配。
 * 替代了原来分散的 String 返回值和 CommandResult 数据类，
 * 提供更类型安全的结果处理方式。
 *
 * 使用方式:
 * ```
 * val result = executeCommand(wxId, message)
 * when (result) {
 *     is OperationResult.Success -> sendReply(result.message)
 *     is OperationResult.Failure -> sendReply("错误: ${result.message}")
 *     is OperationResult.Restricted -> sendReply("权限不足")
 *     is OperationResult.ValidationError -> sendReply("格式错误: ${result.message}")
 * }
 * ```
 */
sealed class OperationResult {

    /**
     * 命令执行成功。
     *
     * @property message 成功消息，将发送给用户
     * @property data 可选的附加数据映射
     */
    data class Success(
        val message: String,
        val data: Map<String, Any> = emptyMap()
    ) : OperationResult()

    /**
     * 命令执行失败。
     *
     * @property message 错误消息，将发送给用户
     * @property cause 可选的原始异常
     * @property errorCode 可选的错误代码
     */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val errorCode: String = ""
    ) : OperationResult()

    /**
     * 权限不足（非开发者尝试执行管理命令）。
     */
    object Restricted : OperationResult() {
        /** 默认的权限不足提示消息 */
        const val DEFAULT_MESSAGE = "仅开发者可执行此操作"

        override fun toString(): String = DEFAULT_MESSAGE
    }

    /**
     * 输入验证失败（格式错误、参数缺失等）。
     *
     * @property message 验证错误消息，将发送给用户
     * @property expectedFormat 期望的输入格式说明
     */
    data class ValidationError(
        val message: String,
        val expectedFormat: String = ""
    ) : OperationResult()

    /**
     * 命令执行发生异常（非预期错误）。
     *
     * @property message 错误消息
     * @property exception 原始异常
     */
    data class ExceptionError(
        val message: String,
        val exception: Throwable
    ) : OperationResult()

    // ===================== 便捷工厂方法 =====================

    companion object {
        /**
         * 创建成功结果。
         */
        @JvmStatic
        fun success(message: String, data: Map<String, Any> = emptyMap()): OperationResult {
            return Success(message, data)
        }

        /**
         * 创建失败结果。
         */
        @JvmStatic
        fun failure(message: String, cause: Throwable? = null, errorCode: String = ""): OperationResult {
            return Failure(message, cause, errorCode)
        }

        /**
         * 创建权限不足结果。
         */
        @JvmStatic
        fun restricted(): OperationResult {
            return Restricted
        }

        /**
         * 创建验证错误结果。
         */
        @JvmStatic
        fun validationError(message: String, expectedFormat: String = ""): OperationResult {
            return ValidationError(message, expectedFormat)
        }

        /**
         * 创建异常错误结果。
         */
        @JvmStatic
        fun exceptionError(message: String, exception: Throwable): OperationResult {
            return ExceptionError(message, exception)
        }
    }
}