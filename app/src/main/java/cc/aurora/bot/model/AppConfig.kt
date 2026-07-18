package cc.aurora.bot.model

import cc.aurora.bot.service.ai.AiProvider
import com.google.gson.annotations.SerializedName

/**
 * 应用完整配置数据类。
 *
 * 将分散在 ConfigManager 中的各个配置项聚合为一个单一对象，
 * 便于配置的序列化、验证、迁移和展示。
 *
 * 使用方式:
 * ```
 * val config = AppConfig.fromConfigManager(context)
 * // 修改配置
 * val updated = config.copy(
 *     triggerWord = "小助手",
 *     deepSeekKey = "sk-xxx"
 * )
 * // 保存回 ConfigManager
 * updated.saveToConfigManager(context)
 * ```
 *
 * @property triggerWord 触发词，默认为 "机器人"
 * @property cacheTimes 对话缓存轮数，范围 1-50，默认 10
 * @property systemPrompt 全局默认调教规则 (System Prompt)
 * @property debugMode 调试模式开关
 * @property logLevel 日志级别: verbose, debug, info, warn, error, none
 * @property deepSeekKey DeepSeek API Key
 * @property deepSeekModel DeepSeek 模型名称
 * @property qwenKey 通义千问 API Key
 * @property qwenModel 通义千问模型名称
 * @property siliconKey 硅基流动 API Key
 * @property siliconModel 硅基流动模型名称
 * @property zhiPuKey 智谱 API Key
 * @property zhiPuModel 智谱模型名称
 * @property customApiUrl 自定义 API 地址
 * @property customKey 自定义 API Key
 * @property customModel 自定义模型名称
 * @property currentAiProvider 当前激活的 AI 提供商
 */
data class AppConfig(
    // 通用配置
    @SerializedName("triggerWord") val triggerWord: String = "机器人",
    @SerializedName("cacheTimes") val cacheTimes: Int = 10,
    @SerializedName("systemPrompt") val systemPrompt: String = "你是一个友好的微信AI助手，请用简洁自然的中文回复。",

    // 调试配置
    @SerializedName("debugMode") val debugMode: Boolean = false,
    @SerializedName("logLevel") val logLevel: String = "debug",

    // DeepSeek 配置
    @SerializedName("deepSeekKey") val deepSeekKey: String = "",
    @SerializedName("deepSeekModel") val deepSeekModel: String = "",

    // 通义千问配置
    @SerializedName("qwenKey") val qwenKey: String = "",
    @SerializedName("qwenModel") val qwenModel: String = "",

    // 硅基流动配置
    @SerializedName("siliconKey") val siliconKey: String = "",
    @SerializedName("siliconModel") val siliconModel: String = "",

    // 智谱配置
    @SerializedName("zhiPuKey") val zhiPuKey: String = "",
    @SerializedName("zhiPuModel") val zhiPuModel: String = "",

    // 自定义 API 配置
    @SerializedName("customApiUrl") val customApiUrl: String = "",
    @SerializedName("customKey") val customKey: String = "",
    @SerializedName("customModel") val customModel: String = ""
) {

    companion object {
        /**
         * 创建默认配置。
         */
        @JvmStatic
        fun default(): AppConfig = AppConfig()

        /**
         * 从 ConfigManager 加载当前配置并构建 AppConfig 对象。
         *
         * @param context Android 上下文
         * @return 包含当前所有配置的 AppConfig 对象
         */
        @JvmStatic
        fun fromConfigManager(context: android.content.Context): AppConfig {
            val cm = cc.aurora.bot.service.config.ConfigManager
            return AppConfig(
                triggerWord = cm.getString(context, cm.KEY_TRIGGER_WORD, "机器人"),
                cacheTimes = cm.getInt(context, cm.KEY_CACHE_TIMES, 10),
                systemPrompt = cm.getString(
                    context, cm.KEY_PROMPT,
                    "你是一个友好的微信AI助手，请用简洁自然的中文回复。"
                ),
                debugMode = cm.isDebugMode(context),
                logLevel = cm.getLogLevel(context),
                deepSeekKey = cm.getString(context, cm.KEY_DEEPSEEK_KEY),
                deepSeekModel = cm.getString(context, cm.KEY_DEEPSEEK_MODEL),
                qwenKey = cm.getString(context, cm.KEY_QWEN_KEY),
                qwenModel = cm.getString(context, cm.KEY_QWEN_MODEL),
                siliconKey = cm.getString(context, cm.KEY_SILICON_KEY),
                siliconModel = cm.getString(context, cm.KEY_SILICON_MODEL),
                zhiPuKey = cm.getString(context, cm.KEY_ZHIPU_KEY),
                zhiPuModel = cm.getString(context, cm.KEY_ZHIPU_MODEL),
                customApiUrl = cm.getString(context, cm.KEY_CUSTOM_API),
                customKey = cm.getString(context, cm.KEY_CUSTOM_KEY),
                customModel = cm.getString(context, cm.KEY_CUSTOM_MODEL)
            )
        }
    }

    /**
     * 将当前配置保存到 ConfigManager。
     *
     * @param context Android 上下文
     */
    fun saveToConfigManager(context: android.content.Context) {
        val cm = cc.aurora.bot.service.config.ConfigManager
        cm.saveString(context, cm.KEY_TRIGGER_WORD, triggerWord)
        cm.saveInt(context, cm.KEY_CACHE_TIMES, cacheTimes)
        cm.saveString(context, cm.KEY_PROMPT, systemPrompt)
        cm.setDebugMode(context, debugMode)
        cm.setLogLevel(context, logLevel)
        cm.saveString(context, cm.KEY_DEEPSEEK_KEY, deepSeekKey)
        cm.saveString(context, cm.KEY_DEEPSEEK_MODEL, deepSeekModel)
        cm.saveString(context, cm.KEY_QWEN_KEY, qwenKey)
        cm.saveString(context, cm.KEY_QWEN_MODEL, qwenModel)
        cm.saveString(context, cm.KEY_SILICON_KEY, siliconKey)
        cm.saveString(context, cm.KEY_SILICON_MODEL, siliconModel)
        cm.saveString(context, cm.KEY_ZHIPU_KEY, zhiPuKey)
        cm.saveString(context, cm.KEY_ZHIPU_MODEL, zhiPuModel)
        cm.saveString(context, cm.KEY_CUSTOM_API, customApiUrl)
        cm.saveString(context, cm.KEY_CUSTOM_KEY, customKey)
        cm.saveString(context, cm.KEY_CUSTOM_MODEL, customModel)
    }

    /**
     * 获取当前激活的 AI 提供商（按优先级: DeepSeek > Qwen > Silicon > Zhipu > Custom）。
     *
     * @return AI 提供商，如果没有配置任何 Key 则返回 null
     */
    fun getActiveAiProvider(): AiProvider? {
        return when {
            deepSeekKey.isNotBlank() -> AiProvider.DEEPSEEK
            qwenKey.isNotBlank() -> AiProvider.QWEN
            siliconKey.isNotBlank() -> AiProvider.SILICON
            zhiPuKey.isNotBlank() -> AiProvider.ZHIPU
            customKey.isNotBlank() && customApiUrl.isNotBlank() -> AiProvider.CUSTOM
            else -> null
        }
    }

    /**
     * 检查指定 AI 提供商是否已配置 Key。
     *
     * @param provider AI 提供商
     * @return true 如果该提供商已配置 Key
     */
    fun isProviderConfigured(provider: AiProvider): Boolean {
        return when (provider) {
            AiProvider.DEEPSEEK -> deepSeekKey.isNotBlank()
            AiProvider.QWEN -> qwenKey.isNotBlank()
            AiProvider.SILICON -> siliconKey.isNotBlank()
            AiProvider.ZHIPU -> zhiPuKey.isNotBlank()
            AiProvider.CUSTOM -> customKey.isNotBlank() && customApiUrl.isNotBlank()
        }
    }

    /**
     * 获取指定 AI 提供商的配置状态。
     *
     * @param provider AI 提供商
     * @return [AiProviderStatus] 配置状态
     */
    fun getProviderStatus(provider: AiProvider): AiProviderStatus {
        return when {
            isProviderConfigured(provider) -> AiProviderStatus.CONFIGURED
            else -> AiProviderStatus.NOT_CONFIGURED
        }
    }

    /**
     * 检查是否有任何 AI 提供商已配置。
     */
    fun hasAnyProviderConfigured(): Boolean {
        return getActiveAiProvider() != null
    }

    /**
     * 获取指定 AI 提供商的 API Key。
     *
     * @param provider AI 提供商
     * @return API Key，未配置时返回空字符串
     */
    fun getApiKey(provider: AiProvider): String {
        return when (provider) {
            AiProvider.DEEPSEEK -> deepSeekKey
            AiProvider.QWEN -> qwenKey
            AiProvider.SILICON -> siliconKey
            AiProvider.ZHIPU -> zhiPuKey
            AiProvider.CUSTOM -> customKey
        }
    }

    /**
     * 获取指定 AI 提供商的模型名称。
     * 如果未配置自定义模型，返回提供商的默认模型。
     *
     * @param provider AI 提供商
     * @return 模型名称
     */
    fun getModel(provider: AiProvider): String {
        val customModel = when (provider) {
            AiProvider.DEEPSEEK -> deepSeekModel
            AiProvider.QWEN -> qwenModel
            AiProvider.SILICON -> siliconModel
            AiProvider.ZHIPU -> zhiPuModel
            AiProvider.CUSTOM -> customModel
        }
        return customModel.ifBlank { provider.defaultModel }
    }

    /**
     * 获取指定 AI 提供商的 API 地址。
     * 仅 CUSTOM 提供商返回自定义地址，其他返回默认地址。
     *
     * @param provider AI 提供商
     * @return API 地址
     */
    fun getBaseUrl(provider: AiProvider): String {
        return when (provider) {
            AiProvider.CUSTOM -> customApiUrl.ifBlank { provider.defaultBaseUrl }
            else -> provider.defaultBaseUrl
        }
    }

    /**
     * 获取非敏感配置摘要（不含 API Key 完整值）。
     *
     * @return 配置摘要映射
     */
    fun getSummary(): Map<String, String> {
        val summary = mutableMapOf<String, String>()
        summary["triggerWord"] = triggerWord
        summary["cacheTimes"] = cacheTimes.toString()
        summary["prompt"] = systemPrompt.take(100)
        summary["deepSeekModel"] = deepSeekModel.ifBlank { "默认" }
        summary["qwenModel"] = qwenModel.ifBlank { "默认" }
        summary["siliconModel"] = siliconModel.ifBlank { "默认" }
        summary["zhiPuModel"] = zhiPuModel.ifBlank { "默认" }
        summary["customModel"] = customModel.ifBlank { "默认" }
        summary["deepSeekKeyConfigured"] = deepSeekKey.isNotBlank().toString()
        summary["qwenKeyConfigured"] = qwenKey.isNotBlank().toString()
        summary["siliconKeyConfigured"] = siliconKey.isNotBlank().toString()
        summary["zhiPuKeyConfigured"] = zhiPuKey.isNotBlank().toString()
        summary["customKeyConfigured"] = customKey.isNotBlank().toString()
        summary["customApiUrl"] = if (customApiUrl.isNotBlank()) customApiUrl else "未配置"
        summary["activeProvider"] = getActiveAiProvider()?.displayName ?: "未配置"
        summary["debugMode"] = debugMode.toString()
        summary["logLevel"] = logLevel
        return summary
    }
}