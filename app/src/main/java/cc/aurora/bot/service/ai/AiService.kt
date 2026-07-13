package cc.aurora.bot.service.ai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// AI 请求参数
data class AiReqParams(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<AiMessage>,
    @SerializedName("tools") val tools: List<AiTool>? = null
)

data class AiMessage(
    @SerializedName("role") val role: String,  // system/user/assistant
    @SerializedName("content") val content: String
)

data class AiTool(
    @SerializedName("type") val type: String,
    @SerializedName("web_search") val webSearch: WebSearch? = null
)

data class WebSearch(
    @SerializedName("enable") val enable: Boolean = true
)

// AI 返回
data class AiReturn(
    @SerializedName("id") val id: String = "",
    @SerializedName("choices") val choices: List<AiChoice> = emptyList(),
    @SerializedName("model") val model: String = "",
    @SerializedName("created") val created: Long = 0
)

data class AiChoice(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("message") val message: AiMessage,
    @SerializedName("finish_reason") val finishReason: String = ""
)

// AI 厂商配置
enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-turbo"),
    ZHIPU("智谱", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-flash"),
    SILICON("硅基流动", "https://api.siliconflow.cn/v1/chat/completions", "Qwen/Qwen2.5-7B-Instruct"),
    CUSTOM("自定义", "", "")
}

object AiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun chat(
        provider: AiProvider,
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        customBaseUrl: String? = null
    ): AiReturn {
        val baseUrl = customBaseUrl ?: provider.defaultBaseUrl
        val params = AiReqParams(
            model = model,
            messages = messages,
            tools = if (provider == AiProvider.ZHIPU)
                listOf(AiTool(type = "web_search", webSearch = WebSearch())) else null
        )

        val json = gson.toJson(params)
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("AI response body is null")
        return gson.fromJson(body, AiReturn::class.java)
    }
}
