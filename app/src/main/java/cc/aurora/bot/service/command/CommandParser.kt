package cc.aurora.bot.service.command

/**
 * 指令类型枚举。
 *
 * 所有指令以 # 前缀开头，按前缀匹配。
 * 越长的前缀应放在越前面，确保精确匹配优先。
 * 使用 [CommandParser.parse] 解析用户消息为对应的指令类型和参数。
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

    // ===== 对话管理 =====
    CLEAR_CONVERSATION("#清空对话"),
    EXPORT_CONVERSATION("#导出对话"),
    CONVERSATION_STATS("#对话统计"),

    // ===== 模块状态 =====
    MODULE_STATUS("#模块状态"),

    // ===== 定时消息 =====
    CANCEL_SCHEDULE("#取消定时"),
    LIST_SCHEDULES("#定时列表"),
    SCHEDULE_MESSAGE("#定时"),

    // ===== 未识别 =====
    NONE("")
}

/**
 * 指令解析器。
 *
 * 解析以 # 开头的微信消息，提取指令类型和参数内容。
 * 使用预排序的指令列表和精确前缀匹配，确保 O(n) 最坏情况下解析效率。
 *
 * 使用方式：
 * ```
 * val (cmdType, content) = CommandParser.parse("#AI:你好")
 * ```
 */
object CommandParser {

    /**
     * 预排序的指令列表（按前缀长度降序，确保长前缀优先匹配）。
     * 使用 lazy 延迟初始化，避免在类加载时立即计算。
     */
    private val sortedCommands: List<CommandType> by lazy {
        CommandType.values()
            .filter { it != CommandType.NONE }
            .sortedByDescending { it.prefix.length }
    }

    /**
     * 预计算的指令前缀 -> 指令类型映射，用于 O(1) 精确前缀查找。
     * 使用 lazy 延迟初始化。
     */
    private val commandMap: Map<String, CommandType> by lazy {
        val map = mutableMapOf<String, CommandType>()
        for (cmd in CommandType.values()) {
            if (cmd != CommandType.NONE && cmd.prefix.isNotEmpty()) {
                map[cmd.prefix] = cmd
            }
        }
        map
    }

    /**
     * 解析消息为指令。
     *
     * 先尝试 O(1) 精确前缀匹配，再按前缀长度降序尝试匹配。
     * 使用 `startsWith(ignoreCase=true)` 实现大小写不敏感匹配。
     *
     * @param message 原始消息（应以 # 开头或 @测试 开头）
     * @return Pair<CommandType, String> 指令类型和参数内容，未匹配时返回 [CommandType.NONE] 和原始消息
     */
    fun parse(message: String): Pair<CommandType, String> {
        val trimmed = message.trim()

        // 过滤非指令消息
        if (!trimmed.startsWith("#") && !trimmed.startsWith("@测试")) {
            return CommandType.NONE to trimmed
        }

        // 先尝试 O(1) 精确前缀匹配
        for (cmdType in sortedCommands) {
            if (trimmed.startsWith(cmdType.prefix, ignoreCase = true)) {
                // 使用 substring 代替 removePrefix，确保大小写不敏感匹配后正确截取
                // removePrefix 是大小写敏感的，而 startsWith(ignoreCase=true) 不是，
                // 两者不一致会导致解析错误。
                val content = if (trimmed.length >= cmdType.prefix.length) {
                    trimmed.substring(cmdType.prefix.length).trim()
                } else {
                    ""
                }
                return cmdType to content
            }
        }

        // 未匹配到任何指令，返回 NONE
        return CommandType.NONE to trimmed
    }

    /**
     * 判断消息是否为指令。
     *
     * 检查消息是否以 "#" 或 "@测试" 开头。
     *
     * @param message 要检查的消息
     * @return true 如果消息以指令前缀开头，否则 false
     */
    fun isCommand(message: String): Boolean {
        val trimmed = message.trimStart()
        return trimmed.startsWith("#") || trimmed.startsWith("@测试")
    }

    /**
     * 生成格式化的帮助文本，按类别分组列出所有可用指令。
     *
     * 返回人类可读的帮助字符串，适合在微信中直接发送。
     * 指令按类别分组：AI 对话、绑定管理、AI 配置、订阅管理、调教规则、
     * 群欢迎语、开发者、对话管理、模块管理、信息查询。
     *
     * @return 格式化的帮助文本字符串
     */
    fun formatHelp(): String {
        val sb = StringBuilder()

        // 类别定义: category -> list of (command, description)
        val categories = linkedMapOf(
            "AI 对话" to listOf(
                "#AI:<内容>" to "使用默认 AI 厂商回复",
                "#AI:DS:<内容>" to "使用 DeepSeek 回复",
                "#AI:QW:<内容>" to "使用通义千问回复",
                "#AI:SI:<内容>" to "使用硅基流动回复",
                "#AI:ZP:<内容>" to "使用智谱回复",
                "#AII:<描述>" to "使用智谱生成图片",
                "@测试" to "测试 AI 连接"
            ),
            "绑定管理" to listOf(
                "#绑定聊天室" to "绑定当前聊天室（仅开发者）",
                "#解绑聊天室" to "解绑当前聊天室（仅开发者）",
                "#解绑全部聊天室" to "解绑所有聊天室（仅开发者）",
                "#当前聊天室" to "查看当前聊天室 ID",
                "#聊天室ID" to "查看聊天室 ID"
            ),
            "AI 配置" to listOf(
                "#deepseekKey:<key>" to "设置 DeepSeek API Key",
                "#deepseekModel:<模型>" to "设置 DeepSeek 模型",
                "#qwenKey:<key>" to "设置通义千问 API Key",
                "#qwenModel:<模型>" to "设置通义千问模型",
                "#siliconKey:<key>" to "设置硅基流动 API Key",
                "#siliconModel:<模型>" to "设置硅基流动模型",
                "#zhipuKey:<key>" to "设置智谱 API Key",
                "#zhipuModel:<模型>" to "设置智谱模型",
                "#API:<URL>" to "设置自定义 API 地址",
                "#KEY:<key>" to "设置自定义 API Key",
                "#模型:<名称>" to "设置自定义模型",
                "#触发:<词>" to "设置触发词",
                "#缓存:<1-50>" to "设置对话缓存轮数"
            ),
            "订阅管理" to listOf(
                "#订阅:<名称> <URL>" to "订阅 RSS 推送",
                "#取消订阅:<名称>" to "取消指定订阅",
                "#取消所有订阅" to "取消所有订阅",
                "#当前订阅" to "查看当前订阅"
            ),
            "调教规则" to listOf(
                "#调教 <规则>" to "设置当前聊天室专属调教",
                "#默认调教 <规则>" to "设置全局默认调教"
            ),
            "群欢迎语" to listOf(
                "#进群欢迎语:<内容>" to "设置本群欢迎语",
                "#查询本群欢迎语" to "查看本群欢迎语",
                "#取消本群欢迎语" to "取消本群欢迎语"
            ),
            "开发者" to listOf(
                "#开发模式" to "开启开发者模式（仅开发者）",
                "#关闭开发模式" to "关闭开发者模式（仅开发者）",
                "#无需艾特" to "设置无需艾特模式",
                "#需艾特" to "设置需艾特模式",
                "#需求 <内容>" to "提交功能需求",
                "#重启机器人" to "重启机器人（仅开发者）"
            ),
            "对话管理" to listOf(
                "#清空对话" to "清除当前聊天室的对话历史",
                "#导出对话" to "导出当前聊天室的对话为 JSON",
                "#对话统计" to "查看当前对话的统计信息"
            ),
            "模块管理" to listOf(
                "#模块状态" to "查看各模块健康状态"
            ),
            "定时消息" to listOf(
                "#定时 <秒数> <消息>" to "在指定秒数后发送消息",
                "#取消定时 <id>" to "取消指定定时消息",
                "#定时列表" to "查看所有定时消息"
            ),
            "信息查询" to listOf(
                "#机器人信息" to "查看机器人运行状态",
                "#API说明" to "查看 API 接口说明",
                "#测试" to "测试指令"
            )
        )

        sb.appendLine("=== Aurora 指令帮助 ===")
        sb.appendLine("所有指令以 # 开头，支持大小写不敏感匹配")
        sb.appendLine()

        for ((category, commands) in categories) {
            sb.appendLine("【${category}】")
            for ((cmd, desc) in commands) {
                sb.appendLine("  $cmd")
                sb.appendLine("    -> $desc")
            }
            sb.appendLine()
        }

        sb.appendLine("提示: 更多帮助请查看 #API说明")
        return sb.toString().trimEnd()
    }
}