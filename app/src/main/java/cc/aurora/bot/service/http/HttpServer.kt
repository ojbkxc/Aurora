package cc.aurora.bot.service.http

import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * Aurora HTTP API 服务器
 * 基于 NanoHTTPD，提供外部 HTTP 接口用于：
 * 1. 发送微信消息 (POST /send)
 * 2. 查询机器人状态 (GET /status)
 * 3. 查询绑定列表 (GET /bindings)
 * 4. 查询订阅列表 (GET /subscriptions)
 * 5. 健康检查 (GET /health)
 *
 * 端口: 5888 (与 GodHook 兼容)
 */
class HttpServer(port: Int = 5888) : NanoHTTPD(port) {

    /** 发送消息回调: (wxIds, msg) -> Unit */
    var onSendMessage: ((wxIds: List<String>, msg: String) -> Unit)? = null

    /** 获取状态回调: () -> Map<String, Any> */
    var onGetStatus: (() -> Map<String, Any>)? = null

    /** 获取绑定列表回调: () -> List<String> */
    var onGetBindings: (() -> List<String>)? = null

    /** 获取订阅列表回调: () -> List<Map<String, Any>> */
    var onGetSubscriptions: (() -> List<Map<String, Any>>)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
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

                // GET /
                uri == "/" -> handleRoot()

                else -> jsonResponse(Response.Status.NOT_FOUND, -1, "unknown endpoint: $uri")
            }
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, 2, "server error: ${e.message}")
        }
    }

    // ===================== Handlers =====================

    private fun handleSend(session: IHTTPSession): Response {
        val params = session.parms
        val wxIdsStr = params["wxIds"] ?: ""
        val msg = params["msg"] ?: ""

        if (wxIdsStr.isBlank() || msg.isBlank()) {
            return jsonResponse(Response.Status.OK, 1, "missing required params: wxIds, msg")
        }

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

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}