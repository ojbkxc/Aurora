package cc.aurora.bot.service.wx.dto

import cc.aurora.bot.service.ai.AiMessage

// 群欢迎语
data class WxGroupWelcome(
    val chatroomId: String = "",
    val welcomWord: String = ""
)

// 订阅
data class WxSubcribeDTO(
    val name: String = "",
    val url: String = "",
    val wxIds: MutableSet<String> = mutableSetOf()
)

// 聊天室信息
data class ChatRoomInfoDTO(
    val chatroomId: String = "",
    val userInfoDTOSet: MutableSet<UserInfoDTO> = mutableSetOf()
)

// 用户信息
data class UserInfoDTO(
    val wxId: String = "",
    val nickName: String = "",
    val userSubcribeDTO: UserSubcribeDTO? = null
)

// 用户订阅关键词
data class UserSubcribeDTO(
    val keyWord: String = ""
)

// 多轮对话
data class MultipleChat(
    val chatId: String = "",
    val messages: MutableList<AiMessage> = mutableListOf()
)
