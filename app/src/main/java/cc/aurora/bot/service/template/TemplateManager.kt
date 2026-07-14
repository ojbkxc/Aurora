package cc.aurora.bot.service.template

import java.util.concurrent.ConcurrentHashMap

/**
 * 模板管理器：管理预定义和自定义回复模板。
 *
 * 功能：
 * - 预置常见场景的回复模板（help、welcome、busy、error）
 * - 支持动态添加、删除和查询自定义模板
 * - 线程安全（使用 ConcurrentHashMap）
 *
 * 使用方式：
 * ```
 * val template = TemplateManager.getTemplate("help")
 * val welcomeMsg = TemplateManager.getTemplate("welcome").replace("{name}", "新成员")
 * TemplateManager.addTemplate("greeting", "你好，{name}！")
 * ```
 */
object TemplateManager {

    /**
     * 模板存储：templateName -> templateContent
     */
    private val templates = ConcurrentHashMap<String, String>()

    init {
        // 初始化内置模板
        registerBuiltInTemplates()
    }

    /**
     * 注册内置模板。
     * 内置模板在对象初始化时自动加载，但可以被 [addTemplate] 覆盖。
     */
    private fun registerBuiltInTemplates() {
        templates["help"] = """
            === Aurora 指令帮助 ===
            所有指令以 # 开头，支持大小写不敏感匹配

            【AI 对话】
              #AI:<内容> -> 使用默认 AI 厂商回复
              #AI:DS:<内容> -> 使用 DeepSeek 回复
              #AI:QW:<内容> -> 使用通义千问回复
              #AI:SI:<内容> -> 使用硅基流动回复
              #AI:ZP:<内容> -> 使用智谱回复
              #AII:<描述> -> 使用智谱生成图片
              @测试 -> 测试 AI 连接

            【绑定管理】
              #绑定聊天室 -> 绑定当前聊天室（仅开发者）
              #解绑聊天室 -> 解绑当前聊天室（仅开发者）
              #当前聊天室 -> 查看当前聊天室 ID

            【AI 配置】
              #deepseekKey:<key> -> 设置 DeepSeek API Key
              #qwenKey:<key> -> 设置通义千问 API Key
              #siliconKey:<key> -> 设置硅基流动 API Key
              #zhipuKey:<key> -> 设置智谱 API Key
              #API:<URL> -> 设置自定义 API 地址
              #KEY:<key> -> 设置自定义 API Key
              #模型:<名称> -> 设置自定义模型
              #触发:<词> -> 设置触发词
              #缓存:<1-50> -> 设置对话缓存轮数

            【订阅管理】
              #订阅:<名称> <URL> -> 订阅 RSS 推送
              #取消订阅:<名称> -> 取消指定订阅
              #当前订阅 -> 查看当前订阅

            【定时消息】（新增）
              #定时 <秒数> <消息> -> 设置定时消息
              #取消定时 <id> -> 取消指定定时
              #定时列表 -> 查看所有定时

            【其他】
              #机器人信息 -> 查看机器人运行状态
              #API说明 -> 查看 API 接口说明
              #调教 <规则> -> 设置聊天室专属调教
              #清空对话 -> 清除对话历史
              #对话统计 -> 查看对话统计
              #模块状态 -> 查看模块健康状态
        """.trimIndent()

        templates["welcome"] = """
            欢迎 {name} 加入本群！
            我是 Aurora AI 助手，可以回答各种问题。
            发送 #help 查看可用指令。
        """.trimIndent()

        templates["busy"] = """
            机器人当前繁忙，请稍后再试。
            如需帮助，请发送 #help 查看可用指令。
        """.trimIndent()

        templates["error"] = """
            抱歉，处理请求时发生了错误。
            错误信息：{error}
            请稍后重试或联系管理员。
        """.trimIndent()
    }

    /**
     * 根据名称获取模板内容。
     *
     * @param name 模板名称（大小写不敏感）
     * @return 模板内容字符串，如果模板不存在则返回空字符串
     */
    fun getTemplate(name: String): String {
        return templates[name.lowercase()] ?: ""
    }

    /**
     * 添加或更新自定义模板。
     *
     * 如果模板名称已存在（包括内置模板），则覆盖原有内容。
     * 模板名称自动转换为小写存储。
     *
     * @param name 模板名称（大小写不敏感）
     * @param content 模板内容，支持 {name} 占位符
     */
    fun addTemplate(name: String, content: String) {
        if (name.isBlank()) return
        templates[name.lowercase()] = content
    }

    /**
     * 移除指定模板。
     *
     * 可以移除内置模板，但内置模板不会在下次初始化时自动恢复
     * （因为 ConcurrentHashMap 的 remove 不会触发重新初始化）。
     * 如需恢复内置模板，使用 [resetToDefaults]。
     *
     * @param name 模板名称（大小写不敏感）
     * @return true 如果模板存在并被移除，false 如果模板不存在
     */
    fun removeTemplate(name: String): Boolean {
        return templates.remove(name.lowercase()) != null
    }

    /**
     * 列出所有模板名称和内容预览。
     *
     * @return 模板名称和内容预览（前 50 字符）的映射
     */
    fun listTemplates(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((name, content) in templates) {
            val preview = if (content.length > 50) {
                content.take(50).replace("\n", " ") + "..."
            } else {
                content.replace("\n", " ")
            }
            result[name] = preview
        }
        return result
    }

    /**
     * 获取所有模板名称列表。
     *
     * @return 所有已注册模板的名称集合
     */
    fun getTemplateNames(): Set<String> {
        return templates.keys.toSet()
    }

    /**
     * 检查指定模板是否存在。
     *
     * @param name 模板名称（大小写不敏感）
     * @return true 如果模板存在
     */
    fun hasTemplate(name: String): Boolean {
        return templates.containsKey(name.lowercase())
    }

    /**
     * 获取模板数量。
     *
     * @return 当前模板总数（含内置和自定义）
     */
    fun getTemplateCount(): Int {
        return templates.size
    }

    /**
     * 重置所有模板为内置默认值。
     *
     * 清除所有自定义模板并重新注册内置模板。
     */
    fun resetToDefaults() {
        templates.clear()
        registerBuiltInTemplates()
    }

    /**
     * 格式化模板：替换占位符为实际值。
     *
     * 占位符格式：{key}，例如 {name}、{error}。
     * 如果模板中不存在对应的占位符，则忽略该键值对。
     *
     * @param templateName 模板名称（大小写不敏感）
     * @param params 占位符键值对映射，key 为占位符名（不含花括号），value 为替换值
     * @return 格式化后的字符串，如果模板不存在则返回空字符串
     */
    fun formatTemplate(templateName: String, params: Map<String, String>): String {
        var content = getTemplate(templateName)
        if (content.isEmpty()) return ""

        for ((key, value) in params) {
            content = content.replace("{$key}", value)
        }
        return content
    }
}