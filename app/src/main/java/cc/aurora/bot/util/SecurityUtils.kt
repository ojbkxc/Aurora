package cc.aurora.bot.util

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.net.InetAddress
import java.net.URI

/**
 * 安全工具类，提供输入消毒、API Key 掩码、URL 验证、wxId 验证和 JSON 验证功能。
 *
 * 所有方法均为静态方法，可在项目各处直接调用。
 *
 * 使用方式：
 * ```
 * val safe = SecurityUtils.sanitizeInput(userInput, 500)
 * val masked = SecurityUtils.maskApiKey("sk-abc123xyz789")
 * val valid = SecurityUtils.isValidUrl("https://api.example.com")
 * ```
 */
object SecurityUtils {

    // ===================== 常量 =====================

    /** 默认输入长度上限 */
    const val DEFAULT_MAX_INPUT_LENGTH = 10000

    /** wxId 最大长度 */
    private const val MAX_WX_ID_LENGTH = 256

    /** wxId 有效格式正则：wxid_ 开头后跟字母数字下划线，或以 @chatroom 结尾 */
    private val WX_ID_PATTERN = Regex("^(wxid_[a-zA-Z0-9_]+|[a-zA-Z0-9_]+@chatroom)$")

    /** API Key 最小长度（用于掩码） */
    private const val MIN_KEY_LENGTH_FOR_MASK = 8

    /** 请求体最大尺寸 (1MB) */
    const val MAX_REQUEST_BODY_SIZE = 1_048_576L // 1MB

    // ===================== 输入消毒 =====================

    /**
     * 消毒用户输入，使用默认长度上限 [DEFAULT_MAX_INPUT_LENGTH]。
     *
     * @param input 原始输入字符串
     * @return 消毒后的字符串
     */
    @JvmStatic
    fun sanitizeInput(input: String): String {
        return sanitizeInput(input, DEFAULT_MAX_INPUT_LENGTH)
    }

    /**
     * 消毒用户输入：去除控制字符、限制长度、去除首尾空白。
     *
     * 规则：
     * - 去除所有 ASCII 控制字符（0x00-0x1F，除了 \t、\n、\r）
     * - 去除 Unicode 双向控制字符（U+200E, U+200F, U+202A-U+202E）
     * - 去除零宽字符（U+200B-U+200D, U+FEFF）
     * - 限制最大长度
     * - 去除首尾空白
     *
     * @param input 原始输入字符串
     * @param maxLength 最大允许长度
     * @return 消毒后的字符串
     */
    @JvmStatic
    fun sanitizeInput(input: String, maxLength: Int): String {
        if (input.isEmpty()) return ""

        val sb = StringBuilder(minOf(input.length, maxLength))
        for (ch in input) {
            // 跳过达到最大长度
            if (sb.length >= maxLength) break

            when {
                // 允许普通 tab、换行、回车
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                // 拒绝其他 ASCII 控制字符 (0x00-0x1F, 0x7F DEL)
                ch.code in 0x00..0x1F -> { /* skip */ }
                ch.code == 0x7F -> { /* skip DEL */ }
                // 拒绝 Unicode 双向控制字符
                ch == '\u200E' || ch == '\u200F' -> { /* skip bidi */ }
                ch in '\u202A'..'\u202E' -> { /* skip bidi */ }
                // 拒绝零宽字符
                ch in '\u200B'..'\u200D' -> { /* skip zero-width */ }
                ch == '\uFEFF' -> { /* skip BOM */ }
                // 拒绝 Unicode 控制字符类别 (Cc, Cf, Cs, Co)
                Character.getType(ch).let { type ->
                    type == Character.CONTROL.toInt() ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.SURROGATE.toInt() ||
                    type == Character.UNASSIGNED.toInt()
                } -> { /* skip */ }
                // 允许所有其他字符
                else -> sb.append(ch)
            }
        }

        return sb.toString().trim()
    }

    // ===================== API Key 掩码 =====================

    /**
     * 掩码 API Key，仅显示前 4 和后 4 个字符，中间用星号替代。
     *
     * 规则：
     * - null 或空字符串返回 "(empty)"
     * - 长度小于 [MIN_KEY_LENGTH_FOR_MASK] 的返回 "(too short to mask)"
     * - 其余返回格式: "sk-a***z789"（前4 + *** + 后4）
     *
     * @param key API Key 字符串
     * @return 掩码后的字符串
     */
    @JvmStatic
    fun maskApiKey(key: String?): String {
        if (key.isNullOrEmpty()) return "(empty)"
        if (key.length < MIN_KEY_LENGTH_FOR_MASK) return "(too short to mask)"

        val showLen = 4
        if (key.length <= showLen * 2) {
            // 太短，但超过最小值，显示前2后2
            return "${key.take(2)}***${key.takeLast(2)}"
        }

        return "${key.take(showLen)}***${key.takeLast(showLen)}"
    }

    // ===================== URL 验证 =====================

    /**
     * 验证 URL 格式并防止 SSRF 攻击。
     *
     * 规则：
     * - 必须以 http:// 或 https:// 开头
     * - 域名不能是 localhost 或内网地址
     * - 不包含空格或控制字符
     * - 长度不超过 2048 字符
     *
     * @param url 要验证的 URL 字符串
     * @return true 如果 URL 格式有效且安全
     */
    @JvmStatic
    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.length > 2048) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        return try {
            val uri = URI(url)
            val host = uri.host ?: return false

            // 基本格式检查：必须有 scheme 和 host
            if (uri.scheme.isNullOrBlank() || host.isBlank()) return false

            // 检查是否包含空格（URL 中不应有空格）
            if (url.contains(" ")) return false

            // SSRF 防护：拒绝内网地址
            if (isPrivateOrLoopback(host)) return false

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查主机名是否为内网地址或回环地址。
     *
     * @param host 主机名或 IP 地址
     * @return true 如果是内网/回环地址
     */
    private fun isPrivateOrLoopback(host: String): Boolean {
        // 检查 localhost 变体
        val lowerHost = host.lowercase()
        if (lowerHost == "localhost" || lowerHost == "127.0.0.1" || lowerHost == "::1" ||
            lowerHost == "0.0.0.0" || lowerHost == "[::1]" || lowerHost == "[::]"
        ) {
            return true
        }

        // 检查 .local 域名
        if (lowerHost.endsWith(".local")) return true

        // 尝试解析为 IP 地址检查内网段
        return try {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress
        } catch (e: Exception) {
            // 如果无法解析，检查是否直接是内网 IP 格式
            val ipv4Pattern = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
            val match = ipv4Pattern.matchEntire(host) ?: return false
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }

            // 10.x.x.x, 172.16-31.x.x, 192.168.x.x
            parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 127)
        }
    }

    // ===================== wxId 验证 =====================

    /**
     * 验证 wxId 格式。
     *
     * 规则：
     * - 非空且不超过 [MAX_WX_ID_LENGTH] 字符
     * - 格式: wxid_ 开头后跟字母数字下划线，或以 @chatroom 结尾
     *
     * @param wxId 要验证的微信 ID
     * @return true 如果格式有效
     */
    @JvmStatic
    fun isValidWxId(wxId: String): Boolean {
        if (wxId.isBlank()) return false
        if (wxId.length > MAX_WX_ID_LENGTH) return false

        return WX_ID_PATTERN.matches(wxId.trim())
    }

    /**
     * 消毒 wxId：去除控制字符和空白，限制长度。
     *
     * @param wxId 原始 wxId 字符串
     * @return 消毒后的 wxId
     */
    @JvmStatic
    fun sanitizeWxId(wxId: String): String {
        return sanitizeInput(wxId, MAX_WX_ID_LENGTH)
    }

    // ===================== JSON 验证 =====================

    /**
     * 验证 JSON 字符串格式是否有效。
     *
     * 使用 Gson 的 JsonParser 进行解析，捕获解析异常。
     * 注意：此方法仅验证 JSON 格式，不验证内容的语义。
     *
     * @param json 要验证的 JSON 字符串
     * @return true 如果 JSON 格式有效
     */
    @JvmStatic
    fun isValidJson(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            JsonParser.parseString(json)
            true
        } catch (e: JsonSyntaxException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 消毒 JSON 输入：验证 JSON 格式并限制长度。
     *
     * @param json 原始 JSON 字符串
     * @param maxLength 最大允许长度
     * @return 消毒后的 JSON 字符串，如果 JSON 无效则返回空字符串
     */
    @JvmStatic
    fun sanitizeJson(json: String, maxLength: Int = DEFAULT_MAX_INPUT_LENGTH): String {
        if (json.isBlank()) return ""

        // 先限制长度再验证
        val truncated = if (json.length > maxLength) json.take(maxLength) else json

        return if (isValidJson(truncated)) truncated else ""
    }

    /**
     * 消毒 JSON 输入（默认最大长度）。
     *
     * @param json 原始 JSON 字符串
     * @return 消毒后的 JSON 字符串，如果 JSON 无效则返回空字符串
     */
    @JvmStatic
    fun sanitizeJson(json: String): String {
        return sanitizeJson(json, DEFAULT_MAX_INPUT_LENGTH)
    }
}