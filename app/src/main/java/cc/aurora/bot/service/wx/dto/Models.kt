package cc.aurora.bot.service.wx.dto

import com.google.gson.annotations.SerializedName
import cc.aurora.bot.service.ai.AiMessage

// 群欢迎语
data class WxGroupWelcome(
    @SerializedName("chatroomId") val chatroomId: String = "",
    @SerializedName("welcomWord") val welcomeWord: String = ""
)

// 订阅
// 注意: 类名 WxSubcribeDTO 是历史遗留拼写（应为 Subscribe），
// 为保持向后兼容性暂不重命名。
data class WxSubcribeDTO(
    @SerializedName("name") val name: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("wxIds") val wxIds: MutableSet<String> = mutableSetOf()
)

// 聊天室信息
data class ChatRoomInfoDTO(
    @SerializedName("chatroomId") val chatroomId: String = "",
    @SerializedName("userInfoDTOSet") val userInfoDTOSet: MutableSet<UserInfoDTO> = mutableSetOf()
)

// 用户信息
data class UserInfoDTO(
    @SerializedName("wxId") val wxId: String = "",
    @SerializedName("nickName") val nickName: String = "",
    @SerializedName("userSubcribeDTO") val userSubcribeDTO: UserSubcribeDTO? = null
)

// 用户订阅关键词
// 注意: 类名 UserSubcribeDTO 是历史遗留拼写（应为 Subscribe），
// 为保持向后兼容性暂不重命名。
data class UserSubcribeDTO(
    @SerializedName("keyWord") val keyWord: String = ""
)

// 多轮对话
data class MultipleChat(
    @SerializedName("chatId") val chatId: String = "",
    @SerializedName("messages") val messages: MutableList<AiMessage> = mutableListOf()
)

// ===================== 结构化结果数据类 =====================

/**
 * 指令执行结果
 * 用于统一指令处理后的结构化返回
 */
data class CommandResult(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("commandType") val commandType: String = "",
    @SerializedName("extra") val extra: Map<String, String> = emptyMap()
) {
    companion object {
        @JvmStatic
        fun ok(message: String, commandType: String = "", extra: Map<String, String> = emptyMap()): CommandResult {
            return CommandResult(success = true, message = message, commandType = commandType, extra = extra)
        }

        @JvmStatic
        fun fail(message: String, commandType: String = ""): CommandResult {
            return CommandResult(success = false, message = message, commandType = commandType)
        }
    }
}

/**
 * 健康检查结果
 * 用于 healthCheck() 的结构化返回
 */
data class HealthStatus(
    @SerializedName("healthy") val healthy: Boolean,
    @SerializedName("dexKitActive") val dexKitActive: Boolean = false,
    @SerializedName("httpServerRunning") val httpServerRunning: Boolean = false,
    @SerializedName("configManagerAccessible") val configManagerAccessible: Boolean = false,
    @SerializedName("aiKeyConfigured") val aiKeyConfigured: Boolean = false,
    @SerializedName("uptimeMs") val uptimeMs: Long = 0L,
    @SerializedName("messagesProcessed") val messagesProcessed: Long = 0,
    @SerializedName("aiCallsMade") val aiCallsMade: Long = 0,
    @SerializedName("commandsExecuted") val commandsExecuted: Long = 0,
    @SerializedName("errorsEncountered") val errorsEncountered: Long = 0,
    @SerializedName("details") val details: Map<String, String> = emptyMap()
)