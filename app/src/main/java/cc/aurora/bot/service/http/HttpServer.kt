package cc.aurora.bot.service.http

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.LinkedHashMap
import cc.aurora.bot.util.RateLimiter
import cc.aurora.bot.util.SecurityUtils

/**
 * Aurora HTTP API 服务器。
 *
 * 基于 NanoHTTPD，提供外部 HTTP 接口用于：
 * 1. 发送微信消息 (POST /send)
 * 2. 查询机器人状态 (GET /status)
 * 3. 查询绑定列表 (GET /bindings)
 * 4. 查询订阅列表 (GET /subscriptions)
 * 5. 健康检查 (GET /health)
 * 6. 配置信息 (GET /config)
 *
 * 端口: 默认 5888（与 GodHook 兼容），若被占用则回退到 5889、5890。
 * 内置速率限制（每个 IP 每分钟最多 60 次请求）和 CORS 支持。
 *
 * 使用方式：
 * ```
 * val server = HttpServer(5888).apply {
 *     onSendMessage = { wxIds, msg -> ... }
 *     onGetStatus = { ... }
 * }
 * val port = server.tryStart()
 * if (port > 0) {
 *     println("HTTP server started on port $port")
 * }
 * ```
 *
 * @param port 尝试监听的端口，默认为 5888
 */
class HttpServer(port: Int = 5888) : NanoHTTPD(port) {

    /**
     * 服务器状态枚举。
     */
    enum class ServerStatus {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR
    }

    companion object {
        /** 默认端口回退列表 */
        private val FALLBACK_PORTS = listOf(5888, 5889, 5890)

        /** 当前服务器状态 */
        @Volatile
        @JvmStatic
        var status: ServerStatus = ServerStatus.STOPPED
            private set

        /** 实际使用的端口 */
        @Volatile
        @JvmStatic
        var actualPort: Int = 0
            private set

        /** 速率限制: 每个 IP 每分钟最大请求数 */
        private const val RATE_LIMIT_MAX_REQUESTS = 60
        private const val RATE_LIMIT_WINDOW_MS = 60_000L // 1 分钟

        /** escapeJson 的 LRU 缓存最大容量 */
        private const val ESCAPE_CACHE_MAX_SIZE = 200

        /**
         * escapeJson 的 LRU 缓存：基于 LinkedHashMap 实现。
         * 对常见字符串（如 status "ok", "success", "healthy" 等）缓存转义结果，
         * 避免每次 JSON 序列化时重复遍历字符。
         */
        private val escapeCache = object : LinkedHashMap<String, String>(
            ESCAPE_CACHE_MAX_SIZE, 0.75f, true
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > ESCAPE_CACHE_MAX_SIZE
            }
        }
    }

    /** 速率限制器: 使用统一的 RateLimiter 工具类 */
    private val rateLimiter = RateLimiter(RATE_LIMIT_MAX_REQUESTS, RATE_LIMIT_WINDOW_MS)

    /**
     * 发送消息回调。
     *
     * 当 HTTP 客户端通过 /send 端点请求发送消息时调用。
     *
     * @param wxIds 目标微信 ID 列表
     * @param msg 要发送的消息内容
     */
    var onSendMessage: ((wxIds: List<String>, msg: String) -> Unit)? = null

    /**
     * 获取状态回调。
     *
     * 当 HTTP 客户端请求 /status 端点时调用。
     *
     * @return 包含机器人状态信息的映射，如 aiProvider、model、sendCount 等
     */
    var onGetStatus: (() -> Map<String, Any>)? = null

    /**
     * 获取绑定列表回调。
     *
     * 当 HTTP 客户端请求 /bindings 端点时调用。
     *
     * @return 已绑定的微信 ID 列表
     */
    var onGetBindings: (() -> List<String>)? = null

    /**
     * 获取订阅列表回调。
     *
     * 当 HTTP 客户端请求 /subscriptions 端点时调用。
     *
     * @return 订阅信息列表，每个订阅包含 name、url、subscriberCount 等
     */
    var onGetSubscriptions: (() -> List<Map<String, Any>>)? = null

    /**
     * 获取配置摘要回调。
     *
     * 当 HTTP 客户端请求 /config 端点时调用。
     *
     * @return 不含敏感信息的配置摘要映射
     */
    var onGetConfig: (() -> Map<String, String>)? = null

    /**
     * 尝试启动服务器，支持端口回退。
     *
     * 依次尝试 FALLBACK_PORTS 列表中的端口，直到找到一个可用的端口。
     * 如果所有端口都不可用，状态设为 [ServerStatus.ERROR] 并返回 -1。
     *
     * @return 实际使用的端口号，若启动失败则返回 -1
     */
    fun tryStart(): Int {
        status = ServerStatus.STARTING
        for (port in FALLBACK_PORTS) {
            try {
                // 如果当前端口不是目标端口，重新创建
                val server = if (port == 5888) this else HttpServer(port).apply {
                    onSendMessage = this@HttpServer.onSendMessage
                    onGetStatus = this@HttpServer.onGetStatus
                    onGetBindings = this@HttpServer.onGetBindings
                    onGetSubscriptions = this@HttpServer.onGetSubscriptions
                    onGetConfig = this@HttpServer.onGetConfig
                }
                server.start()
                actualPort = port
                status = ServerStatus.RUNNING
                return port
            } catch (e: IOException) {
                // 端口被占用，尝试下一个
                System.err.println("HttpServer: port $port is occupied, trying next...")
            } catch (e: Exception) {
                System.err.println("HttpServer: failed to start on port $port: ${e.message}")
            }
        }
        status = ServerStatus.ERROR
        actualPort = -1
        return -1
    }

    /**
     * 停止服务器
     */
    override fun stop() {
        super.stop()
        status = ServerStatus.STOPPED
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return try {
            // 路径遍历防护：拒绝包含 ".."、"/."、".\\" 等危险路径的请求
            if (containsPathTraversal(uri)) {
                return addCorsHeaders(
                    jsonResponse(Response.Status.BAD_REQUEST, -1, "invalid request path")
                )
            }

            // 检查速率限制
            val clientIp = session.headers.getOrDefault("x-forwarded-for", 
                session.headers.getOrDefault("remote-addr", "unknown"))
            if (!rateLimiter.tryAcquire(clientIp)) {
                return addCorsHeaders(
                    jsonResponse(Response.Status.TOO_MANY_REQUESTS, 429, "rate limit exceeded: max $RATE_LIMIT_MAX_REQUESTS requests per minute")
                )
            }

            // 请求体大小限制：拒绝超过 1MB 的请求
            if (session.method == Method.POST) {
                val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0
                if (contentLength > SecurityUtils.MAX_REQUEST_BODY_SIZE) {
                    return addCorsHeaders(
                        jsonResponse(Response.Status.BAD_REQUEST, -1, "request body too large: max ${SecurityUtils.MAX_REQUEST_BODY_SIZE / 1024}KB")
                    )
                }
            }

            val response = when {
                // POST /send 或 GET /send?wxIds=xxx&msg=xxx
                uri.contains("send") -> handleSend(session)

                // GET /status
                uri == "/status" -> handleStatus()

                // GET /bindings
                uri == "/bindings" -> handleBindings()

                // GET /subscriptions
                uri == "/subscriptions" -> handleSubscriptions()

                // GET /health
                uri == "/health" -> handleHealth()

                // GET /config
                uri == "/config" -> handleConfig()

                // GET /
                uri == "/" -> handleRoot()

                else -> jsonResponse(Response.Status.NOT_FOUND, -1, "unknown endpoint: $uri")
            }

            addCorsHeaders(response)
        } catch (e: Exception) {
            addCorsHeaders(
                jsonResponse(Response.Status.INTERNAL_ERROR, 2, "server error: ${e.message}")
            )
        }
    }

    // ===================== Handlers =====================

    /**
     * 处理 /send 请求。
     * 支持 GET 查询参数和 POST 表单/JSON 请求体。
     */
    private fun handleSend(session: IHTTPSession): Response {
        // POST 请求内容类型验证
        if (session.method == Method.POST) {
            val contentType = session.headers["content-type"] ?: ""
            if (contentType.isNotBlank() &&
                !contentType.contains("application/json") &&
                !contentType.contains("application/x-www-form-urlencoded") &&
                !contentType.contains("multipart/form-data")) {
                return jsonResponse(Response.Status.BAD_REQUEST, -1, "unsupported content type: $contentType")
            }
        }

        // 尝试从查询参数获取
        var wxIdsStr = session.parms["wxIds"] ?: ""
        var msg = session.parms["msg"] ?: ""

        // 对于 POST 请求，尝试从请求体中解析参数
        // parseBody 会将表单参数填充到 session.parms 中
        if (wxIdsStr.isBlank() || msg.isBlank()) {
            try {
                session.parseBody(hashMapOf())
                wxIdsStr = session.parms["wxIds"] ?: wxIdsStr
                msg = session.parms["msg"] ?: msg
            } catch (e: Exception) {
                // 忽略 body 解析错误，继续使用已有参数
            }
        }

        if (wxIdsStr.isBlank() || msg.isBlank()) {
            return jsonResponse(Response.Status.OK, 1, "missing required params: wxIds, msg")
        }

        // 消毒输入：安全处理 wxIds 和 msg
        wxIdsStr = SecurityUtils.sanitizeInput(wxIdsStr, 4096)
        msg = SecurityUtils.sanitizeInput(msg, 5000)

        val wxIds = parseWxIds(wxIdsStr)
        if (wxIds.isEmpty()) {
            return jsonResponse(Response.Status.OK, 1, "invalid wxIds format")
        }

        onSendMessage?.invoke(wxIds, msg)
        return jsonResponse(Response.Status.OK, 0, "success", mapOf("sentTo" to wxIds.size))
    }

    private fun handleStatus(): Response {
        val status = onGetStatus?.invoke() ?: mapOf("status" to "running")
        return jsonResponse(Response.Status.OK, 0, "ok", status)
    }

    private fun handleBindings(): Response {
        val bindings = onGetBindings?.invoke() ?: emptyList()
        return jsonResponse(Response.Status.OK, 0, "ok", mapOf("count" to bindings.size, "bindings" to bindings))
    }

    private fun handleSubscriptions(): Response {
        val subs = onGetSubscriptions?.invoke() ?: emptyList()
        return jsonResponse(Response.Status.OK, 0, "ok", mapOf("count" to subs.size, "subscriptions" to subs))
    }

    private fun handleHealth(): Response {
        return jsonResponse(Response.Status.OK, 0, "healthy", mapOf("uptime" to System.currentTimeMillis()))
    }

    /**
     * 处理 /config 请求：返回当前配置摘要（不含敏感信息）。
     */
    private fun handleConfig(): Response {
        val config = onGetConfig?.invoke() ?: mapOf("error" to "config callback not set")
        return jsonResponse(Response.Status.OK, 0, "ok", config)
    }

    private fun handleRoot(): Response {
        val html = """
            <html>
            <head><title>Aurora API</title></head>
            <body>
                <h1>Aurora - 微信AI自动回复机器人</h1>
                <h2>API 端点</h2>
                <ul>
                    <li>GET /health - 健康检查</li>
                    <li>GET /status - 机器人状态</li>
                    <li>GET /bindings - 绑定列表</li>
                    <li>GET /subscriptions - 订阅列表</li>
                    <li>POST /send?wxIds=xxx&msg=xxx - 发送消息</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    // ===================== 辅助方法 =====================

    /**
     * 检查 URI 是否包含路径遍历攻击模式。
     *
     * 检测 ".."、"/."、".\\"、URL 编码的 ".." (%2e%2e)、
     * 空字节注入 (%00) 等危险模式。
     *
     * @param uri 请求 URI
     * @return true 如果 URI 包含路径遍历模式
     */
    private fun containsPathTraversal(uri: String): Boolean {
        if (uri.isBlank()) return false

        val lowerUri = uri.lowercase()
        return when {
            // 基本路径遍历
            uri.contains("..") -> true
            uri.contains("./") || uri.contains("\\.") -> true
            // URL 编码的路径遍历
            lowerUri.contains("%2e%2e") || lowerUri.contains("%2e%2e%2f") -> true
            lowerUri.contains("%2e%2e/") || lowerUri.contains("%2e%2e%5c") -> true
            // 空字节注入
            uri.contains("\u0000") || lowerUri.contains("%00") -> true
            // 绝对路径绕过
            uri.startsWith("//") || uri.startsWith("\\\\") -> true
            else -> false
        }
    }

    private fun parseWxIds(str: String): List<String> {
        return try {
            // 支持格式:
            // 1. ['wxid_xxx','wxid_yyy'] (JSON数组)
            // 2. wxid_xxx,wxid_yyy (逗号分隔)
            // 3. wxid_xxx (单个)
            str.trim()
                .removePrefix("[").removeSuffix("]")
                .replace("'", "").replace("\"", "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 为响应添加 CORS 头，允许跨域访问。
     * 支持所有来源、常见 HTTP 方法和自定义头。
     */
    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun jsonResponse(status: Response.Status, code: Int, msg: String, extra: Map<String, Any> = emptyMap()): Response {
        val json = buildJsonResponse(code, msg, extra)
        return newFixedLengthResponse(status, "application/json", json)
    }

    private fun buildJsonResponse(code: Int, msg: String, extra: Map<String, Any> = emptyMap()): String {
        val sb = StringBuilder()
        sb.append("{\"code\":$code,\"msg\":\"${escapeJson(msg)}\"")
        for ((key, value) in extra) {
            sb.append(",\"$key\":")
            when (value) {
                is String -> sb.append("\"${escapeJson(value)}\"")
                is Number -> sb.append(value.toString())
                is Boolean -> sb.append(value.toString())
                is List<*> -> sb.append(toJsonArray(value))
                is Map<*, *> -> sb.append(toJsonObject(value as Map<String, Any>))
                else -> sb.append("\"${escapeJson(value.toString())}\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun toJsonArray(list: List<*>): String {
        if (list.isEmpty()) return "[]"
        return list.joinToString(",", "[", "]") { item ->
            when (item) {
                is String -> "\"${escapeJson(item)}\""
                is Number -> item.toString()
                is Boolean -> item.toString()
                is Map<*, *> -> toJsonObject(item as Map<String, Any>)
                else -> "\"${escapeJson(item.toString())}\""
            }
        }
    }

    private fun toJsonObject(map: Map<String, Any>): String {
        if (map.isEmpty()) return "{}"
        return map.entries.joinToString(",", "{", "}") { (key, value) ->
            val valStr = when (value) {
                is String -> "\"${escapeJson(value)}\""
                is Number -> value.toString()
                is Boolean -> value.toString()
                is List<*> -> toJsonArray(value)
                is Map<*, *> -> toJsonObject(value as Map<String, Any>)
                else -> "\"${escapeJson(value.toString())}\""
            }
            "\"${escapeJson(key)}\":$valStr"
        }
    }

    /**
     * 完整的 JSON 字符串转义，包括所有特殊字符和控制字符。
     * 符合 RFC 8259 标准。
     * 使用 LRU 缓存避免对常见字符串重复转义。
     */
    private fun escapeJson(str: String): String {
        // 检查 LRU 缓存
        escapeCache[str]?.let { return it }

        val sb = StringBuilder(str.length)
        for (ch in str) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '/' -> sb.append("\\/")
                in '\u0000'..'\u001F' -> {
                    // 其他控制字符用 \\uXXXX 格式
                    sb.append("\\u")
                    sb.append(String.format("%04x", ch.code))
                }
                else -> sb.append(ch)
            }
        }
        val result = sb.toString()

        // 仅缓存长度不超过 256 的字符串，避免缓存过大对象
        if (str.length <= 256) {
            synchronized(escapeCache) {
                escapeCache[str] = result
            }
        }
        return result
    }
}