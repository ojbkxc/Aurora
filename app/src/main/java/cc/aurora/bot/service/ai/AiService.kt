package cc.aurora.bot.service.ai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// AI 请求参数

/**
 * AI 聊天请求参数。
 *
 * 包含模型名称、消息列表和可选的工具定义。
 * 序列化为 JSON 发送到 OpenAI 兼容的 API 端点。
 *
 * @property model 模型名称，如 "deepseek-chat"、"qwen-turbo"
 * @property messages 对话消息列表，包含 system/user/assistant 角色
 * @property tools 可选的工具定义列表，用于 Function Calling 或 Web Search
 */
data class AiReqParams(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<AiMessage>,
    @SerializedName("tools") val tools: List<AiTool>? = null
)

/**
 * 对话消息。
 *
 * 符合 OpenAI Chat Completion 消息格式。
 *
 * @property role 角色类型: "system"、"user" 或 "assistant"
 * @property content 消息内容文本
 */
data class AiMessage(
    @SerializedName("role") val role: String,  // system/user/assistant
    @SerializedName("content") val content: String
)

/**
 * AI 工具定义。
 *
 * 用于 Function Calling 或 Web Search 等工具调用场景。
 *
 * @property type 工具类型，如 "web_search"
 * @property webSearch Web Search 工具的配置，可为 null
 */
data class AiTool(
    @SerializedName("type") val type: String,
    @SerializedName("web_search") val webSearch: WebSearch? = null
)

/**
 * Web Search 工具配置。
 *
 * @property enable 是否启用 Web Search，默认为 true
 */
data class WebSearch(
    @SerializedName("enable") val enable: Boolean = true
)

// AI 返回

/**
 * AI 聊天 API 的返回结果。
 *
 * 符合 OpenAI Chat Completion 响应格式。
 *
 * @property id 请求唯一标识
 * @property choices 回复选项列表，通常取第一个
 * @property model 实际使用的模型名称
 * @property created 创建时间戳（Unix 时间）
 */
data class AiReturn(
    @SerializedName("id") val id: String = "",
    @SerializedName("choices") val choices: List<AiChoice> = emptyList(),
    @SerializedName("model") val model: String = "",
    @SerializedName("created") val created: Long = 0
)

/**
 * AI 回复选项。
 *
 * @property index 选项索引，通常为 0
 * @property message 回复消息
 * @property finishReason 结束原因: "stop"、"length"、"content_filter" 等
 */
data class AiChoice(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("message") val message: AiMessage,
    @SerializedName("finish_reason") val finishReason: String = ""
)

// AI 厂商配置

/**
 * AI 提供商枚举。
 *
 * 每个提供商包含显示名称、默认 API 地址和默认模型名称。
 * 支持 DeepSeek、通义千问、智谱、硅基流动和自定义 OpenAI 兼容 API。
 *
 * @property displayName 中文显示名称
 * @property defaultBaseUrl 默认 API 端点地址
 * @property defaultModel 默认模型名称
 */
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

/**
 * AI 服务核心类。
 *
 * 提供与 OpenAI 兼容的 AI API 交互功能，包括：
 * 1. 发送聊天请求 [chat]：支持多轮对话、重试和指数退避
 * 2. 连接检查 [checkConnection]：验证 API Key 和网络连通性
 *
 * 支持的 AI 提供商：DeepSeek、通义千问、智谱、硅基流动、自定义 OpenAI 兼容 API。
 *
 * 使用单例 OkHttpClient 复用 TCP 连接，减少网络开销。
 */
object AiService {
    private const val MAX_RETRIES = 2
    private const val RETRY_BASE_DELAY_MS = 1000L

    /**
     * 全局共享的单例 OkHttpClient，启用连接池以复用 TCP 连接。
     * - 连接池最大空闲连接数: 5
     * - 空闲连接存活时间: 5 分钟
     * - 连接超时: 30s, 读取超时: 60s, 写入超时: 30s
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(
            okhttp3.ConnectionPool(
                maxIdleConnections = 5,
                keepAliveDuration = 5, TimeUnit.MINUTES
            )
        )
        .build()

    private val gson = Gson()

    /**
     * 发送聊天请求，支持最多 2 次重试和指数退避。
     *
     * @param provider AI 提供商
     * @param apiKey API 密钥
     * @param model 模型名称
     * @param messages 消息列表
     * @param customBaseUrl 自定义 API 地址（仅 CUSTOM 提供商使用）
     * @return AI 返回结果
     * @throws AiServiceException 当所有重试均失败时抛出
     */
    fun chat(
        provider: AiProvider,
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        customBaseUrl: String? = null
    ): AiReturn {
        val baseUrl = customBaseUrl ?: provider.defaultBaseUrl

        var lastException: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                return executeChatRequest(provider, apiKey, model, messages, baseUrl, attempt)
            } catch (e: AiServiceException) {
                // 非可重试错误（如 4xx），直接抛出，不重试
                lastException = e
                break
            } catch (e: SocketTimeoutException) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    val delay = RETRY_BASE_DELAY_MS * (1L shl attempt) // 1s, 2s
                    Thread.sleep(delay)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    val delay = RETRY_BASE_DELAY_MS * (1L shl attempt)
                    Thread.sleep(delay)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    val delay = RETRY_BASE_DELAY_MS * (1L shl attempt)
                    Thread.sleep(delay)
                }
            }
        }

        throw AiServiceException(
            provider = provider.displayName,
            message = buildErrorMessage(lastException, provider, baseUrl),
            cause = lastException
        )
    }

    /**
     * 执行单次聊天请求。
     * 使用 use {} 确保 OkHttp Response 被正确关闭，防止资源泄漏。
     */
    private fun executeChatRequest(
        provider: AiProvider,
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        baseUrl: String,
        attempt: Int
    ): AiReturn {
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

        // 使用 use {} 确保 Response 被正确关闭，防止资源泄漏
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val statusCode = resp.code
                val errorBody = resp.body?.string() ?: "(empty body)"
                throw AiServiceException(
                    provider = provider.displayName,
                    message = "HTTP $statusCode from ${provider.displayName}: $errorBody",
                    cause = null
                )
            }

            val body = resp.body?.string()
                ?: throw AiServiceException(
                    provider = provider.displayName,
                    message = "AI response body is null (${provider.displayName})",
                    cause = null
                )

            return try {
                gson.fromJson(body, AiReturn::class.java)
            } catch (e: Exception) {
                throw AiServiceException(
                    provider = provider.displayName,
                    message = "Failed to parse response from ${provider.displayName}: ${e.message}",
                    cause = e
                )
            }
        }
    }

    /**
     * 构建详细错误消息
     */
    private fun buildErrorMessage(
        exception: Exception?,
        provider: AiProvider,
        baseUrl: String
    ): String {
        return when (exception) {
            is AiServiceException -> exception.message ?: "Unknown AI service error"
            is SocketTimeoutException -> "连接超时: ${provider.displayName} ($baseUrl) - 请检查网络连接或 API 地址"
            is UnknownHostException -> "无法解析主机: ${provider.displayName} ($baseUrl) - 请检查 API 地址是否正确"
            is IOException -> "网络错误: ${provider.displayName} - ${exception.message}"
            else -> "${provider.displayName} 请求失败: ${exception?.message ?: "未知错误"}"
        }
    }

    /**
     * 检查与指定 AI 提供商的连接是否正常。
     * 发送一个最简请求（仅包含 system 和空的 user 消息），
     * 验证 API Key 和网络连通性，不关心实际回复内容。
     *
     * @param provider AI 提供商
     * @param apiKey API 密钥
     * @param model 模型名称（可选，默认使用 provider 的默认模型）
     * @param customBaseUrl 自定义 API 地址（仅 CUSTOM 提供商使用）
     * @return ConnectionResult 包含连接状态和延迟等信息
     */
    fun checkConnection(
        provider: AiProvider,
        apiKey: String,
        model: String = provider.defaultModel,
        customBaseUrl: String? = null
    ): ConnectionResult {
        val baseUrl = customBaseUrl ?: provider.defaultBaseUrl
        val startTime = System.currentTimeMillis()

        try {
            // 构建最简探测请求
            val testMessages = listOf(
                AiMessage(role = "system", content = "You are a connection tester. Reply with only the word 'OK'."),
                AiMessage(role = "user", content = "ping")
            )

            val params = AiReqParams(
                model = model,
                messages = testMessages,
                tools = null // 连接检查不需要 tools
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
            response.use { resp ->
                val latency = System.currentTimeMillis() - startTime
                val statusCode = resp.code

                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    return try {
                        val aiReturn = gson.fromJson(body, AiReturn::class.java)
                        val reply = aiReturn.choices.firstOrNull()?.message?.content ?: "(empty)"
                        ConnectionResult(
                            success = true,
                            message = "连接成功 (${provider.displayName})",
                            latencyMs = latency,
                            statusCode = statusCode,
                            responsePreview = reply.take(100)
                        )
                    } catch (e: Exception) {
                        // 即使解析失败，只要 HTTP 200 就认为连接成功
                        ConnectionResult(
                            success = true,
                            message = "连接成功 (${provider.displayName}) - 响应解析警告",
                            latencyMs = latency,
                            statusCode = statusCode,
                            responsePreview = body.take(100)
                        )
                    }
                } else {
                    val errorBody = resp.body?.string() ?: "(empty body)"
                    return ConnectionResult(
                        success = false,
                        message = "HTTP $statusCode: ${getConnectionErrorMessage(statusCode, errorBody, provider)}",
                        latencyMs = latency,
                        statusCode = statusCode,
                        errorDetail = errorBody.take(200)
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            val latency = System.currentTimeMillis() - startTime
            return ConnectionResult(
                success = false,
                message = "连接超时: ${provider.displayName} ($baseUrl)",
                latencyMs = latency,
                errorDetail = e.message ?: "timeout"
            )
        } catch (e: UnknownHostException) {
            val latency = System.currentTimeMillis() - startTime
            return ConnectionResult(
                success = false,
                message = "无法解析主机: ${provider.displayName} ($baseUrl)",
                latencyMs = latency,
                errorDetail = e.message ?: "unknown host"
            )
        } catch (e: IOException) {
            val latency = System.currentTimeMillis() - startTime
            return ConnectionResult(
                success = false,
                message = "网络错误: ${provider.displayName} - ${e.message}",
                latencyMs = latency,
                errorDetail = e.message ?: "IO error"
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            return ConnectionResult(
                success = false,
                message = "${provider.displayName} 连接失败: ${e.message}",
                latencyMs = latency,
                errorDetail = e.message ?: "unknown error"
            )
        }
    }

    /**
     * 根据 HTTP 状态码生成友好的错误消息
     */
    private fun getConnectionErrorMessage(
        statusCode: Int,
        errorBody: String,
        provider: AiProvider
    ): String {
        return when (statusCode) {
            401 -> "认证失败 (${provider.displayName}): API Key 无效或已过期"
            403 -> "权限不足 (${provider.displayName}): 请检查 API Key 权限"
            404 -> "接口不存在 (${provider.displayName}): 请检查 API 地址是否正确"
            429 -> "请求过于频繁 (${provider.displayName}): 请稍后再试"
            500 -> "服务器内部错误 (${provider.displayName}): 请稍后再试"
            502 -> "网关错误 (${provider.displayName}): 服务暂时不可用"
            503 -> "服务不可用 (${provider.displayName}): 服务正在维护中"
            else -> "${provider.displayName} 返回 HTTP $statusCode"
        }
    }
}

/**
 * 连接检查结果。
 *
 * 用于 [AiService.checkConnection] 的结构化返回，包含连接状态和延迟等信息。
 *
 * @property success 连接是否成功
 * @property message 人类可读的结果描述
 * @property latencyMs 请求延迟（毫秒）
 * @property statusCode HTTP 状态码
 * @property responsePreview 回复内容的预览（前 100 字符）
 * @property errorDetail 错误详情（仅在失败时有值）
 */
data class ConnectionResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long = 0L,
    val statusCode: Int = 0,
    val responsePreview: String = "",
    val errorDetail: String = ""
)

/**
 * AI 服务异常。
 *
 * 包含提供商名称和详细错误信息，用于 [AiService.chat] 和 [AiService.checkConnection]
 * 在请求失败时抛出。
 *
 * @property provider 发生错误的 AI 提供商显示名称
 * @property message 错误描述消息
 * @property cause 原始异常（可选）
 * @throws AiServiceException 当 AI API 请求失败时抛出
 */
class AiServiceException(
    val provider: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)