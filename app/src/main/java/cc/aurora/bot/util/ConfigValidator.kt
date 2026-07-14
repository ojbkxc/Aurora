package cc.aurora.bot.util

/**
 * 配置验证器，用于验证用户输入的配置值是否合法。
 *
 * 提供以下验证功能：
 * - API Key 格式验证
 * - URL 格式验证（HTTP/HTTPS）
 * - 模型名称验证
 * - 触发词验证
 * - 缓存轮数范围验证
 * - 日志级别验证
 *
 * 所有验证方法返回 [ValidationResult]，包含是否通过和错误消息。
 * 使用方式：
 * ```
 * val result = ConfigValidator.validateApiKey("sk-abc123")
 * if (!result.isValid) {
 *     showError(result.errorMessage)
 * }
 * ```
 */
object ConfigValidator {

    /**
     * 验证结果数据类。
     *
     * @param isValid 验证是否通过
     * @param errorMessage 验证失败时的错误消息，通过时为空字符串
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String = ""
    ) {
        companion object {
            /** 创建一个通过验证的结果 */
            @JvmStatic
            fun ok(): ValidationResult = ValidationResult(true, "")
            @JvmStatic
            fun fail(message: String): ValidationResult = ValidationResult(false, message)
        }
    }

    // ===================== 常量 =====================

    /** API Key 的最小长度 */
    private const val MIN_API_KEY_LENGTH = 8

    /** API Key 的最大长度 */
    private const val MAX_API_KEY_LENGTH = 256

    /** 模型名称的最小长度 */
    private const val MIN_MODEL_NAME_LENGTH = 1

    /** 模型名称的最大长度 */
    private const val MAX_MODEL_NAME_LENGTH = 128

    /** 触发词的最小长度 */
    private const val MIN_TRIGGER_WORD_LENGTH = 1

    /** 触发词的最大长度 */
    private const val MAX_TRIGGER_WORD_LENGTH = 20

    /** 缓存轮数的最小值 */
    const val MIN_CACHE_TIMES = 1

    /** 缓存轮数的最大值 */
    const val MAX_CACHE_TIMES = 50

    /** 有效的日志级别 */
    private val VALID_LOG_LEVELS = setOf("verbose", "debug", "info", "warn", "error", "none")

    // ===================== API Key 验证 =====================

    /**
     * 验证 API Key 格式。
     *
     * 规则：
     * - 不能为空或仅包含空白字符
     * - 长度在 [MIN_API_KEY_LENGTH] 到 [MAX_API_KEY_LENGTH] 之间
     * - 只包含可打印字符（无控制字符）
     *
     * @param apiKey 要验证的 API Key 字符串
     * @return [ValidationResult] 验证结果
     */
    fun validateApiKey(apiKey: String): ValidationResult {
        val trimmed = apiKey.trim()

        if (trimmed.isEmpty()) {
            return ValidationResult.fail("API Key 不能为空")
        }

        if (trimmed.length < MIN_API_KEY_LENGTH) {
            return ValidationResult.fail("API Key 长度不能少于 $MIN_API_KEY_LENGTH 个字符")
        }

        if (trimmed.length > MAX_API_KEY_LENGTH) {
            return ValidationResult.fail("API Key 长度不能超过 $MAX_API_KEY_LENGTH 个字符")
        }

        // 检查是否包含不可打印字符
        for (ch in trimmed) {
            if (ch.code < 0x20 || ch.code > 0x7E) {
                return ValidationResult.fail("API Key 包含非法字符: '${ch}' (0x${String.format("%02X", ch.code)})")
            }
        }

        return ValidationResult.ok()
    }

    // ===================== URL 验证 =====================

    /**
     * 验证 URL 格式。
     *
     * 规则：
     * - 必须以 http:// 或 https:// 开头
     * - 至少包含一个点（域名分隔符）
     * - 不包含空格
     * - 总长度不超过 2048 字符
     *
     * @param url 要验证的 URL 字符串
     * @return [ValidationResult] 验证结果
     */
    fun validateUrl(url: String): ValidationResult {
        val trimmed = url.trim()

        if (trimmed.isEmpty()) {
            return ValidationResult.fail("URL 不能为空")
        }

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ValidationResult.fail("URL 必须以 http:// 或 https:// 开头")
        }

        if (trimmed.contains(" ")) {
            return ValidationResult.fail("URL 不能包含空格")
        }

        if (trimmed.length > 2048) {
            return ValidationResult.fail("URL 长度不能超过 2048 个字符")
        }

        // 基本域名检查：http(s):// 之后至少应包含一个点
        val afterProtocol = trimmed.removePrefix("https://").removePrefix("http://")
        if (!afterProtocol.contains(".") && !afterProtocol.startsWith("localhost")) {
            return ValidationResult.fail("URL 格式无效: 缺少有效域名")
        }

        return ValidationResult.ok()
    }

    // ===================== 模型名称验证 =====================

    /**
     * 验证模型名称。
     *
     * 规则：
     * - 不能为空
     * - 长度在 [MIN_MODEL_NAME_LENGTH] 到 [MAX_MODEL_NAME_LENGTH] 之间
     * - 只包含字母、数字、连字符、下划线、点、冒号和斜线
     *
     * @param modelName 要验证的模型名称
     * @return [ValidationResult] 验证结果
     */
    fun validateModelName(modelName: String): ValidationResult {
        val trimmed = modelName.trim()

        if (trimmed.isEmpty()) {
            return ValidationResult.fail("模型名称不能为空")
        }

        if (trimmed.length < MIN_MODEL_NAME_LENGTH) {
            return ValidationResult.fail("模型名称长度不能少于 $MIN_MODEL_NAME_LENGTH 个字符")
        }

        if (trimmed.length > MAX_MODEL_NAME_LENGTH) {
            return ValidationResult.fail("模型名称长度不能超过 $MAX_MODEL_NAME_LENGTH 个字符")
        }

        // 允许: 字母、数字、连字符、下划线、点、冒号、斜线
        val validPattern = Regex("^[a-zA-Z0-9\\-_.:/]+$")
        if (!validPattern.matches(trimmed)) {
            return ValidationResult.fail("模型名称包含无效字符，仅允许字母、数字、连字符、下划线、点、冒号和斜线")
        }

        return ValidationResult.ok()
    }

    // ===================== 触发词验证 =====================

    /**
     * 验证触发词。
     *
     * 规则：
     * - 可以为空（空表示接受所有消息）
     * - 如果非空，长度在 [MIN_TRIGGER_WORD_LENGTH] 到 [MAX_TRIGGER_WORD_LENGTH] 之间
     * - 不能以 # 开头（与指令冲突）
     * - 不能包含换行符
     *
     * @param triggerWord 要验证的触发词
     * @return [ValidationResult] 验证结果
     */
    fun validateTriggerWord(triggerWord: String): ValidationResult {
        val trimmed = triggerWord.trim()

        if (trimmed.isEmpty()) {
            // 空触发词是允许的（表示接受所有消息）
            return ValidationResult.ok()
        }

        if (trimmed.length < MIN_TRIGGER_WORD_LENGTH) {
            return ValidationResult.fail("触发词长度不能少于 $MIN_TRIGGER_WORD_LENGTH 个字符")
        }

        if (trimmed.length > MAX_TRIGGER_WORD_LENGTH) {
            return ValidationResult.fail("触发词长度不能超过 $MAX_TRIGGER_WORD_LENGTH 个字符")
        }

        if (trimmed.startsWith("#")) {
            return ValidationResult.fail("触发词不能以 # 开头（与指令冲突）")
        }

        if (trimmed.contains("\n") || trimmed.contains("\r")) {
            return ValidationResult.fail("触发词不能包含换行符")
        }

        return ValidationResult.ok()
    }

    // ===================== 缓存轮数验证 =====================

    /**
     * 验证缓存轮数。
     *
     * 规则：
     * - 必须是有效的整数
     * - 范围在 [MIN_CACHE_TIMES] 到 [MAX_CACHE_TIMES] 之间
     *
     * @param cacheTimesStr 缓存轮数字符串
     * @return [ValidationResult] 验证结果
     */
    fun validateCacheTimes(cacheTimesStr: String): ValidationResult {
        val trimmed = cacheTimesStr.trim()

        if (trimmed.isEmpty()) {
            return ValidationResult.fail("缓存轮数不能为空")
        }

        val times = trimmed.toIntOrNull()
        if (times == null) {
            return ValidationResult.fail("缓存轮数必须是有效的整数，你输入的是: $trimmed")
        }

        if (times < MIN_CACHE_TIMES) {
            return ValidationResult.fail("缓存轮数不能小于 $MIN_CACHE_TIMES，你输入的是: $times")
        }

        if (times > MAX_CACHE_TIMES) {
            return ValidationResult.fail("缓存轮数不能超过 $MAX_CACHE_TIMES，你输入的是: $times")
        }

        return ValidationResult.ok()
    }

    /**
     * 验证缓存轮数（整数版本）。
     *
     * @param cacheTimes 缓存轮数整数值
     * @return [ValidationResult] 验证结果
     */
    fun validateCacheTimes(cacheTimes: Int): ValidationResult {
        if (cacheTimes < MIN_CACHE_TIMES) {
            return ValidationResult.fail("缓存轮数不能小于 $MIN_CACHE_TIMES，你输入的是: $cacheTimes")
        }

        if (cacheTimes > MAX_CACHE_TIMES) {
            return ValidationResult.fail("缓存轮数不能超过 $MAX_CACHE_TIMES，你输入的是: $cacheTimes")
        }

        return ValidationResult.ok()
    }

    // ===================== 日志级别验证 =====================

    /**
     * 验证日志级别。
     *
     * 规则：
     * - 必须是以下值之一: verbose, debug, info, warn, error, none
     * - 大小写不敏感
     *
     * @param logLevel 日志级别字符串
     * @return [ValidationResult] 验证结果
     */
    fun validateLogLevel(logLevel: String): ValidationResult {
        val trimmed = logLevel.trim().lowercase()

        if (trimmed.isEmpty()) {
            return ValidationResult.fail("日志级别不能为空")
        }

        if (trimmed !in VALID_LOG_LEVELS) {
            return ValidationResult.fail(
                "无效的日志级别: '$trimmed'，有效值为: ${VALID_LOG_LEVELS.joinToString(", ")}"
            )
        }

        return ValidationResult.ok()
    }

    // ===================== 综合验证 =====================

    /**
     * 验证完整的配置项集合。
     *
     * 依次验证所有配置字段，返回所有验证失败的字段列表。
     * 适用于批量配置保存前的预检。
     *
     * @param configs 配置键值对映射
     * @return 验证失败的字段名到错误消息的映射，空映射表示全部通过
     */
    fun validateAll(configs: Map<String, String>): Map<String, String> {
        val errors = mutableMapOf<String, String>()

        // 遍历所有配置项，根据 key 选择对应的验证方法
        for ((key, value) in configs) {
            when {
                key.endsWith("Key") || key.contains("key", ignoreCase = true) -> {
                    if (value.isNotBlank()) {
                        val result = validateApiKey(value)
                        if (!result.isValid) {
                            errors[key] = result.errorMessage
                        }
                    }
                }
                key.contains("api", ignoreCase = true) || key.contains("url", ignoreCase = true) -> {
                    if (value.isNotBlank()) {
                        val result = validateUrl(value)
                        if (!result.isValid) {
                            errors[key] = result.errorMessage
                        }
                    }
                }
                key.contains("model", ignoreCase = true) -> {
                    if (value.isNotBlank()) {
                        val result = validateModelName(value)
                        if (!result.isValid) {
                            errors[key] = result.errorMessage
                        }
                    }
                }
                key.contains("trigger", ignoreCase = true) -> {
                    if (value.isNotBlank()) {
                        val result = validateTriggerWord(value)
                        if (!result.isValid) {
                            errors[key] = result.errorMessage
                        }
                    }
                }
                key.contains("cache", ignoreCase = true) -> {
                    if (value.isNotBlank()) {
                        val result = validateCacheTimes(value)
                        if (!result.isValid) {
                            errors[key] = result.errorMessage
                        }
                    }
                }
                key.contains("logLevel", ignoreCase = true) || key.contains("log_level", ignoreCase = true) -> {
                    if (value.isNotBlank()) {
                        val result = validateLogLevel(value)
                        if (!result.isValid) {
                            errors[key] = result.errorMessage
                        }
                    }
                }
            }
        }

        return errors
    }
}