package cc.aurora.bot.service.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ConfigManager {
    private const val SP_NAME = "God_Hook_SpData"

    // AI 配置键
    const val KEY_DEEPSEEK_KEY = "deepseekKey"
    const val KEY_DEEPSEEK_MODEL = "deepseekModel"
    const val KEY_QWEN_KEY = "qwenKey"
    const val KEY_QWEN_MODEL = "qwenModel"
    const val KEY_ZHIPU_KEY = "zhipuKey"
    const val KEY_ZHIPU_MODEL = "zhipuModel"
    const val KEY_SILICON_KEY = "siliconKey"
    const val KEY_SILICON_MODEL = "siliconModel"
    const val KEY_CUSTOM_API = "customApiUrl"
    const val KEY_CUSTOM_KEY = "customApiKey"
    const val KEY_CUSTOM_MODEL = "customModel"
    const val KEY_TRIGGER_WORD = "triggerWord"
    const val KEY_CACHE_TIMES = "cacheTimes"
    const val KEY_PROMPT = "prompt"

    // 业务配置键
    const val KEY_WX_IDS = "wxIds"
    const val KEY_SEND_COUNT = "sendCount"
    const val KEY_CHAT_ROOM_INFO = "chatRoomInfoDTOSet"
    const val KEY_SUBSCRIBE = "wxSubcribeDTOSet"
    const val KEY_WELCOME = "wxGroupWelcomeSet"
    const val KEY_DEV_WX_IDS = "devWxIds"
    const val KEY_CHAT_MESSAGES = "chatMessages"

    private fun getSP(context: Context) =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun saveString(context: Context, key: String, value: String) {
        getSP(context).edit().putString(key, value).apply()
    }

    fun getString(context: Context, key: String, default: String = ""): String {
        return getSP(context).getString(key, default) ?: default
    }

    fun saveInt(context: Context, key: String, value: Int) {
        getSP(context).edit().putInt(key, value).apply()
    }

    fun getInt(context: Context, key: String, default: Int = 0): Int {
        return getSP(context).getInt(key, default)
    }

    fun saveStringSet(context: Context, key: String, value: Set<String>) {
        getSP(context).edit().putStringSet(key, value).apply()
    }

    fun getStringSet(context: Context, key: String): Set<String> {
        return getSP(context).getStringSet(key, emptySet()) ?: emptySet()
    }

    // 通用 JSON 存储
    inline fun <reified T> saveJson(context: Context, key: String, value: T) {
        val json = Gson().toJson(value)
        getSP(context).edit().putString(key, json).apply()
    }

    inline fun <reified T> getJson(context: Context, key: String, default: T): T {
        val json = getSP(context).getString(key, null) ?: return default
        return try {
            Gson().fromJson(json, object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            default
        }
    }
}
