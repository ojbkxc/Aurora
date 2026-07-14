package cc.aurora.bot.service.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cc.aurora.bot.util.SecurityUtils

object ConfigManager {
    private const val TAG = "Aurora"
    private const val SP_NAME = "Aurora_SpData"
    private const val OLD_SP_NAME = "God_Hook_SpData"

    /**
     * 内存缓存 TTL（毫秒），用于减少 SharedPreferences 读取频率。
     * getString 和 getInt 的结果在 5 秒内直接返回缓存，超时后重新读取。
     */
    private const val CACHE_TTL_MS: Long = 5_000L // 5 秒

    // AI 配置键
    // ===== AI 配置键常量 =====

    /** DeepSeek API Key，默认值: 空字符串 "" */
    const val KEY_DEEPSEEK_KEY = "deepseekKey"
    /** DeepSeek 模型名称，默认值: 空字符串 ""（使用 AiProvider 默认模型） */
    const val KEY_DEEPSEEK_MODEL = "deepseekModel"
    /** 通义千问 API Key，默认值: 空字符串 "" */
    const val KEY_QWEN_KEY = "qwenKey"
    /** 通义千问模型名称，默认值: 空字符串 ""（使用 AiProvider 默认模型） */
    const val KEY_QWEN_MODEL = "qwenModel"
    /** 智谱 API Key，默认值: 空字符串 "" */
    const val KEY_ZHIPU_KEY = "zhipuKey"
    /** 智谱模型名称，默认值: 空字符串 ""（使用 AiProvider 默认模型） */
    const val KEY_ZHIPU_MODEL = "zhipuModel"
    /** 硅基流动 API Key，默认值: 空字符串 "" */
    const val KEY_SILICON_KEY = "siliconKey"
    /** 硅基流动模型名称，默认值: 空字符串 ""（使用 AiProvider 默认模型） */
    const val KEY_SILICON_MODEL = "siliconModel"
    /** 自定义 API 地址 (OpenAI 兼容)，默认值: 空字符串 "" */
    const val KEY_CUSTOM_API = "customApiUrl"
    /** 自定义 API Key，默认值: 空字符串 "" */
    const val KEY_CUSTOM_KEY = "customApiKey"
    /** 自定义 API 模型名称，默认值: 空字符串 ""（使用 AiProvider 默认模型） */
    const val KEY_CUSTOM_MODEL = "customModel"
    /** 触发词，默认值: "机器人" */
    const val KEY_TRIGGER_WORD = "triggerWord"
    /** 对话缓存轮数，默认值: 10，范围: 1-50 */
    const val KEY_CACHE_TIMES = "cacheTimes"
    /** 默认调教规则 (System Prompt)，默认值: "你是一个友好的微信AI助手，请用简洁自然的中文回复。" */
    const val KEY_PROMPT = "prompt"

    // ===== 调试配置键 =====

    /** 调试模式开关，默认值: false */
    const val KEY_DEBUG_MODE = "debugMode"
    /** 日志级别，默认值: "debug"，有效值: verbose, debug, info, warn, error, none */
    const val KEY_LOG_LEVEL = "logLevel"

    // ===== 业务配置键 =====

    /** 绑定的微信 ID 集合，默认值: 空集合 */
    const val KEY_WX_IDS = "wxIds"
    /** 消息发送计数，默认值: 0 */
    const val KEY_SEND_COUNT = "sendCount"
    /** 聊天室信息集合 (JSON 格式)，默认值: 空集合 */
    const val KEY_CHAT_ROOM_INFO = "chatRoomInfoDTOSet"
    /** 订阅信息集合 (JSON 格式)，默认值: 空集合 */
    const val KEY_SUBSCRIBE = "wxSubcribeDTOSet"
    /** 群欢迎语集合 (JSON 格式)，默认值: 空集合 */
    const val KEY_WELCOME = "wxGroupWelcomeSet"
    /** 开发者微信 ID 集合，默认值: 空集合 */
    const val KEY_DEV_WX_IDS = "devWxIds"
    /** 聊天消息缓存 (JSON 格式)，默认值: 空列表 */
    const val KEY_CHAT_MESSAGES = "chatMessages"

    @Volatile
    private var migrated = false

    /**
     * 共享的 Gson 实例，避免重复创建。
     * Gson 是线程安全的，可以跨线程复用。
     */
    private val gson = Gson()

    // ===================== 内存缓存 =====================

    /**
     * 字符串缓存条目：存储值和过期时间戳。
     */
    private data class CacheEntry<T>(val value: T, val expireAt: Long)

    /**
     * 字符串值缓存：key -> CacheEntry
     */
    private val stringCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<String>>()

    /**
     * 整数值缓存：key -> CacheEntry
     */
    private val intCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<Int>>()

    /**
     * 迁移旧 SP 数据到新 SP 名称。
     * 首次访问时自动调用，只需调用一次。
     * 由 DefaultApplication 在初始化时调用。
     */
    fun migrateIfNeeded(context: Context) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            try {
                val oldSP = context.getSharedPreferences(OLD_SP_NAME, Context.MODE_PRIVATE)
                val newSP = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

                // 检查新 SP 是否已有数据，避免重复迁移
                if (newSP.all.isEmpty() && oldSP.all.isNotEmpty()) {
                    Log.d(TAG, "ConfigManager: migrating old SP '$OLD_SP_NAME' to '$SP_NAME'")
                    val editor = newSP.edit()
                    for ((key, value) in oldSP.all) {
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Long -> editor.putLong(key, value)
                            is Set<*> -> {
                                // 安全转换：过滤非 String 元素，避免 ClassCastException
                                val stringSet = value.filterIsInstance<String>().toSet()
                                if (stringSet.isNotEmpty()) {
                                    editor.putStringSet(key, stringSet)
                                } else {
                                    Log.w(TAG, "ConfigManager: skipping Set key '$key' with no String elements")
                                }
                            }
                        }
                    }
                    editor.apply()
                    Log.d(TAG, "ConfigManager: migration complete, ${oldSP.all.size} entries copied")
                } else {
                    Log.d(TAG, "ConfigManager: no migration needed (new SP has data or old SP is empty)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "ConfigManager: migration failed: ${e.message}")
            } finally {
                migrated = true
            }
        }
    }

    private fun getSP(context: Context): android.content.SharedPreferences {
        // 确保迁移已执行
        if (!migrated) {
            migrateIfNeeded(context)
        }
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 保存字符串类型的配置值。
     *
     * 写入 SharedPreferences 并清除对应的内存缓存，确保下次读取时从 SP 重新加载。
     *
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @param value 要保存的字符串值
     */
    fun saveString(context: Context, key: String, value: String) {
        getSP(context).edit().putString(key, value).apply()
        stringCache.remove(key) // 使缓存失效
    }

    /**
     * 获取字符串类型的配置值。
     *
     * 优先从内存缓存读取（TTL 内有效），缓存未命中时从 SharedPreferences 读取。
     * 默认返回空字符串。
     *
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @param default 默认值，当配置不存在或读取失败时返回，默认为空字符串 ""
     * @return 配置的字符串值，若不存在则返回 default
     */
    fun getString(context: Context, key: String, default: String = ""): String {
        // 检查缓存
        val now = System.currentTimeMillis()
        val cached = stringCache[key]
        if (cached != null && now < cached.expireAt) {
            return cached.value
        }

        // 缓存未命中或已过期，从 SP 读取
        val value = getSP(context).getString(key, default) ?: default
        stringCache[key] = CacheEntry(value, now + CACHE_TTL_MS)
        return value
    }

    /**
     * 保存整数类型的配置值。
     *
     * 写入 SharedPreferences 并清除对应的内存缓存，确保下次读取时从 SP 重新加载。
     *
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @param value 要保存的整数值
     */
    fun saveInt(context: Context, key: String, value: Int) {
        getSP(context).edit().putInt(key, value).apply()
        intCache.remove(key) // 使缓存失效
    }

    /**
     * 获取整数类型的配置值。
     *
     * 优先从内存缓存读取（TTL 内有效），缓存未命中时从 SharedPreferences 读取。
     * 默认返回 0。
     *
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @param default 默认值，当配置不存在或读取失败时返回，默认为 0
     * @return 配置的整数值，若不存在则返回 default
     */
    fun getInt(context: Context, key: String, default: Int = 0): Int {
        // 检查缓存
        val now = System.currentTimeMillis()
        val cached = intCache[key]
        if (cached != null && now < cached.expireAt) {
            return cached.value
        }

        // 缓存未命中或已过期，从 SP 读取
        val value = getSP(context).getInt(key, default)
        intCache[key] = CacheEntry(value, now + CACHE_TTL_MS)
        return value
    }

    /**
     * 保存字符串集合类型的配置值。
     *
     * 直接写入 SharedPreferences，不使用缓存。
     *
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @param value 要保存的字符串集合
     */
    fun saveStringSet(context: Context, key: String, value: Set<String>) {
        getSP(context).edit().putStringSet(key, value).apply()
    }

    /**
     * 获取字符串集合类型的配置值。
     *
     * 直接从 SharedPreferences 读取，不使用缓存。
     * 默认返回空集合。
     *
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @return 配置的字符串集合，若不存在则返回空集合
     */
    fun getStringSet(context: Context, key: String): Set<String> {
        return getSP(context).getStringSet(key, emptySet()) ?: emptySet()
    }

    // 通用 JSON 存储

    /**
     * 以 JSON 格式保存任意类型的配置值。
     *
     * 使用 Gson 将对象序列化为 JSON 字符串后存入 SharedPreferences。
     * 类型参数 T 由调用处推断，需要 reified 关键字支持。
     *
     * @param T 要保存的对象类型
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @param value 要保存的对象，将被序列化为 JSON
     */
    inline fun <reified T> saveJson(context: Context, key: String, value: T) {
        val json = gson.toJson(value)
        getSP(context).edit().putString(key, json).apply()
    }

    /**
     * 从 JSON 格式读取配置值并反序列化为指定类型。
     *
     * 使用 Gson 将 SharedPreferences 中的 JSON 字符串反序列化为指定类型。
     * 如果 JSON 解析失败，返回默认值。
     *
     * @param T 要反序列化的目标类型
     * @param context Android 上下文
     * @param key 配置键名，使用本类中定义的 KEY_* 常量之一
     * @param default 默认值，当配置不存在或 JSON 解析失败时返回
     * @return 反序列化后的对象，若解析失败则返回 default
     */
    inline fun <reified T> getJson(context: Context, key: String, default: T): T {
        val json = getSP(context).getString(key, null) ?: return default
        return try {
            gson.fromJson(json, object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            default
        }
    }

    /**
     * 获取所有配置项（键值对），API Key 等敏感信息已掩码处理。
     *
     * 返回完整的 SharedPreferences 数据映射，但 API Key 字段值（KEY_DEEPSEEK_KEY、
     * KEY_QWEN_KEY、KEY_SILICON_KEY、KEY_ZHIPU_KEY、KEY_CUSTOM_KEY）会被掩码处理。
     * 如需获取原始 API Key 值，请使用 [getString] 方法直接读取。
     *
     * @param context Android 上下文
     * @return 所有配置项的键值对映射，读取失败时返回空映射
     */
    fun getAllConfigs(context: Context): Map<String, Any> {
        return try {
            val raw = getSP(context).all.toMap()
            val masked = mutableMapOf<String, Any>()
            val keyFields = setOf(
                KEY_DEEPSEEK_KEY, KEY_QWEN_KEY, KEY_SILICON_KEY,
                KEY_ZHIPU_KEY, KEY_CUSTOM_KEY
            )
            for ((key, value) in raw) {
                masked[key] = if (key in keyFields && value is String) {
                    SecurityUtils.maskApiKey(value)
                } else {
                    value
                }
            }
            masked
        } catch (e: Exception) {
            Log.e(TAG, "ConfigManager: getAllConfigs failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 重置所有配置：清空 SharedPreferences 中的所有数据。
     *
     * 会先记录警告日志，然后执行清空操作。此操作不可逆，慎用。
     * 清空后不会清除内存缓存，建议调用后重启应用。
     *
     * @param context Android 上下文
     */
    fun resetAll(context: Context) {
        try {
            Log.w(TAG, "ConfigManager: resetAll() called - clearing all configuration data")
            val editor = getSP(context).edit()
            editor.clear()
            editor.apply()
            Log.d(TAG, "ConfigManager: all configuration data has been cleared")
        } catch (e: Exception) {
            Log.e(TAG, "ConfigManager: resetAll failed: ${e.message}")
        }
    }

    /**
     * 获取调试模式状态。
     *
     * 默认值为 false（关闭调试模式）。
     *
     * @param context Android 上下文
     * @return true 表示调试模式已开启，false 表示关闭
     */
    fun isDebugMode(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_DEBUG_MODE, false)
    }

    /**
     * 设置调试模式状态。
     *
     * 调试模式开启后，会输出更详细的日志信息。
     *
     * @param context Android 上下文
     * @param enabled true 表示开启调试模式，false 表示关闭
     */
    fun setDebugMode(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    /**
     * 获取日志级别。
     *
     * 默认值为 "debug"。
     * 有效值: verbose, debug, info, warn, error, none
     *
     * @param context Android 上下文
     * @return 当前日志级别字符串
     */
    fun getLogLevel(context: Context): String {
        return getString(context, KEY_LOG_LEVEL, "debug")
    }

    /**
     * 设置日志级别。
     *
     * 自动转换为小写存储。有效值: verbose, debug, info, warn, error, none
     *
     * @param context Android 上下文
     * @param level 日志级别字符串，大小写不敏感
     */
    fun setLogLevel(context: Context, level: String) {
        saveString(context, KEY_LOG_LEVEL, level.lowercase())
    }

    /**
     * 获取非敏感的配置摘要信息。
     * 返回用于 UI 展示或 API 查询的配置信息，不包含 API Key 等敏感数据。
     *
     * @return 包含模型名称、触发词、缓存轮数等非敏感配置的映射
     */
    fun getConfigSummary(context: Context): Map<String, String> {
        val summary = mutableMapOf<String, String>()

        try {
            val sp = getSP(context)

            // 通用配置
            summary["triggerWord"] = sp.getString(KEY_TRIGGER_WORD, "") ?: ""
            summary["cacheTimes"] = (sp.getInt(KEY_CACHE_TIMES, 10)).toString()
            summary["prompt"] = (sp.getString(KEY_PROMPT, "") ?: "").take(100) // 截断过长 prompt

            // 各厂商的模型（非敏感）
            summary["deepseekModel"] = sp.getString(KEY_DEEPSEEK_MODEL, "") ?: ""
            summary["qwenModel"] = sp.getString(KEY_QWEN_MODEL, "") ?: ""
            summary["siliconModel"] = sp.getString(KEY_SILICON_MODEL, "") ?: ""
            summary["zhipuModel"] = sp.getString(KEY_ZHIPU_MODEL, "") ?: ""
            summary["customModel"] = sp.getString(KEY_CUSTOM_MODEL, "") ?: ""

            // 各厂商的 Key 是否已配置（只显示是否配置，不显示具体内容）
            summary["deepseekKeyConfigured"] = (!sp.getString(KEY_DEEPSEEK_KEY, "").isNullOrBlank()).toString()
            summary["qwenKeyConfigured"] = (!sp.getString(KEY_QWEN_KEY, "").isNullOrBlank()).toString()
            summary["siliconKeyConfigured"] = (!sp.getString(KEY_SILICON_KEY, "").isNullOrBlank()).toString()
            summary["zhipuKeyConfigured"] = (!sp.getString(KEY_ZHIPU_KEY, "").isNullOrBlank()).toString()
            summary["customKeyConfigured"] = (!sp.getString(KEY_CUSTOM_KEY, "").isNullOrBlank()).toString()

            // 自定义 API 地址
            val customApi = sp.getString(KEY_CUSTOM_API, "") ?: ""
            summary["customApiUrl"] = if (customApi.isNotBlank()) customApi else "not configured"

            // 业务统计
            summary["bindingCount"] = (sp.getStringSet(KEY_WX_IDS, emptySet())?.size ?: 0).toString()
            summary["sendCount"] = (sp.getInt(KEY_SEND_COUNT, 0)).toString()
            summary["devIdCount"] = (sp.getStringSet(KEY_DEV_WX_IDS, emptySet())?.size ?: 0).toString()

        } catch (e: Exception) {
            Log.e(TAG, "ConfigManager: getConfigSummary failed: ${e.message}")
            summary["error"] = "Failed to read config: ${e.message}"
        }

        return summary
    }
}