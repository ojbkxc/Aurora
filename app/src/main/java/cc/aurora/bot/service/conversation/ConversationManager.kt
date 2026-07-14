package cc.aurora.bot.service.conversation

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import cc.aurora.bot.service.ai.AiMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 对话管理器：管理多轮对话的缓存、清理、导出和导入。
 *
 * 功能：
 * - 管理每个 chat 的多轮对话历史
 * - 清除指定 chat 的对话历史
 * - 获取对话摘要（消息数、轮次数等）
 * - 导出对话为 JSON 格式
 * - 从 JSON 格式导入对话
 */
object ConversationManager {

    private val gson = Gson()

    /**
     * 总消息数上限，超过此限制时将触发 LRU 淘汰。
     */
    private const val MAX_TOTAL_MESSAGES = 1000

    /**
     * 对话历史内存缓存：chatId -> 消息列表
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private val conversationCache = ConcurrentHashMap<String, MutableList<AiMessage>>()

    /**
     * 访问顺序跟踪：chatId -> 上次访问时间戳
     * 用于 LRU 淘汰策略：当总消息数超限时，淘汰最近最少访问的 chat。
     */
    private val lruAccessTracker = ConcurrentHashMap<String, Long>()

    /**
     * 当前总消息数（近似值，用于快速判断是否需要淘汰）
     */
    @Volatile
    private var approximateTotalMessages: Int = 0

    /**
     * 记录 chat 的最近访问时间
     */
    private fun touchChat(chatId: String) {
        lruAccessTracker[chatId] = System.currentTimeMillis()
    }

    /**
     * 检查并执行 LRU 淘汰。
     * 当总消息数超过 MAX_TOTAL_MESSAGES 时，淘汰最近最少访问的 chat 的全部历史。
     * 如果淘汰后仍然超限，则继续淘汰，直到满足限制。
     */
    private fun evictIfNeeded() {
        while (approximateTotalMessages > MAX_TOTAL_MESSAGES && conversationCache.isNotEmpty()) {
            // 找到最近最少访问的 chat
            var lruChatId: String? = null
            var lruTime = Long.MAX_VALUE
            for ((chatId, time) in lruAccessTracker) {
                if (time < lruTime) {
                    lruTime = time
                    lruChatId = chatId
                }
            }

            val chatToEvict = lruChatId ?: conversationCache.keys().firstOrNull() ?: break

            // 移除该 chat 的所有消息
            val removed = conversationCache.remove(chatToEvict)
            lruAccessTracker.remove(chatToEvict)
            if (removed != null) {
                approximateTotalMessages -= removed.size
            }
        }
    }

    /**
     * 获取指定 chat 的对话历史。
     *
     * @param chatId 聊天标识
     * @return 消息列表（不可变副本）
     */
    fun getHistory(chatId: String): List<AiMessage> {
        touchChat(chatId)
        return conversationCache[chatId]?.toList() ?: emptyList()
    }

    /**
     * 添加消息到指定 chat 的对话历史。
     *
     * @param chatId 聊天标识
     * @param message 要添加的消息
     */
    fun addMessage(chatId: String, message: AiMessage) {
        touchChat(chatId)
        conversationCache.getOrPut(chatId) { mutableListOf() }.add(message)
        approximateTotalMessages++
        evictIfNeeded()
    }

    /**
     * 批量更新指定 chat 的对话历史。
     *
     * @param chatId 聊天标识
     * @param messages 新的消息列表
     */
    fun updateHistory(chatId: String, messages: List<AiMessage>) {
        touchChat(chatId)
        val oldSize = conversationCache[chatId]?.size ?: 0
        conversationCache[chatId] = messages.toMutableList()
        approximateTotalMessages = approximateTotalMessages - oldSize + messages.size
        evictIfNeeded()
    }

    /**
     * 清除指定 chat 的对话历史。
     *
     * @param chatId 聊天标识
     * @return 是否成功清除（true 表示存在历史并被清除，false 表示该 chat 没有历史记录）
     */
    fun clearHistory(chatId: String): Boolean {
        val removed = conversationCache.remove(chatId)
        lruAccessTracker.remove(chatId)
        if (removed != null) {
            approximateTotalMessages -= removed.size
        }
        return removed != null
    }

    /**
     * 清除所有对话历史。
     */
    fun clearAll() {
        conversationCache.clear()
        lruAccessTracker.clear()
        approximateTotalMessages = 0
    }

    /**
     * 获取对话统计信息。
     *
     * @param chatId 聊天标识
     * @return 包含消息数、轮次数、角色分布等统计信息的映射
     */
    fun getConversationStats(chatId: String): Map<String, Any> {
        val messages = conversationCache[chatId] ?: return mapOf(
            "chatId" to chatId,
            "messageCount" to 0,
            "turnCount" to 0,
            "userMessages" to 0,
            "assistantMessages" to 0,
            "hasHistory" to false
        )

        val userMessages = messages.count { it.role == "user" }
        val assistantMessages = messages.count { it.role == "assistant" }
        val turnCount = minOf(userMessages, assistantMessages)

        return mapOf(
            "chatId" to chatId,
            "messageCount" to messages.size,
            "turnCount" to turnCount,
            "userMessages" to userMessages,
            "assistantMessages" to assistantMessages,
            "hasHistory" to true
        )
    }

    /**
     * 获取对话摘要（人类可读的文本格式）。
     *
     * @param chatId 聊天标识
     * @return 格式化的对话摘要字符串
     */
    fun getConversationSummary(chatId: String): String {
        val stats = getConversationStats(chatId)
        if (stats["hasHistory"] == false) {
            return "当前对话无历史记录\nChat ID: $chatId"
        }

        val sb = StringBuilder()
        sb.appendLine("=== 对话摘要 ===")
        sb.appendLine("Chat ID: $chatId")
        sb.appendLine("消息总数: ${stats["messageCount"]}")
        sb.appendLine("对话轮次: ${stats["turnCount"]}")
        sb.appendLine("用户消息: ${stats["userMessages"]}")
        sb.appendLine("助手回复: ${stats["assistantMessages"]}")

        // 显示最近一条消息的预览
        val messages = getHistory(chatId)
        if (messages.isNotEmpty()) {
            val lastMsg = messages.last()
            sb.appendLine("最后消息: [${lastMsg.role}] ${lastMsg.content.take(50)}")
        }

        return sb.toString()
    }

    /**
     * 导出指定 chat 的对话为 JSON 字符串。
     *
     * @param chatId 聊天标识
     * @return JSON 格式的对话数据，包含元数据和时间戳
     */
    fun exportToJson(chatId: String): String {
        val messages = conversationCache[chatId] ?: emptyList<AiMessage>()
        val exportData = ConversationExport(
            exportVersion = 1,
            chatId = chatId,
            exportTime = System.currentTimeMillis(),
            exportTimeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            messageCount = messages.size,
            messages = messages
        )
        return try {
            gson.toJson(exportData)
        } catch (e: Exception) {
            """{"error": "Failed to export: ${e.message}"}"""
        }
    }

    /**
     * 从 JSON 字符串导入对话历史。
     *
     * @param json JSON 格式的对话数据
     * @return 导入结果，包含 chatId 和消息数量
     */
    fun importFromJson(json: String): ImportResult {
        return try {
            val exportData = gson.fromJson(json, ConversationExport::class.java)
            if (exportData == null || exportData.chatId.isBlank()) {
                return ImportResult(success = false, message = "JSON 数据格式无效：缺少 chatId")
            }
            if (exportData.messages.isEmpty()) {
                return ImportResult(success = false, message = "JSON 数据中无对话消息")
            }
            conversationCache[exportData.chatId] = exportData.messages.toMutableList()
            ImportResult(
                success = true,
                chatId = exportData.chatId,
                messageCount = exportData.messages.size,
                message = "成功导入 ${exportData.messages.size} 条消息到 ${exportData.chatId}"
            )
        } catch (e: Exception) {
            ImportResult(success = false, message = "导入失败: ${e.message}")
        }
    }

    /**
     * 获取所有有对话历史的 chat 列表
     */
    fun getAllChatIds(): Set<String> {
        return conversationCache.keys.toSet()
    }

    /**
     * 获取所有对话的总统计
     */
    fun getGlobalStats(): Map<String, Any> {
        val totalMessages = conversationCache.values.sumOf { it.size }
        val totalChats = conversationCache.size
        var totalUserMessages = 0
        var totalAssistantMessages = 0
        for (messages in conversationCache.values) {
            totalUserMessages += messages.count { it.role == "user" }
            totalAssistantMessages += messages.count { it.role == "assistant" }
        }
        return mapOf(
            "totalChats" to totalChats,
            "totalMessages" to totalMessages,
            "totalUserMessages" to totalUserMessages,
            "totalAssistantMessages" to totalAssistantMessages,
            "totalTurns" to minOf(totalUserMessages, totalAssistantMessages)
        )
    }
}

/**
 * 对话导出数据类，用于 JSON 序列化/反序列化。
 */
data class ConversationExport(
    @SerializedName("exportVersion") val exportVersion: Int,
    @SerializedName("chatId") val chatId: String,
    @SerializedName("exportTime") val exportTime: Long,
    @SerializedName("exportTimeFormatted") val exportTimeFormatted: String,
    @SerializedName("messageCount") val messageCount: Int,
    @SerializedName("messages") val messages: List<AiMessage>
)

/**
 * 导入结果数据类。
 */
data class ImportResult(
    @SerializedName("success") val success: Boolean,
    @SerializedName("chatId") val chatId: String = "",
    @SerializedName("messageCount") val messageCount: Int = 0,
    @SerializedName("message") val message: String
)