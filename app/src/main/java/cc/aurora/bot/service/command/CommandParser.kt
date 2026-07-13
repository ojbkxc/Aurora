package cc.aurora.bot.service.command

/**
 * 指令类型枚举
 * 所有指令以 # 前缀开头，按前缀匹配
 * 越长的前缀应放在越前面，确保精确匹配优先
 */
enum class CommandType(val prefix: String) {
    // ===== AI 指定厂商回复 (精确前缀优先) =====
    AI_DEEPSEEK("#AI:DS:"),
    AI_QWEN("#AI:QW:"),
    AI_SILICON("#AI:SI:"),
    AI_ZHIPU("#AI:ZP:"),
    AI_IMAGE("#AII:"),
    AI_DEFAULT("#AI:"),
    AI_TEST("@测试"),

    // ===== 绑定管理 =====
    BIND_CHATROOM("#绑定聊天室"),
    UNBIND_CHATROOM("#解绑聊天室"),
    UNBIND_ALL("#解绑全部聊天室"),
    CURRENT_CHATROOM("#当前聊天室"),
    CHATROOM_ID("#聊天室ID"),

    // ===== AI 配置 =====
    SET_DEEPSEEK_KEY("#deepseekKey:"),
    SET_DEEPSEEK_MODEL("#deepseekModel:"),
    SET_QWEN_KEY("#qwenKey:"),
    SET_QWEN_MODEL("#qwenModel:"),
    SET_SILICON_KEY("#siliconKey:"),
    SET_SILICON_MODEL("#siliconModel:"),
    SET_ZHIPU_KEY("#zhipuKey:"),
    SET_ZHIPU_MODEL("#zhipuModel:"),
    SET_API("#API:"),
    SET_KEY("#KEY:"),
    SET_MODEL("#模型:"),
    SET_TRIGGER("#触发:"),
    SET_CACHE("#缓存:"),

    // ===== 订阅管理 =====
    SUBSCRIBE("#订阅:"),
    UNSUBSCRIBE("#取消订阅:"),
    UNSUBSCRIBE_ALL("#取消所有订阅"),
    CURRENT_SUB("#当前订阅"),

    // ===== 调教 =====
    TUNE("#调教"),
    DEFAULT_TUNE("#默认调教"),

    // ===== 群欢迎语 =====
    WELCOME("#进群欢迎语:"),
    QUERY_WELCOME("#查询本群欢迎语"),
    CANCEL_WELCOME("#取消本群欢迎语"),

    // ===== 开发者模式 =====
    DEV_MODE("#开发模式"),
    CLOSE_DEV("#关闭开发模式"),

    // ===== 艾特配置 =====
    NO_NEED_AT("#无需艾特"),
    NEED_AT("#需艾特"),

    // ===== 信息查询 =====
    BOT_INFO("#机器人信息"),
    API_INFO("#API说明"),

    // ===== 系统 =====
    RESTART("#重启机器人"),
    TEST("#测试"),

    // ===== 需求 =====
    DEMAND("#需求"),

    // ===== 艾特配置 =====
    AT_ME("#艾特"),

    // ===== 未识别 =====
    NONE("")
}

/**
 * 指令解析器
 * 解析以 # 开头的微信消息，提取指令类型和参数内容
 */
object CommandParser {

    /**
     * 解析消息为指令
     * @param message 原始消息 (应以 # 开头)
     * @return Pair<CommandType, String> 指令类型和参数内容
     */
    fun parse(message: String): Pair<CommandType, String> {
        val trimmed = message.trim()

        // 过滤非指令消息
        if (!trimmed.startsWith("#") && !trimmed.startsWith("@测试")) {
            return CommandType.NONE to trimmed
        }

        // 按优先级顺序匹配 (前缀越长越优先)
        // 先按 prefix 长度降序排序，确保精确匹配
        val sortedCommands = CommandType.values()
            .filter { it != CommandType.NONE }
            .sortedByDescending { it.prefix.length }

        for (cmdType in sortedCommands) {
            if (trimmed.startsWith(cmdType.prefix, ignoreCase = true)) {
                val content = trimmed.removePrefix(cmdType.prefix).trim()
                return cmdType to content
            }
        }

        // 未匹配到任何指令，可能是 # 开头的普通消息
        // 尝试作为 AI 默认回复处理
        return if (trimmed.startsWith("#AI:") || trimmed.startsWith("#ai:")) {
            // 去掉 #AI: 前缀，作为 AI 调用
            val content = trimmed.substringAfter(":").trim()
            CommandType.AI_DEFAULT to content
        } else {
            CommandType.NONE to trimmed
        }
    }

    /**
     * 判断消息是否为指令
     */
    fun isCommand(message: String): Boolean {
        return message.trimStart().startsWith("#") || message.trimStart().startsWith("@测试")
    }
}