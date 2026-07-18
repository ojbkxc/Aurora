package cc.aurora.bot.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.factory.classOf
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import cc.aurora.bot.lifecycle.ModuleLifecycle
import cc.aurora.bot.service.ai.AiMessage
import cc.aurora.bot.service.ai.AiProvider
import cc.aurora.bot.service.ai.AiService
import cc.aurora.bot.service.command.CommandParser
import cc.aurora.bot.service.command.CommandType
import cc.aurora.bot.service.config.ConfigManager
import cc.aurora.bot.service.conversation.ConversationManager
import cc.aurora.bot.service.http.HttpServer
import cc.aurora.bot.service.scheduler.SchedulerManager
import cc.aurora.bot.service.template.TemplateManager
import cc.aurora.bot.service.wx.dto.ChatRoomInfoDTO
import cc.aurora.bot.model.AppConfig
import cc.aurora.bot.service.wx.dto.MultipleChat
import cc.aurora.bot.service.wx.dto.UserInfoDTO
import cc.aurora.bot.service.wx.dto.WxGroupWelcome
import cc.aurora.bot.service.wx.dto.WxSubcribeDTO
import cc.aurora.bot.service.wx.dto.HealthStatus
import cc.aurora.bot.util.RateLimiter
import cc.aurora.bot.util.SecurityUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class WeChatHooker : IYukiHookXposedInit, ModuleLifecycle {

    companion object {
        const val TAG: String = "Aurora"
        const val SUBSCRIBE_PUSH_HOUR: Int = 9
        const val SUBSCRIBE_PUSH_MINUTE: Int = 30
        const val TIMER_CHECK_INTERVAL: Long = 20_000L       // 20秒
        const val CACHE_FLUSH_INTERVAL: Long = 1_200_000L     // 20分钟
        const val REPORT_INTERVAL: Long = 7_200_000L         // 2小时
        const val MAX_HISTORY_MESSAGES: Int = 20
        const val DEFAULT_PROMPT: String = "你是一个友好的微信AI助手，请用简洁自然的中文回复。"
        const val MAX_DEDUP_IDS: Int = 100

        // 启动时间，用于计算 uptime
        @JvmStatic
        val startTime: Long = System.currentTimeMillis()

        // DexKit 实例
        @Volatile
        @JvmStatic
        var dexKitBridge: DexKitBridge? = null

        /**
         * 由 HookEntry 调用，设置 DexKitBridge 实例
         */
        @JvmStatic
        fun setDexKitBridge(bridge: DexKitBridge) {
            dexKitBridge = bridge
            XposedBridge.log("$TAG: DexKitBridge set from HookEntry")
        }

        // ===================== 统计追踪 =====================

        /** 总消息处理数 */
        @JvmStatic
        val totalMessagesProcessed = AtomicLong(0)

        /** AI 调用总数 */
        @JvmStatic
        val totalAiCallsMade = AtomicLong(0)

        /** 指令执行总数 */
        @JvmStatic
        val totalCommandsExecuted = AtomicLong(0)

        /** 错误总数 */
        @JvmStatic
        val totalErrorsEncountered = AtomicLong(0)

        /**
         * 获取统计数据的快照
         */
        @JvmStatic
        fun getStatistics(): Map<String, Long> {
            return mapOf(
                "uptimeMs" to (System.currentTimeMillis() - startTime),
                "messagesProcessed" to totalMessagesProcessed.get(),
                "aiCallsMade" to totalAiCallsMade.get(),
                "commandsExecuted" to totalCommandsExecuted.get(),
                "errorsEncountered" to totalErrorsEncountered.get()
            )
        }

        // ===================== 消息去重 =====================

        /**
         * 最近处理的消息 ID 集合（LRU 风格的 LinkedHashMap）。
         * 用于避免重复处理同一条消息（如重复推送通知）。
         * 最多保留 MAX_DEDUP_IDS 条记录。
         */
        private val processedMessageIds = object : LinkedHashMap<String, Boolean>(MAX_DEDUP_IDS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > MAX_DEDUP_IDS
            }
        }

        /**
         * 检查消息是否已处理过。
         * 如果已处理返回 true，否则标记为已处理并返回 false。
         */
        @Synchronized
        @JvmStatic
        fun isMessageDuplicate(messageId: String): Boolean {
            return processedMessageIds.put(messageId, true) != null
        }

        /**
         * 清除去重记录
         */
        @Synchronized
        @JvmStatic
        fun clearDedupCache() {
            processedMessageIds.clear()
        }

        // ===================== DexKit 缓存 =====================

        /**
         * DexKit 搜索结果缓存对象。
         * 缓存通过 DexKit 查找到的类名，避免重复搜索。
         */
        object DexKitCache {
            /** 缓存: 搜索模式 -> 找到的类名集合 */
            private val cache = ConcurrentHashMap<String, Set<String>>()

            /**
             * 从缓存中获取 DexKit 搜索结果
             * @param key 搜索模式标识
             * @return 缓存的类名集合，如果未缓存则返回 null
             */
            fun get(key: String): Set<String>? {
                return cache[key]
            }

            /**
             * 将搜索结果存入缓存
             * @param key 搜索模式标识
             * @param classes 找到的类名集合
             */
            fun put(key: String, classes: Set<String>) {
                cache[key] = classes
            }

            /**
             * 检查指定 key 是否已缓存
             */
            fun contains(key: String): Boolean {
                return cache.containsKey(key)
            }

            /**
             * 清空所有缓存
             */
            fun clear() {
                cache.clear()
            }

            /**
             * 获取缓存大小
             */
            fun size(): Int = cache.size

            /**
             * 获取所有缓存的 key
             */
            fun keys(): Set<String> = cache.keys.toSet()
        }

        /**
         * 预热 DexKit 缓存：预先执行常用的 DexKit 搜索，将结果缓存起来。
         * 应在 DexKit 初始化完成后调用。
         */
        @JvmStatic
        fun warmUpDexKitCache() {
            val bridge = dexKitBridge ?: run {
                XposedBridge.log("$TAG: warmUpDexKitCache() - DexKit bridge not available, skipping")
                return
            }

            XposedBridge.log("$TAG: warmUpDexKitCache() - starting warm-up...")
            val startTime = System.currentTimeMillis()

            try {
                // 预热1: 搜索 doRevokeMsg 模式
                if (!DexKitCache.contains("doRevokeMsg")) {
                    try {
                        val revokeMethods = bridge.findMethod {
                            matcher {
                                addUsingString("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
                            }
                        }
                        DexKitCache.put("doRevokeMsg", revokeMethods.map { it.className }.toSet())
                        XposedBridge.log("$TAG: DexKitCache warmed up: doRevokeMsg (${revokeMethods.size} methods)")
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG: DexKitCache warm-up failed for doRevokeMsg: ${e.message}")
                    }
                }

                // 预热2: 搜索 msgId 字符串
                if (!DexKitCache.contains("msgId")) {
                    try {
                        val msgIdMethods = bridge.findMethod {
                            matcher {
                                addUsingString("msgId")
                            }
                        }
                        DexKitCache.put("msgId", msgIdMethods.map { it.className }.toSet())
                        XposedBridge.log("$TAG: DexKitCache warmed up: msgId (${msgIdMethods.size} methods)")
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG: DexKitCache warm-up failed for msgId: ${e.message}")
                    }
                }

                // 预热3: 搜索 talker 字符串
                if (!DexKitCache.contains("talker")) {
                    try {
                        val talkerMethods = bridge.findMethod {
                            matcher {
                                addUsingString("talker")
                            }
                        }
                        DexKitCache.put("talker", talkerMethods.map { it.className }.toSet())
                        XposedBridge.log("$TAG: DexKitCache warmed up: talker (${talkerMethods.size} methods)")
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG: DexKitCache warm-up failed for talker: ${e.message}")
                    }
                }

                // 预热4: 搜索 SendTextComponent 模式
                if (!DexKitCache.contains("SendTextComponent")) {
                    try {
                        val sendTextMethods = bridge.findMethod {
                            matcher {
                                addUsingString("MicroMsg.ChattingUI.SendTextComponent")
                            }
                        }
                        DexKitCache.put("SendTextComponent", sendTextMethods.map { it.className }.toSet())
                        XposedBridge.log("$TAG: DexKitCache warmed up: SendTextComponent (${sendTextMethods.size} methods)")
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG: DexKitCache warm-up failed for SendTextComponent: ${e.message}")
                    }
                }

                // 预热5: 搜索 sendMessage 字符串
                if (!DexKitCache.contains("sendMessage")) {
                    try {
                        val sendMsgMethods = bridge.findMethod {
                            matcher {
                                addUsingString("sendMessage")
                            }
                        }
                        DexKitCache.put("sendMessage", sendMsgMethods.map { it.className }.toSet())
                        XposedBridge.log("$TAG: DexKitCache warmed up: sendMessage (${sendMsgMethods.size} methods)")
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG: DexKitCache warm-up failed for sendMessage: ${e.message}")
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                XposedBridge.log("$TAG: DexKitCache warm-up completed in ${elapsed}ms, cache size=${DexKitCache.size()}")
            } catch (e: Exception) {
                XposedBridge.log("$TAG: warmUpDexKitCache() error: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var httpServer: HttpServer? = null
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var weChatPkgPath: String? = null

    // DexKit 查找到的微信内部类缓存
    // msgReceiveClass, msgReceiveMethod 已移至 MessageReceiver

    // 内存缓存: chatId -> 消息列表 (用于快速读写, 定时持久化)
    @Volatile
    private var chatCache: MutableMap<String, MutableList<AiMessage>> = mutableMapOf()

    // 记录当日是否已推送订阅
    @Volatile
    private var lastSubscribePushDate: String? = null

    // 速率限制: 每个 wxId 的最后一次 AI 调用时间戳
    private val lastAiCallTime = ConcurrentHashMap<String, Long>()
    // AI 调用计数: 每个 wxId 的 AI 调用次数
    private val aiCallCount = ConcurrentHashMap<String, Int>()
    // AI 调用最小间隔 (毫秒)
    private val minAiCallIntervalMs: Long = 2000L

    // AI 调用速率限制器（每用户每分钟 10 次）
    private val aiRateLimiter = RateLimiter.forAiCalls()

    // 消息队列: 待处理的 AI 请求
    private val aiRequestQueue = LinkedBlockingQueue<AiRequest>(50)
    // 队列处理是否已启动
    @Volatile
    private var queueProcessorStarted = false

    /**
     * AI 请求数据类，用于消息队列
     */
    data class AiRequest(
        val wxId: String,
        val message: String,
        val forceProvider: AiProvider?
    )

    // ===================== YukiHookAPI 入口 =====================

    override fun onHook() = encase {
        XposedBridge.log("$TAG: onHook() called")

        // 初始化 DexKit (从已缓存的 bridge 实例)
        initDexKit()

        // Hook Application.attachBaseContext 以获取全局 Context
        "android.app.Application".classOf().method {
            name = "attachBaseContext"
            emptyParam()
        }.hook {
            after {
                val app = this.instance as? Application ?: return@after
                appContext = app
                onApplicationAttached(app)
            }
        }
    }

    // ===================== DexKit 初始化 =====================

    /**
     * 初始化 DexKit: 用已缓存的 bridge 实例，如果没有则尝试从当前进程获取
     */
    private fun initDexKit() {
        try {
            if (dexKitBridge != null) {
                XposedBridge.log("$TAG: DexKit already initialized")
                return
            }

            // 尝试从 appContext 获取 APK 路径
            val ctx = appContext
            if (ctx != null) {
                try {
                    val apkPath = ctx.applicationInfo.sourceDir
                    if (apkPath != null && apkPath.isNotBlank()) {
                        XposedBridge.log("$TAG: initDexKit from sourceDir: $apkPath")
                        dexKitBridge = DexKitBridge.create(apkPath)
                        if (dexKitBridge != null) {
                            XposedBridge.log("$TAG: DexKit initialized from sourceDir")
                            return
                        }
                    }
                } catch (e: Exception) {
                    XposedBridge.log("$TAG: initDexKit from sourceDir failed: ${e.message}")
                }
            }

            XposedBridge.log("$TAG: DexKit not available, will use fallback methods")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: initDexKit error: ${e.message}")
        }
    }

    private fun onApplicationAttached(app: Application) {
        XposedBridge.log("$TAG: onApplicationAttached(), app=${app.javaClass.name}")

        // 检查 WeChat 版本兼容性
        try {
            checkWeChatVersion(app)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Version check failed: ${e.message}")
        }

        // Step 1: 获取微信 APK 路径, 供 DexKit 使用
        try {
            weChatPkgPath = app.applicationInfo.sourceDir
            XposedBridge.log("$TAG: weChatPkgPath=$weChatPkgPath")

            // 如果 DexKit 还没初始化，再次尝试
            if (dexKitBridge == null && weChatPkgPath != null) {
                try {
                    dexKitBridge = DexKitBridge.create(weChatPkgPath!!)
                    XposedBridge.log("$TAG: DexKit initialized on app attach")
                } catch (e: Exception) {
                    XposedBridge.log("$TAG: DexKit init on app attach failed: ${e.message}")
                    onError(e)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to get sourceDir: ${e.message}")
            onError(e)
        }

        // Step 2: 初始化 HTTP 服务
        try {
            startHttpServer()
        } catch (e: Exception) {
            XposedBridge.log("$TAG: HTTP server init failed: ${e.message}")
            onError(e)
        }

        // Step 2.5: 初始化 SchedulerManager 回调
        try {
            SchedulerManager.onMessageReady = { wxId, message ->
                sendReply(wxId, message)
            }
            XposedBridge.log("$TAG: SchedulerManager callback registered")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: SchedulerManager init failed: ${e.message}")
            onError(e)
        }

        // Step 3: 初始化消息拦截
        try {
            initMessageInterceptor()
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Message interceptor init failed: ${e.message}")
            onError(e)
        }

        // Step 4: 从 SP 恢复对话缓存到内存
        try {
            restoreChatCache()
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Chat cache restore failed: ${e.message}")
            onError(e)
        }

        // Step 5: 启动定时器
        try {
            startTimers()
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Timers start failed: ${e.message}")
            onError(e)
        }

        // Step 6: 启动队列处理器
        try {
            startQueueProcessor()
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Queue processor start failed: ${e.message}")
            onError(e)
        }

        // Step 7: 预热 DexKit 缓存
        scope.launch {
            try {
                warmUpDexKitCache()
            } catch (e: Exception) {
                XposedBridge.log("$TAG: warmUpDexKitCache failed in onApplicationAttached: ${e.message}")
                onError(e)
            }
        }

        // 通知生命周期：初始化完成
        try {
            onInitialized()
        } catch (e: Exception) {
            XposedBridge.log("$TAG: onInitialized callback failed: ${e.message}")
        }
    }

    /**
     * 检查 WeChat 版本兼容性。
     *
     * 通过 Android PackageManager 获取当前微信的版本信息，
     * 与已知的测试通过版本列表进行比对。对于未经过测试的版本，
     * 记录警告日志，但不阻止功能运行。
     *
     * 已知测试通过的版本列表：
     * - 8.0.50: 基本功能正常
     * - 8.0.49: 基本功能正常
     * - 8.0.48: 基本功能正常
     *
     * @param app 微信 Application 实例，用于获取 PackageInfo
     */
    private fun checkWeChatVersion(app: Application) {
        try {
            val packageInfo = app.packageManager.getPackageInfo(
                app.packageName, 0
            )
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            XposedBridge.log("$TAG: WeChat version detected: $versionName (code=$versionCode)")

            // 已知测试通过的版本前缀
            val testedVersions = listOf("8.0.50", "8.0.49", "8.0.48")

            val isTested = testedVersions.any { versionName.startsWith(it) }
            if (!isTested) {
                XposedBridge.log("$TAG: WARNING - WeChat version $versionName has NOT been tested with Aurora!")
                XposedBridge.log("$TAG: Aurora may still work, but some features might be unstable.")
                XposedBridge.log("$TAG: Tested versions: ${testedVersions.joinToString(", ")}")
            } else {
                XposedBridge.log("$TAG: WeChat version $versionName is in the tested list. Proceeding normally.")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: checkWeChatVersion failed: ${e.message}")
            throw e
        }
    }

    // ===================== 1. 消息接收 Hook =====================

    private fun initMessageInterceptor() {
        XposedBridge.log("$TAG: initMessageInterceptor() start")

        val classLoader = appContext?.classLoader ?: run {
            XposedBridge.log("$TAG: initMessageInterceptor() - classLoader is null")
            return
        }

        MessageReceiver.init(
            cl = classLoader,
            bridge = dexKitBridge,
            callback = MessageReceiver.MessageCallback { wxId, content ->
                processReceivedMessage(wxId, content)
            },
            logTag = TAG
        )
    }

    // ===================== 2. 消息处理入口 =====================

    private fun processReceivedMessage(wxId: String, content: String) {
        val ctx = appContext ?: return

        // 消息去重：基于 wxId + content 的前 100 个字符生成消息 ID
        val messageId = "${wxId}_${content.take(100)}"
        if (isMessageDuplicate(messageId)) {
            XposedBridge.log("$TAG: Duplicate message detected, skipping: $messageId")
            return
        }

        // 统计：消息处理数 +1
        totalMessagesProcessed.incrementAndGet()

        // 过滤 XML 格式的系统消息
        if (content.trim().startsWith("<") && content.trim().endsWith(">")) {
            XposedBridge.log("$TAG: Skip XML system message")
            return
        }

        // 检查是否是 # 指令或 @测试 指令
        val trimmed = content.trim()
        if (trimmed.startsWith("#") || trimmed.startsWith("@测试")) {
            handleCommand(wxId, trimmed)
            return
        }

        // 检查 wxId 是否在绑定列表中
        val boundIds = ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS)

        // 检查是否是开发者 ID (无需触发词)
        val devIds = ConfigManager.getStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS)
        if (devIds.contains(wxId)) {
            callAI(wxId, content, null)
            return
        }

        // 检查 wxId 是否是已绑定的群聊或好友
        if (boundIds.contains(wxId)) {
            // 检查触发词
            val config = AppConfig.fromConfigManager(ctx)
            val triggerWord = config.triggerWord
            if (triggerWord.isBlank() || content.contains(triggerWord)) {
                callAI(wxId, content, null)
                return
            }

            // 检查是否有艾特机器人的情况 (@xxx)
            // 微信群消息 @ 格式: "@wxid_xxx\u0000" 或昵称
            if (content.contains("@") && content.contains("\u0000")) {
                // 被 @ 的消息, 去掉 @ 前缀后调用AI
                val cleanContent = content.substringAfter("\u0000").trim()
                if (cleanContent.isNotBlank()) {
                    callAI(wxId, cleanContent, null)
                }
                return
            }
        }

        // 未绑定的 ID 不处理
        XposedBridge.log("$TAG: wxId=$wxId not bound, skip")
    }

    // ===================== 3. 指令处理 =====================

    private fun handleCommand(wxId: String, message: String) {
        val ctx = appContext ?: return
        val (cmdType, cmdContent) = CommandParser.parse(message)

        // 消毒命令内容，防止注入
        val safeCmdContent = SecurityUtils.sanitizeInput(cmdContent, 5000)

        XposedBridge.log("$TAG: handleCommand wxId=${SecurityUtils.sanitizeWxId(wxId)} cmd=$cmdType content=${safeCmdContent.take(50)}")

        // 统计：指令执行数 +1
        totalCommandsExecuted.incrementAndGet()

        // 指令鉴权: 绑定/解绑等管理指令仅开发者可用
        val devIds = ConfigManager.getStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS)
        val isDev = devIds.contains(wxId)

        when (cmdType) {
            CommandType.BIND_CHATROOM -> {
                if (!isDev) {
                    sendReply(wxId, "仅开发者可执行此操作")
                    return
                }
                val ids = ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS).toMutableSet()
                ids.add(wxId)
                ConfigManager.saveStringSet(ctx, ConfigManager.KEY_WX_IDS, ids)
                sendReply(wxId, "已绑定: $wxId")
            }

            CommandType.UNBIND_CHATROOM -> {
                if (!isDev) {
                    sendReply(wxId, "仅开发者可执行此操作")
                    return
                }
                val ids = ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS).toMutableSet()
                ids.remove(wxId)
                ConfigManager.saveStringSet(ctx, ConfigManager.KEY_WX_IDS, ids)
                sendReply(wxId, "已解绑: $wxId")
            }

            CommandType.UNBIND_ALL -> {
                if (!isDev) {
                    sendReply(wxId, "仅开发者可执行此操作")
                    return
                }
                ConfigManager.saveStringSet(ctx, ConfigManager.KEY_WX_IDS, emptySet())
                sendReply(wxId, "已解绑所有聊天室")
            }

            CommandType.CURRENT_CHATROOM -> {
                sendReply(wxId, "当前聊天室ID: $wxId")
            }

            CommandType.CHATROOM_ID -> {
                sendReply(wxId, "聊天室ID: $wxId")
            }

            // ===== AI 指定厂商回复 =====
            CommandType.AI_DEFAULT -> {
                if (safeCmdContent.isNotBlank()) {
                    callAI(wxId, safeCmdContent, null)
                }
            }
            CommandType.AI_DEEPSEEK -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_DEEPSEEK_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置 DeepSeek Key, 请先发送 #deepseekKey <your_key>")
                    return
                }
                if (safeCmdContent.isNotBlank()) {
                    callAI(wxId, safeCmdContent, AiProvider.DEEPSEEK)
                }
            }
            CommandType.AI_QWEN -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_QWEN_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置通义千问 Key, 请先发送 #qwenKey <your_key>")
                    return
                }
                if (safeCmdContent.isNotBlank()) {
                    callAI(wxId, safeCmdContent, AiProvider.QWEN)
                }
            }
            CommandType.AI_SILICON -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_SILICON_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置硅基流动 Key, 请先发送 #siliconKey <your_key>")
                    return
                }
                if (safeCmdContent.isNotBlank()) {
                    callAI(wxId, safeCmdContent, AiProvider.SILICON)
                }
            }
            CommandType.AI_ZHIPU -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置智谱 Key, 请先发送 #zhipuKey <your_key>")
                    return
                }
                if (safeCmdContent.isNotBlank()) {
                    callAI(wxId, safeCmdContent, AiProvider.ZHIPU)
                }
            }
            CommandType.AI_IMAGE -> {
                // 智谱图片生成: 使用智谱的 CogView 模型
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置智谱 Key, 请先发送 #zhipuKey <your_key>")
                    return
                }
                if (safeCmdContent.isNotBlank()) {
                    callAIImage(wxId, safeCmdContent)
                }
            }
            CommandType.AI_TEST -> {
                // 测试 AI 连接: 发送 @测试 触发
                val provider = getCurrentAiProvider()
                if (provider == null) {
                    sendReply(wxId, "未配置 AI 厂商，请先设置 API Key")
                    return
                }
                sendReply(wxId, "正在测试 ${provider.displayName} 连接...")
                callAI(wxId, "请回复'连接测试成功'这几个字", null)
            }

            // ===== 设置配置指令 =====
            CommandType.SET_DEEPSEEK_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #deepseekKey:<API Key>\n示例: #deepseekKey:sk-xxxxx")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_KEY, safeCmdContent)
                XposedBridge.log("$TAG: DeepSeek Key set: ${SecurityUtils.maskApiKey(safeCmdContent)}")
                sendReply(wxId, "DeepSeek Key 已设置")
            }
            CommandType.SET_DEEPSEEK_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #deepseekModel:<模型名称>\n示例: #deepseekModel:deepseek-chat")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_MODEL, safeCmdContent)
                sendReply(wxId, "DeepSeek 模型已设置为: $safeCmdContent")
            }
            CommandType.SET_QWEN_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #qwenKey:<API Key>\n示例: #qwenKey:sk-xxxxx")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_KEY, safeCmdContent)
                XposedBridge.log("$TAG: Qwen Key set: ${SecurityUtils.maskApiKey(safeCmdContent)}")
                sendReply(wxId, "通义千问 Key 已设置")
            }
            CommandType.SET_QWEN_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #qwenModel:<模型名称>\n示例: #qwenModel:qwen-turbo")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_MODEL, safeCmdContent)
                sendReply(wxId, "通义千问模型已设置为: $safeCmdContent")
            }
            CommandType.SET_SILICON_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #siliconKey:<API Key>\n示例: #siliconKey:sk-xxxxx")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_KEY, safeCmdContent)
                XposedBridge.log("$TAG: Silicon Key set: ${SecurityUtils.maskApiKey(safeCmdContent)}")
                sendReply(wxId, "硅基流动 Key 已设置")
            }
            CommandType.SET_SILICON_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #siliconModel:<模型名称>\n示例: #siliconModel:Qwen/Qwen2.5-7B-Instruct")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_MODEL, safeCmdContent)
                sendReply(wxId, "硅基流动模型已设置为: $safeCmdContent")
            }
            CommandType.SET_ZHIPU_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #zhipuKey:<API Key>\n示例: #zhipuKey:sk-xxxxx")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_KEY, safeCmdContent)
                XposedBridge.log("$TAG: ZhiPu Key set: ${SecurityUtils.maskApiKey(safeCmdContent)}")
                sendReply(wxId, "智谱 Key 已设置")
            }
            CommandType.SET_ZHIPU_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #zhipuModel:<模型名称>\n示例: #zhipuModel:glm-4-flash")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_MODEL, safeCmdContent)
                sendReply(wxId, "智谱模型已设置为: $safeCmdContent")
            }
            CommandType.SET_API -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #API:<URL>\n示例: #API:https://api.openai.com/v1")
                    return
                }
                if (!safeCmdContent.startsWith("http://") && !safeCmdContent.startsWith("https://")) {
                    sendReply(wxId, "API地址必须以 http:// 或 https:// 开头\n格式: #API:<URL>")
                    return
                }
                // SSRF 防护：验证 URL 安全
                if (!SecurityUtils.isValidUrl(safeCmdContent)) {
                    sendReply(wxId, "API地址无效或不允许使用内网地址\n格式: #API:<URL>")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_API, safeCmdContent)
                sendReply(wxId, "自定义API地址已设置为: $safeCmdContent")
            }
            CommandType.SET_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #KEY:<API Key>\n示例: #KEY:sk-xxxxx")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_KEY, safeCmdContent)
                XposedBridge.log("$TAG: Custom Key set: ${SecurityUtils.maskApiKey(safeCmdContent)}")
                sendReply(wxId, "自定义API Key已设置")
            }
            CommandType.SET_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #模型:<模型名称>\n示例: #模型:gpt-4o")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_MODEL, safeCmdContent)
                sendReply(wxId, "自定义模型已设置为: $safeCmdContent")
            }
            CommandType.SET_TRIGGER -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #触发:<触发词>\n示例: #触发:小助手")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_TRIGGER_WORD, safeCmdContent)
                sendReply(wxId, "触发词已设置为: $safeCmdContent")
            }
            CommandType.SET_CACHE -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #缓存:<1-50>\n示例: #缓存:10")
                    return
                }
                val times = safeCmdContent.toIntOrNull()
                if (times == null || times < 1 || times > 50) {
                    sendReply(wxId, "缓存轮数必须在 1-50 之间，你输入的是: $safeCmdContent\n格式: #缓存:<1-50>")
                    return
                }
                ConfigManager.saveInt(ctx, ConfigManager.KEY_CACHE_TIMES, times)
                sendReply(wxId, "缓存轮数已设置为: $times")
            }

            // ===== 需求指令 =====
            CommandType.DEMAND -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #需求 <内容>\n示例: #需求 增加每日新闻推送功能")
                    return
                }
                // 需求记录到日志（消毒后）
                XposedBridge.log("$TAG: DEMAND from ${SecurityUtils.sanitizeWxId(wxId)}: $safeCmdContent")
                sendReply(wxId, "需求已记录: $safeCmdContent")
            }
            CommandType.AT_ME -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                // 艾特配置暂存
                sendReply(wxId, "艾特配置已更新")
            }
            CommandType.WELCOME -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                val welcomes = ConfigManager.getJson<MutableList<WxGroupWelcome>>(
                    ctx, ConfigManager.KEY_WELCOME, mutableListOf()
                )
                if (safeCmdContent.isBlank()) {
                    // 空内容则取消该群的欢迎语
                    val removed = welcomes.removeAll { it.chatroomId == wxId }
                    ConfigManager.saveJson(ctx, ConfigManager.KEY_WELCOME, welcomes)
                    sendReply(wxId, if (removed) "已取消本群欢迎语" else "本群未设置欢迎语，无需取消")
                    return
                }
                // 查找是否已有该群的欢迎语, 有则更新, 无则新增
                val existing = welcomes.find { it.chatroomId == wxId }
                if (existing != null) {
                    existing.welcomeWord = safeCmdContent
                } else {
                    welcomes.add(WxGroupWelcome(chatroomId = wxId, welcomeWord = safeCmdContent))
                }
                ConfigManager.saveJson(ctx, ConfigManager.KEY_WELCOME, welcomes)
                sendReply(wxId, "群 $wxId 欢迎语已设置为: $safeCmdContent")
            }
            CommandType.QUERY_WELCOME -> {
                val welcomes = ConfigManager.getJson<MutableList<WxGroupWelcome>>(
                    ctx, ConfigManager.KEY_WELCOME, mutableListOf()
                )
                val welcome = welcomes.find { it.chatroomId == wxId }
                if (welcome != null && welcome.welcomeWord.isNotBlank()) {
                    sendReply(wxId, "本群欢迎语: ${welcome.welcomeWord}")
                } else {
                    sendReply(wxId, "本群未设置欢迎语")
                }
            }
            CommandType.CANCEL_WELCOME -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                val welcomes = ConfigManager.getJson<MutableList<WxGroupWelcome>>(
                    ctx, ConfigManager.KEY_WELCOME, mutableListOf()
                )
                val removed = welcomes.removeAll { it.chatroomId == wxId }
                ConfigManager.saveJson(ctx, ConfigManager.KEY_WELCOME, welcomes)
                sendReply(wxId, if (removed) "已取消本群欢迎语" else "本群未设置欢迎语，无需取消")
            }
            CommandType.NO_NEED_AT -> {
                sendReply(wxId, "无需艾特模式已开启")
            }
            CommandType.NEED_AT -> {
                sendReply(wxId, "需艾特模式已开启")
            }

            // ===== 订阅管理 =====
            CommandType.SUBSCRIBE -> {
                val parts = safeCmdContent.split(" ", limit = 2)
                if (parts.size < 2) {
                    sendReply(wxId, "格式: #订阅:<名称> <URL>\n示例: #订阅:今日新闻 https://example.com/rss")
                    return
                }
                val subName = parts[0].trim()
                val subUrl = parts[1].trim()
                if (subName.isBlank()) {
                    sendReply(wxId, "订阅名称不能为空\n格式: #订阅:<名称> <URL>")
                    return
                }
                if (subUrl.isBlank() || (!subUrl.startsWith("http://") && !subUrl.startsWith("https://"))) {
                    sendReply(wxId, "订阅URL必须以 http:// 或 https:// 开头\n格式: #订阅:<名称> <URL>")
                    return
                }
                // SSRF 防护：验证订阅 URL 安全
                if (!SecurityUtils.isValidUrl(subUrl)) {
                    sendReply(wxId, "订阅URL无效或不允许使用内网地址\n格式: #订阅:<名称> <URL>")
                    return
                }
                val subscribes = ConfigManager.getJson<MutableList<WxSubcribeDTO>>(
                    ctx, ConfigManager.KEY_SUBSCRIBE, mutableListOf()
                )
                var target = subscribes.find { it.name == subName }
                if (target == null) {
                    target = WxSubcribeDTO(name = subName, url = subUrl, wxIds = mutableSetOf())
                    subscribes.add(target)
                } else {
                    target.url = subUrl
                }
                target.wxIds.add(wxId)
                ConfigManager.saveJson(ctx, ConfigManager.KEY_SUBSCRIBE, subscribes)
                sendReply(wxId, "已订阅: $subName")
            }
            CommandType.UNSUBSCRIBE -> {
                val subName = safeCmdContent.trim()
                if (subName.isBlank()) {
                    sendReply(wxId, "格式: #取消订阅:<名称>\n示例: #取消订阅:今日新闻")
                    return
                }
                val subscribes = ConfigManager.getJson<MutableList<WxSubcribeDTO>>(
                    ctx, ConfigManager.KEY_SUBSCRIBE, mutableListOf()
                )
                val target = subscribes.find { it.name == subName }
                if (target != null) {
                    target.wxIds.remove(wxId)
                    if (target.wxIds.isEmpty()) {
                        subscribes.remove(target)
                    }
                    ConfigManager.saveJson(ctx, ConfigManager.KEY_SUBSCRIBE, subscribes)
                    sendReply(wxId, "已取消订阅: $subName")
                } else {
                    sendReply(wxId, "未找到订阅: $subName")
                }
            }
            CommandType.UNSUBSCRIBE_ALL -> {
                val subscribes = ConfigManager.getJson<MutableList<WxSubcribeDTO>>(
                    ctx, ConfigManager.KEY_SUBSCRIBE, mutableListOf()
                )
                subscribes.forEach { it.wxIds.remove(wxId) }
                val cleaned = subscribes.filter { it.wxIds.isNotEmpty() }.toMutableList()
                ConfigManager.saveJson(ctx, ConfigManager.KEY_SUBSCRIBE, cleaned)
                sendReply(wxId, "已取消所有订阅")
            }
            CommandType.CURRENT_SUB -> {
                val subscribes = ConfigManager.getJson<MutableList<WxSubcribeDTO>>(
                    ctx, ConfigManager.KEY_SUBSCRIBE, mutableListOf()
                )
                val mySubs = subscribes.filter { it.wxIds.contains(wxId) }
                if (mySubs.isEmpty()) {
                    sendReply(wxId, "当前无订阅")
                } else {
                    val sb = StringBuilder("当前订阅:\n")
                    mySubs.forEach { sb.append("- ${it.name}: ${it.url}\n") }
                    sendReply(wxId, sb.toString().trimEnd())
                }
            }

            // ===== 调教 =====
            CommandType.TUNE -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    sendReply(wxId, "格式: #调教 <规则内容>\n示例: #调教 你是一个专业的客服助手，请用礼貌的语气回复")
                    return
                }
                saveTunePrompt(wxId, safeCmdContent)
                sendReply(wxId, "当前聊天室调教规则已保存: $safeCmdContent")
            }
            CommandType.DEFAULT_TUNE -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                if (safeCmdContent.isBlank()) {
                    // 空内容则重置为默认 prompt
                    ConfigManager.saveString(ctx, ConfigManager.KEY_PROMPT, DEFAULT_PROMPT)
                    sendReply(wxId, "默认调教规则已重置为: $DEFAULT_PROMPT")
                    return
                }
                ConfigManager.saveString(ctx, ConfigManager.KEY_PROMPT, safeCmdContent)
                sendReply(wxId, "默认调教规则已更新为: $safeCmdContent")
            }

            // ===== 机器人信息 =====
            CommandType.BOT_INFO -> {
                val config = AppConfig.fromConfigManager(ctx)
                val triggerWord = config.triggerWord
                val boundIds = ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS)
                val devIdsSet = ConfigManager.getStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS)
                val sendCount = ConfigManager.getInt(ctx, ConfigManager.KEY_SEND_COUNT, 0)

                val info = StringBuilder()
                info.append("=== Aurora 机器人信息 ===\n")
                info.append("AI厂商: ${config.getActiveAiProvider()?.displayName ?: "未配置"}\n")
                info.append("模型: ${config.getModel(config.getActiveAiProvider() ?: AiProvider.DEEPSEEK).ifBlank { "默认" }}\n")
                info.append("触发词: $triggerWord\n")
                info.append("绑定数量: ${boundIds.size}\n")
                info.append("开发者数量: ${devIdsSet.size}\n")
                info.append("累计发送: $sendCount 条\n")
                info.append("状态: 运行中")
                sendReply(wxId, info.toString())
            }
            CommandType.API_INFO -> {
                val sb = StringBuilder("=== 指令帮助 ===\n")
                sb.append("管理: #绑定聊天室 #解绑聊天室 #解绑全部聊天室\n")
                sb.append("AI: #AI: #AI:DS: #AI:QW: #AI:SI: #AI:ZP: #AII:\n")
                sb.append("配置: #deepseekKey #deepseekModel #qwenKey #qwenModel\n")
                sb.append("      #siliconKey #siliconModel #zhipuKey #zhipuModel\n")
                sb.append("      #API: #KEY: #模型: #触发: #缓存:\n")
                sb.append("订阅: #订阅:<名称> <URL> #取消订阅: #取消所有订阅 #当前订阅\n")
                sb.append("调教: #调教 <规则> #默认调教 <规则>\n")
                sb.append("其他: #机器人信息 #进群欢迎语: #查询本群欢迎语 #取消本群欢迎语\n")
                sb.append("      #重启机器人")
                sendReply(wxId, sb.toString())
            }

            // ===== 开发者模式 =====
            CommandType.DEV_MODE -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                val ids = ConfigManager.getStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS).toMutableSet()
                ids.add(wxId)
                ConfigManager.saveStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS, ids)
                sendReply(wxId, "已开启开发者模式")
            }
            CommandType.CLOSE_DEV -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                val ids = ConfigManager.getStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS).toMutableSet()
                ids.remove(wxId)
                ConfigManager.saveStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS, ids)
                sendReply(wxId, "已关闭开发者模式")
            }

            // ===== 重启 =====
            CommandType.RESTART -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                sendReply(wxId, "机器人正在重启...")
                restartWeChat()
            }

            // ===== 对话管理 =====
            CommandType.CLEAR_CONVERSATION -> {
                ConversationManager.clearHistory(wxId)
                // 同步清除内存缓存 chatCache（无论 ConversationManager 是否有数据）
                val hadData = synchronized(chatCache) {
                    chatCache.remove(wxId) != null
                }
                if (hadData) {
                    sendReply(wxId, "对话历史已清空")
                } else {
                    sendReply(wxId, "当前对话无历史记录，无需清空")
                }
            }

            CommandType.EXPORT_CONVERSATION -> {
                val json = ConversationManager.exportToJson(wxId)
                if (json.contains("\"error\"")) {
                    sendReply(wxId, "导出失败: 当前对话无历史记录")
                } else {
                    // 截断过长的 JSON 以适配微信消息长度限制
                    val truncatedJson = if (json.length > 450) {
                        json.take(450) + "...(截断)"
                    } else {
                        json
                    }
                    sendReply(wxId, "对话导出:\n$truncatedJson")
                }
            }

            CommandType.CONVERSATION_STATS -> {
                val summary = ConversationManager.getConversationSummary(wxId)
                sendReply(wxId, summary)
            }

            // ===== 模块状态 =====
            CommandType.MODULE_STATUS -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                val status = healthCheck()
                val sb = StringBuilder()
                sb.appendLine("=== 模块健康状态 ===")
                sb.appendLine("整体状态: ${if (status.healthy) "正常" else "异常"}")
                sb.appendLine("DexKit: ${if (status.dexKitActive) "激活" else "未激活"}")
                sb.appendLine("HTTP服务器: ${if (status.httpServerRunning) "运行中" else "已停止"}")
                sb.appendLine("ConfigManager: ${if (status.configManagerAccessible) "可访问" else "不可访问"}")
                sb.appendLine("AI Key: ${if (status.aiKeyConfigured) "已配置" else "未配置"}")
                sb.appendLine("运行时长: ${status.uptimeMs / 1000 / 60} 分钟")
                sb.appendLine("消息处理: ${status.messagesProcessed}")
                sb.appendLine("AI调用: ${status.aiCallsMade}")
                sb.appendLine("指令执行: ${status.commandsExecuted}")
                sb.appendLine("错误数: ${status.errorsEncountered}")
                sendReply(wxId, sb.toString())
            }

            CommandType.NONE -> {
                // 不是指令, 交给正常消息处理
                XposedBridge.log("$TAG: Unrecognized command: $message")
            }

            CommandType.TEST -> {
                sendReply(wxId, "Aurora 机器人运行正常！\n发送 #机器人信息 查看详情\n发送 #API说明 查看所有指令")
            }

            // ===== 定时消息 =====
            CommandType.SCHEDULE_MESSAGE -> {
                // 格式: #定时 <秒数> <消息>
                val parts = safeCmdContent.split(" ", limit = 2)
                if (parts.size < 2) {
                    sendReply(wxId, "格式: #定时 <秒数> <消息>\n示例: #定时 60 你好，这是一条定时消息")
                    return
                }
                val seconds = parts[0].trim().toLongOrNull()
                if (seconds == null || seconds <= 0) {
                    sendReply(wxId, "秒数必须为正整数，你输入的是: ${parts[0]}\n格式: #定时 <秒数> <消息>")
                    return
                }
                if (seconds > 86400) {
                    sendReply(wxId, "定时最大支持 86400 秒（24 小时），你输入的是: $seconds 秒")
                    return
                }
                val messageContent = parts[1].trim()
                if (messageContent.isBlank()) {
                    sendReply(wxId, "消息内容不能为空\n格式: #定时 <秒数> <消息>")
                    return
                }
                val delayMs = seconds * 1000L
                val scheduleId = SchedulerManager.scheduleMessage(wxId, messageContent, delayMs)
                if (scheduleId > 0) {
                    sendReply(wxId, "定时消息已设置，ID: $scheduleId\n将在 ${seconds} 秒后发送: $messageContent")
                } else {
                    sendReply(wxId, "定时消息设置失败，请检查参数是否有效")
                }
            }

            CommandType.CANCEL_SCHEDULE -> {
                // 格式: #取消定时 <id>
                val scheduleIdStr = safeCmdContent.trim()
                if (scheduleIdStr.isBlank()) {
                    sendReply(wxId, "格式: #取消定时 <id>\n示例: #取消定时 1\n发送 #定时列表 查看所有定时")
                    return
                }
                val scheduleId = scheduleIdStr.toLongOrNull()
                if (scheduleId == null) {
                    sendReply(wxId, "ID 必须为数字，你输入的是: $scheduleIdStr\n格式: #取消定时 <id>")
                    return
                }
                val cancelled = SchedulerManager.cancelSchedule(scheduleId)
                if (cancelled) {
                    sendReply(wxId, "已取消定时消息，ID: $scheduleId")
                } else {
                    sendReply(wxId, "未找到定时消息，ID: $scheduleId\n发送 #定时列表 查看所有定时")
                }
            }

            CommandType.LIST_SCHEDULES -> {
                val schedules = SchedulerManager.listSchedules()
                if (schedules.isEmpty()) {
                    sendReply(wxId, "当前没有定时消息\n使用 #定时 <秒数> <消息> 添加定时")
                } else {
                    val sb = StringBuilder("=== 定时消息列表 ===\n")
                    sb.appendLine("共 ${schedules.size} 个定时任务")
                    sb.appendLine()
                    for (s in schedules) {
                        val type = if (s.recurring) "周期" else "一次性"
                        val remainMs = s.triggerAtMs - System.currentTimeMillis()
                        val remainStr = if (s.recurring) {
                            "间隔: ${s.intervalMs / 1000}秒"
                        } else if (remainMs > 0) {
                            "剩余: ${remainMs / 1000}秒"
                        } else {
                            "即将触发"
                        }
                        val msgPreview = if (s.message.length > 30) s.message.take(30) + "..." else s.message
                        sb.appendLine("ID: ${s.id} | $type | $remainStr")
                        sb.appendLine("  目标: ${s.wxId}")
                        sb.appendLine("  内容: $msgPreview")
                        sb.appendLine()
                    }
                    sendReply(wxId, sb.toString().trimEnd())
                }
            }
        }
    }

    // ===================== 4. AI 调用 =====================

    /**
     * 将 AI 请求加入消息队列，由队列处理器按序执行
     */
    private fun callAI(wxId: String, message: String, forceProvider: AiProvider?) {
        // 启动队列处理器 (仅一次)
        if (!queueProcessorStarted) {
            startQueueProcessor()
        }

        val request = AiRequest(wxId, message, forceProvider)
        val enqueued = aiRequestQueue.offer(request)
        if (!enqueued) {
            XposedBridge.log("$TAG: AI request queue full, dropping request from $wxId")
            sendReply(wxId, "AI 请求队列已满，请稍后再试")
        } else {
            XposedBridge.log("$TAG: AI request enqueued for $wxId, queue size=${aiRequestQueue.size}")
        }
    }

    /**
     * 启动队列处理器：在协程中按序处理队列中的 AI 请求
     */
    private fun startQueueProcessor() {
        synchronized(this) {
            if (queueProcessorStarted) return
            queueProcessorStarted = true
        }
        XposedBridge.log("$TAG: Starting AI request queue processor")

        scope.launch {
            while (true) {
                try {
                    val request = aiRequestQueue.take() // 阻塞直到有请求
                    processAiRequest(request)
                } catch (e: InterruptedException) {
                    XposedBridge.log("$TAG: Queue processor interrupted")
                    break
                } catch (e: Exception) {
                    XposedBridge.log("$TAG: Queue processor error: ${e.message}")
                }
            }
        }
    }

    /**
     * 处理单个 AI 请求 (带速率限制)
     */
    private suspend fun processAiRequest(request: AiRequest) {
        val wxId = request.wxId
        val message = request.message
        val forceProvider = request.forceProvider

        try {
            val ctx = appContext ?: return

            // 速率限制检查
            val now = System.currentTimeMillis()
            val lastTime = lastAiCallTime[wxId] ?: 0L
            val elapsed = now - lastTime
            if (elapsed < minAiCallIntervalMs) {
                val waitMs = minAiCallIntervalMs - elapsed
                XposedBridge.log("$TAG: Rate limiting $wxId, waiting ${waitMs}ms")
                delay(waitMs)
            }

            // 新速率限制器检查（每用户每分钟 10 次 AI 调用）
            if (!aiRateLimiter.tryAcquire(wxId)) {
                XposedBridge.log("$TAG: AI rate limit exceeded for $wxId")
                sendReply(wxId, TemplateManager.getTemplate("busy"))
                return
            }

            // 更新速率限制时间戳
            lastAiCallTime[wxId] = System.currentTimeMillis()

            // 更新调用计数
            aiCallCount.compute(wxId) { _, count -> (count ?: 0) + 1 }

            // 统计：AI 调用数 +1
            totalAiCallsMade.incrementAndGet()

            // 确定使用的 AI 厂商
            val provider = forceProvider ?: getCurrentAiProvider()
            if (provider == null) {
                XposedBridge.log("$TAG: No AI provider configured")
                sendReply(wxId, "未配置任何 AI 厂商，请先设置 API Key")
                return
            }

            val apiKey = if (forceProvider != null) {
                getAiKeyForProvider(forceProvider)
            } else {
                getCurrentAiKey()
            }
            val model = if (forceProvider != null) {
                getModelForProvider(forceProvider)
            } else {
                getCurrentModel()
            }
            val customBaseUrl = if (provider == AiProvider.CUSTOM) {
                ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_API)
            } else {
                null
            }

            // 检查 API Key
            if (apiKey.isBlank()) {
                sendReply(wxId, "未配置 ${provider.displayName} 的 API Key, 请先通过 #指令 设置")
                return
            }

            // 构建多轮对话消息列表
            val chatMessages = buildChatMessages(wxId, message)

            XposedBridge.log("$TAG: callAI wxId=${SecurityUtils.sanitizeWxId(wxId)} provider=${provider.displayName} model=$model msgCount=${chatMessages.size} apiKey=${SecurityUtils.maskApiKey(apiKey)}")

            // 调用 AI API
            val result = AiService.chat(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = chatMessages,
                customBaseUrl = customBaseUrl
            )

            // 提取回复内容
            val reply = result.choices.firstOrNull()?.message?.content
                ?: run {
                    sendReply(wxId, "AI 未返回有效内容")
                    return
                }

            XposedBridge.log("$TAG: AI reply for ${SecurityUtils.sanitizeWxId(wxId)}: ${SecurityUtils.sanitizeInput(reply, 200).take(100)}")

            // 更新对话缓存
            val updatedMessages = chatMessages.toMutableList()
            updatedMessages.add(AiMessage(role = "assistant", content = reply))
            updateChatCache(wxId, updatedMessages)

            // 发送回复
            sendReply(wxId, reply)

        } catch (e: Exception) {
            XposedBridge.log("$TAG: callAI error for ${SecurityUtils.sanitizeWxId(wxId)}: ${e.message}")
            e.printStackTrace()
            // 统计：错误数 +1
            totalErrorsEncountered.incrementAndGet()
            sendReply(wxId, "AI 调用失败: ${e.message?.take(100)}")
        }
    }

    /**
     * 调用 AI 图片生成 (智谱 CogView)
     */
    private fun callAIImage(wxId: String, prompt: String) {
        scope.launch {
            try {
                val ctx = appContext ?: return@launch
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置智谱 Key")
                    return@launch
                }

                // 使用智谱的图片生成模型
                val messages = listOf(
                    AiMessage(role = "user", content = prompt)
                )

                // 使用自定义 URL 指向智谱的图片生成接口
                val result = AiService.chat(
                    provider = AiProvider.ZHIPU,
                    apiKey = key,
                    model = "cogview-3-flash",
                    messages = messages
                )

                val reply = result.choices.firstOrNull()?.message?.content ?: "图片生成失败"
                sendReply(wxId, reply)
            } catch (e: Exception) {
                XposedBridge.log("$TAG: callAIImage error: ${e.message}")
                sendReply(wxId, "图片生成失败: ${e.message?.take(100)}")
            }
        }
    }

    // ===================== 5. 发送微信消息 =====================

    private fun sendWxMessage(wxId: String, message: String) {
        try {
            val ctx = appContext ?: return
            val classLoader = ctx.classLoader
            val sent = MessageSender.send(
                classLoader = classLoader,
                dexKitBridge = dexKitBridge,
                wxId = wxId,
                message = message,
                tag = TAG
            )
            if (!sent) {
                XposedBridge.log("$TAG: sendWxMessage failed - could not find send method for $wxId")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: sendWxMessage error: ${e.message}")
            e.printStackTrace()
        }
    }

    // Message sending strategies have been extracted to MessageSender.kt


    // ===================== 6. HTTP 服务 =====================

    private fun startHttpServer() {
        try {
            httpServer = HttpServer(5888).apply {
                // 发送消息回调
                onSendMessage = { wxIds, msg ->
                    for (wxId in wxIds) {
                        sendWxMessage(wxId, msg)
                    }
                }
                // 状态回调
                onGetStatus = {
                    val ctx = appContext
                    if (ctx == null) {
                        mapOf("status" to "running")
                    } else {
                        val provider = getCurrentAiProvider()
                        mapOf(
                            "status" to "running",
                            "aiProvider" to (provider?.displayName ?: "未配置"),
                            "model" to getCurrentModel().ifBlank { "default" },
                            "sendCount" to ConfigManager.getInt(ctx, ConfigManager.KEY_SEND_COUNT, 0),
                            "bindingCount" to ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS).size
                        )
                    }
                }
                // 绑定列表回调
                onGetBindings = {
                    val ctx = appContext
                    if (ctx == null) emptyList()
                    else ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS).toList()
                }
                // 订阅列表回调
                onGetSubscriptions = {
                    val ctx = appContext
                    if (ctx == null) emptyList()
                    else {
                        val subscribes = ConfigManager.getJson<MutableList<WxSubcribeDTO>>(
                            ctx, ConfigManager.KEY_SUBSCRIBE, mutableListOf()
                        )
                        subscribes.map { sub ->
                            mapOf<String, Any>(
                                "name" to sub.name,
                                "url" to sub.url,
                                "subscriberCount" to sub.wxIds.size,
                                "wxIds" to sub.wxIds.toList()
                            )
                        }
                    }
                }
                // 配置摘要回调
                onGetConfig = {
                    val ctx = appContext
                    if (ctx == null) emptyMap()
                    else ConfigManager.getConfigSummary(ctx)
                }
            }
            val port = httpServer?.tryStart() ?: -1
            if (port > 0) {
                XposedBridge.log("$TAG: HTTP server started on port $port")
            } else {
                XposedBridge.log("$TAG: HTTP server failed to start on any port (tried 5888, 5889, 5890)")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: HTTP server start failed: ${e.message}")
        }
    }

    // ===================== 7. 定时器 =====================

    private fun startTimers() {
        XposedBridge.log("$TAG: startTimers()")

        // ---- 订阅推送定时器: 每20秒检查一次, 到达09:30时推送 ----
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    checkAndPushSubscribe()
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: subscribe timer error: ${e.message}")
                }
                mainHandler.postDelayed(this, TIMER_CHECK_INTERVAL)
            }
        }, TIMER_CHECK_INTERVAL)

        // ---- 缓存刷新定时器: 每20分钟持久化发送计数 ----
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    flushCacheToSP()
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: cache flush timer error: ${e.message}")
                }
                mainHandler.postDelayed(this, CACHE_FLUSH_INTERVAL)
            }
        }, CACHE_FLUSH_INTERVAL)

        // ---- 数据上报定时器: 每2小时 (可选功能) ----
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    reportUsage()
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: report timer error: ${e.message}")
                }
                mainHandler.postDelayed(this, REPORT_INTERVAL)
            }
        }, REPORT_INTERVAL)

        XposedBridge.log("$TAG: startTimers() done")
    }

    /**
     * 检查是否到达订阅推送时间 (09:30), 如果是则推送
     */
    private fun checkAndPushSubscribe() {
        val ctx = appContext ?: return
        val now = Date()
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = sdf.format(now)
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)

        // 检查是否到达推送时间
        if (timeStr < "${String.format("%02d", SUBSCRIBE_PUSH_HOUR)}:${String.format("%02d", SUBSCRIBE_PUSH_MINUTE)}") {
            return
        }

        // 检查今天是否已推送
        if (lastSubscribePushDate == todayStr) return
        lastSubscribePushDate = todayStr

        // 获取所有订阅
        val subscribes = ConfigManager.getJson<MutableList<WxSubcribeDTO>>(
            ctx, ConfigManager.KEY_SUBSCRIBE, mutableListOf()
        )

        if (subscribes.isEmpty()) return

        XposedBridge.log("$TAG: Pushing subscribe messages at $timeStr")

        scope.launch {
            for (subscribe in subscribes) {
                if (subscribe.url.isBlank() || subscribe.wxIds.isEmpty()) continue

                try {
                    // 获取订阅内容 (从 URL 抓取摘要)
                    val content = fetchSubscribeContent(subscribe.url)
                    if (content.isBlank()) continue

                    // 构建推送消息
                    val pushMsg = StringBuilder()
                    pushMsg.append("--- ${subscribe.name} 每日推送 ---\n\n")
                    pushMsg.append(content)

                    // 向所有订阅者发送
                    val msgStr = pushMsg.toString()
                    for (wxId in subscribe.wxIds) {
                        sendWxMessage(wxId, msgStr)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: Subscribe push error for ${subscribe.name}: ${e.message}")
                }
            }
        }
    }

    /**
     * 从 URL 获取订阅内容摘要 (简化实现)
     */
    private fun fetchSubscribeContent(url: String): String {
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            val response = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
            // 使用 use {} 确保 Response 被正确关闭，防止资源泄漏
            response.use { resp ->
                val body = resp.body?.string() ?: return ""
                // 简化: 取前500字符作为摘要
                body.take(500).replace(Regex("<[^>]*>"), "").trim()
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: fetchSubscribeContent error: ${e.message}")
            ""
        }
    }

    /**
     * 将内存缓存持久化到 SP
     */
    private fun flushCacheToSP() {
        val ctx = appContext ?: return
        try {
            // 持久化对话缓存
            val allChats = chatCache.map { (chatId, msgs) ->
                MultipleChat(chatId = chatId, messages = msgs.toMutableList())
            }
            ConfigManager.saveJson(ctx, ConfigManager.KEY_CHAT_MESSAGES, allChats)
            XposedBridge.log("$TAG: Cache flushed to SP, ${allChats.size} chats")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: flushCacheToSP error: ${e.message}")
        }
    }

    /**
     * 数据上报 (可选功能, 目前仅记录日志)
     */
    private fun reportUsage() {
        val ctx = appContext ?: return
        val sendCount = ConfigManager.getInt(ctx, ConfigManager.KEY_SEND_COUNT, 0)
        XposedBridge.log("$TAG: Usage report - total sent: $sendCount")
    }

    // ===================== 8. 辅助方法 =====================

    // ===================== AI Provider 缓存 =====================

    /**
     * 缓存的 AI 厂商结果，避免频繁读取 ConfigManager（SharedPreferences）。
     * 缓存有效期 30 秒，到期后自动刷新。
     */
    @Volatile
    private var cachedAiProvider: AiProvider? = null
    @Volatile
    private var cachedAiProviderTimestamp: Long = 0L
    private val AI_PROVIDER_CACHE_TTL_MS: Long = 30_000L // 30 秒

    /**
     * 获取当前配置的 AI 厂商（带缓存）
     * 按优先级 DeepSeek -> Qwen -> Silicon -> Zhipu -> Custom 检查
     * 如果没有任何厂商配置了有效的 Key，返回 null 并记录警告
     */
    private fun getCurrentAiProvider(): AiProvider? {
        val now = System.currentTimeMillis()
        val cached = cachedAiProvider
        if (cached != null && (now - cachedAiProviderTimestamp) < AI_PROVIDER_CACHE_TTL_MS) {
            return cached
        }

        val ctx = appContext ?: return null

        // 使用 AppConfig 模型统一获取
        val config = AppConfig.fromConfigManager(ctx)
        val result = config.getActiveAiProvider()

        cachedAiProvider = result
        cachedAiProviderTimestamp = now

        if (result == null) {
            XposedBridge.log("$TAG: WARNING - No AI provider configured! Please set at least one API Key.")
        }

        return result
    }

    /**
     * 获取当前 AI API Key
     */
    private fun getCurrentAiKey(): String {
        val ctx = appContext ?: return ""
        val provider = getCurrentAiProvider() ?: return ""
        return AppConfig.fromConfigManager(ctx).getApiKey(provider)
    }

    /**
     * 获取当前模型名
     */
    private fun getCurrentModel(): String {
        val ctx = appContext ?: return ""
        val provider = getCurrentAiProvider() ?: return ""
        return AppConfig.fromConfigManager(ctx).getModel(provider)
    }

    /**
     * 根据指定的 AiProvider 获取对应的 API Key
     */
    private fun getAiKeyForProvider(provider: AiProvider): String {
        val ctx = appContext ?: return ""
        return AppConfig.fromConfigManager(ctx).getApiKey(provider)
    }

    /**
     * 根据指定的 AiProvider 获取对应的模型名
     */
    private fun getModelForProvider(provider: AiProvider): String {
        val ctx = appContext ?: return provider.defaultModel
        return AppConfig.fromConfigManager(ctx).getModel(provider)
    }

    /**
     * 获取调教规则 (system prompt)
     * 优先返回当前聊天室的专属调教, 其次返回全局默认调教
     */
    private fun getSystemPrompt(chatId: String): String {
        val ctx = appContext ?: return DEFAULT_PROMPT

        // 检查当前聊天室是否有专属调教
        val chatRoomInfos = ConfigManager.getJson<MutableSet<ChatRoomInfoDTO>>(
            ctx, ConfigManager.KEY_CHAT_ROOM_INFO, mutableSetOf()
        )
        for (roomInfo in chatRoomInfos) {
            if (roomInfo.chatroomId == chatId) {
                for (userInfo in roomInfo.userInfoDTOSet) {
                    userInfo.userSubcribeDTO?.keyWord?.let {
                        if (it.isNotBlank()) return it
                    }
                }
            }
        }

        // 返回全局默认 prompt
        return AppConfig.fromConfigManager(ctx).systemPrompt
    }

    /**
     * 保存聊天室调教规则
     */
    private fun saveTunePrompt(chatId: String, prompt: String) {
        val ctx = appContext ?: return

        // 查找当前聊天室的开发者 ID 来保存调教
        val chatRoomInfos = ConfigManager.getJson<MutableSet<ChatRoomInfoDTO>>(
            ctx, ConfigManager.KEY_CHAT_ROOM_INFO, mutableSetOf()
        )

        var targetRoom = chatRoomInfos.find { it.chatroomId == chatId }
        if (targetRoom == null) {
            targetRoom = ChatRoomInfoDTO(
                chatroomId = chatId,
                userInfoDTOSet = mutableSetOf()
            )
            chatRoomInfos.add(targetRoom)
        }

        // 找到或创建用户信息
        val devIds = ConfigManager.getStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS)
        var targetUser = targetRoom.userInfoDTOSet.find { devIds.contains(it.wxId) }
        if (targetUser == null) {
            targetUser = UserInfoDTO(wxId = chatId, nickName = "admin")
            targetRoom.userInfoDTOSet.add(targetUser)
        }

        targetUser.userSubcribeDTO = cc.aurora.bot.service.wx.dto.UserSubcribeDTO(keyWord = prompt)
        ConfigManager.saveJson(ctx, ConfigManager.KEY_CHAT_ROOM_INFO, chatRoomInfos)
    }

    // ===================== 消息列表缓存 =====================

    /**
     * 消息列表缓存条目，记录上一次构建的消息列表及其输入指纹。
     */
    data class MessageCacheEntry(
        val messages: List<AiMessage>,
        val fingerprint: Int
    )

    /**
     * 消息列表缓存：wxId -> 缓存条目
     * 仅当输入（系统提示词 + 历史消息 + 用户消息）发生变化时才重建消息列表。
     */
    private val messageCache = ConcurrentHashMap<String, MessageCacheEntry>()

    /**
     * 构建多轮对话消息列表（带缓存）
     * 格式: [system_prompt, ...history, new_user_message]
     * 仅当系统提示词、历史消息或用户消息发生变化时才重新构建。
     */
    private fun buildChatMessages(wxId: String, userMessage: String): List<AiMessage> {
        val ctx = appContext ?: return emptyList()

        // 计算输入指纹：系统提示词 + 历史消息 + 用户消息 + 缓存轮数
        val systemPrompt = getSystemPrompt(wxId)
        val cacheTimes = AppConfig.fromConfigManager(ctx).cacheTimes
        val history = chatCache[wxId]
        val historySize = history?.size ?: 0
        val historyHash = history?.hashCode() ?: 0

        // 组合指纹：系统提示词哈希 + 历史消息哈希 + 历史大小 + 缓存轮数 + 用户消息
        val fingerprint = systemPrompt.hashCode() * 31 + historyHash * 31 + historySize * 31 + cacheTimes * 31 + userMessage.hashCode()

        // 检查缓存
        val cached = messageCache[wxId]
        if (cached != null && cached.fingerprint == fingerprint) {
            return cached.messages
        }

        // 缓存未命中，重新构建消息列表
        val messages = mutableListOf<AiMessage>()

        // 1. System prompt
        messages.add(AiMessage(role = "system", content = systemPrompt))

        // 2. 历史消息
        if (history != null && history.isNotEmpty()) {
            // 取最近 cacheTimes 轮对话 (每轮包含 user + assistant)
            val takeCount = minOf(cacheTimes * 2, MAX_HISTORY_MESSAGES, history.size)
            val recentHistory = history.takeLast(takeCount)
            messages.addAll(recentHistory)
        }

        // 3. 新消息
        messages.add(AiMessage(role = "user", content = userMessage))

        // 更新缓存
        messageCache[wxId] = MessageCacheEntry(messages, fingerprint)

        return messages
    }

    /**
     * 更新对话缓存
     * 过滤掉 system 消息，仅保留用户和助手对话，并按 CACHE_TIMES 截断
     */
    private fun updateChatCache(wxId: String, messages: List<AiMessage>) {
        val ctx = appContext ?: return
        val cacheTimes = AppConfig.fromConfigManager(ctx).cacheTimes
        // 过滤掉 system 消息，只保留 user 和 assistant 的对话历史
        val conversationMessages = messages.filter { it.role != "system" }
        // 截断到 cacheTimes 轮对话 (每轮 user + assistant = 2 条)
        val maxMessages = minOf(cacheTimes * 2, MAX_HISTORY_MESSAGES)
        val truncated = if (conversationMessages.size > maxMessages) {
            conversationMessages.takeLast(maxMessages).toMutableList()
        } else {
            conversationMessages.toMutableList()
        }
        synchronized(chatCache) {
            chatCache[wxId] = truncated
        }
        // 同步更新 ConversationManager，确保 #清空对话 / #导出对话 / #对话统计 等指令数据一致
        ConversationManager.updateHistory(wxId, truncated)
    }

    /**
     * 从 SP 恢复对话缓存到内存
     */
    private fun restoreChatCache() {
        val ctx = appContext ?: return
        try {
            val allChats = ConfigManager.getJson<List<MultipleChat>>(
                ctx, ConfigManager.KEY_CHAT_MESSAGES, emptyList()
            )
            synchronized(chatCache) {
                chatCache.clear()
                for (chat in allChats) {
                    chatCache[chat.chatId] = chat.messages
                }
            }
            // 同步恢复 ConversationManager，确保数据一致性
            for (chat in allChats) {
                ConversationManager.updateHistory(chat.chatId, chat.messages)
            }
            XposedBridge.log("$TAG: Restored ${chatCache.size} chats from SP")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: restoreChatCache error: ${e.message}")
        }
    }

    /**
     * 发送回复 (封装 sendWxMessage + 计数 + 字符限制)
     * 超过 500 字符的消息会被截断并添加 "..."
     */
    private fun sendReply(wxId: String, reply: String) {
        val ctx = appContext ?: return

        // 截断过长消息（500 字符限制）
        val truncatedReply = if (reply.length > 500) {
            reply.take(500) + "..."
        } else {
            reply
        }

        // 发送消息
        sendWxMessage(wxId, truncatedReply)

        // 更新发送计数
        val currentCount = ConfigManager.getInt(ctx, ConfigManager.KEY_SEND_COUNT, 0)
        ConfigManager.saveInt(ctx, ConfigManager.KEY_SEND_COUNT, currentCount + 1)

        XposedBridge.log("$TAG: sendReply to=$wxId len=${truncatedReply.length}")
    }

    /**
     * 重启微信
     * 先发送回复，延迟后杀死进程，系统会重新拉起
     */
    private fun restartWeChat() {
        try {
            val ctx = appContext ?: return
            XposedBridge.log("$TAG: restartWeChat() triggered")

            // 延迟执行重启, 让回复消息先发出去
            mainHandler.postDelayed({
                try {
                    // 先尝试通过 ActivityManager 杀死进程
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                        as? android.app.ActivityManager
                    val packageName = ctx.packageName
                    if (am != null) {
                        am.killBackgroundProcesses(packageName)
                        XposedBridge.log("$TAG: killBackgroundProcesses called for $packageName")
                    }

                    // 延迟一小段时间后强制杀进程
                    mainHandler.postDelayed({
                        try {
                            XposedBridge.log("$TAG: Force killing process via android.os.Process.killProcess")
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Force kill failed: ${e.message}")
                            // 回退: 使用 System.exit
                            try {
                                System.exit(0)
                            } catch (_: Throwable) {}
                        }
                    }, 1000) // 1秒延迟确保 killBackgroundProcesses 生效
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: restartWeChat kill error: ${e.message}")
                }
            }, 2000) // 2秒延迟让回复消息先发出去
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: restartWeChat error: ${e.message}")
        }
    }

    // ===================== 9. 健康检查 =====================

    /**
     * 执行健康检查：验证各项核心组件是否正常运行。
     *
     * 检查项：
     * 1. DexKit bridge 是否激活
     * 2. HTTP 服务器是否在运行
     * 3. ConfigManager 是否可访问
     * 4. AI Key 是否已配置
     *
     * @return HealthStatus 包含所有检查结果的结构化数据
     */
    fun healthCheck(): HealthStatus {
        val ctx = appContext
        val details = mutableMapOf<String, String>()

        // 检查1: DexKit bridge 是否激活
        val dexKitActive = try {
            dexKitBridge != null
        } catch (e: Exception) {
            details["dexKitError"] = e.message ?: "unknown"
            false
        }
        details["dexKitStatus"] = if (dexKitActive) "active" else "inactive"

        // 检查2: HTTP 服务器是否在运行
        val httpServerRunning = try {
            httpServer?.isAlive == true
        } catch (e: Exception) {
            details["httpServerError"] = e.message ?: "unknown"
            false
        }
        details["httpServerStatus"] = if (httpServerRunning) "running" else "stopped"
        details["httpServerPort"] = HttpServer.actualPort.toString()

        // 检查3: ConfigManager 是否可访问
        val configManagerAccessible = try {
            if (ctx != null) {
                ConfigManager.getConfigSummary(ctx)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            details["configManagerError"] = e.message ?: "unknown"
            false
        }
        details["configManagerStatus"] = if (configManagerAccessible) "accessible" else "inaccessible"

        // 检查4: AI Key 是否已配置
        val aiKeyConfigured = try {
            if (ctx != null) {
                val config = AppConfig.fromConfigManager(ctx)
                config.hasAnyProviderConfigured()
            } else {
                false
            }
        } catch (e: Exception) {
            details["aiKeyError"] = e.message ?: "unknown"
            false
        }
        details["aiKeyStatus"] = if (aiKeyConfigured) "configured" else "not_configured"

        // 计算整体健康状态
        val healthy = dexKitActive && httpServerRunning && configManagerAccessible && aiKeyConfigured

        // 获取统计信息
        val stats = getStatistics()

        // 记录日志
        XposedBridge.log("$TAG: healthCheck() - healthy=$healthy")
        XposedBridge.log("$TAG:   dexKitActive=$dexKitActive")
        XposedBridge.log("$TAG:   httpServerRunning=$httpServerRunning")
        XposedBridge.log("$TAG:   configManagerAccessible=$configManagerAccessible")
        XposedBridge.log("$TAG:   aiKeyConfigured=$aiKeyConfigured")

        return HealthStatus(
            healthy = healthy,
            dexKitActive = dexKitActive,
            httpServerRunning = httpServerRunning,
            configManagerAccessible = configManagerAccessible,
            aiKeyConfigured = aiKeyConfigured,
            uptimeMs = stats["uptimeMs"] ?: 0L,
            messagesProcessed = stats["messagesProcessed"] ?: 0L,
            aiCallsMade = stats["aiCallsMade"] ?: 0L,
            commandsExecuted = stats["commandsExecuted"] ?: 0L,
            errorsEncountered = stats["errorsEncountered"] ?: 0L,
            details = details
        )
    }

    // ===================== 10. ModuleLifecycle 实现 =====================

    /**
     * 模块初始化完成回调。
     *
     * 当所有核心组件（DexKit、HTTP 服务器、消息拦截、定时器、队列处理器）都成功初始化后调用。
     * 记录初始化完成日志，输出模块运行状态摘要。
     */
    override fun onInitialized() {
        XposedBridge.log("$TAG: ========================================")
        XposedBridge.log("$TAG: Aurora module initialized successfully!")
        XposedBridge.log("$TAG:   DexKit: ${if (dexKitBridge != null) "active" else "inactive"}")
        XposedBridge.log("$TAG:   HTTP Server: ${if (httpServer?.isAlive == true) "running on port ${HttpServer.actualPort}" else "stopped"}")
        XposedBridge.log("$TAG:   Message Interceptor: ${if (MessageReceiver.getReceiveClass() != null) "hooked" else "pending"}")
        XposedBridge.log("$TAG:   AI Provider: ${getCurrentAiProvider()?.displayName ?: "not configured"}")
        XposedBridge.log("$TAG: ========================================")
    }

    /**
     * 模块错误回调。
     *
     * 当模块内部发生不可恢复的错误时触发。记录错误日志并递增错误计数器。
     *
     * @param error 发生的错误异常
     */
    override fun onError(error: Throwable) {
        totalErrorsEncountered.incrementAndGet()
        XposedBridge.log("$TAG: Module error occurred: ${error.message}")
        error.printStackTrace()
    }

    // ===================== 11. 安全关闭 =====================

    /**
     * 安全关闭所有组件：停止 HTTP 服务器、取消所有协程、持久化缓存、释放 DexKit bridge。
     *
     * 应在进程终止前调用，确保资源正确释放和数据不丢失。
     */
    fun shutdown() {
        XposedBridge.log("$TAG: shutdown() called - starting graceful shutdown...")

        // 1. 停止 HTTP 服务器
        try {
            httpServer?.let { server ->
                XposedBridge.log("$TAG: shutdown() - stopping HTTP server on port ${HttpServer.actualPort}")
                server.stop()
                XposedBridge.log("$TAG: shutdown() - HTTP server stopped")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: shutdown() - HTTP server stop error: ${e.message}")
        }

        // 2. 取消所有协程
        try {
            XposedBridge.log("$TAG: shutdown() - cancelling all coroutines")
            scope.cancel()
            XposedBridge.log("$TAG: shutdown() - coroutines cancelled")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: shutdown() - coroutine cancel error: ${e.message}")
        }

        // 2.5. 关闭 SchedulerManager
        try {
            XposedBridge.log("$TAG: shutdown() - shutting down SchedulerManager")
            SchedulerManager.shutdown()
            XposedBridge.log("$TAG: shutdown() - SchedulerManager shut down")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: shutdown() - SchedulerManager shutdown error: ${e.message}")
        }

        // 3. 持久化缓存到 SP
        try {
            XposedBridge.log("$TAG: shutdown() - flushing cache to SP")
            flushCacheToSP()
            XposedBridge.log("$TAG: shutdown() - cache flushed to SP")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: shutdown() - cache flush error: ${e.message}")
        }

        // 4. 清除去重缓存
        try {
            XposedBridge.log("$TAG: shutdown() - clearing dedup cache")
            clearDedupCache()
            XposedBridge.log("$TAG: shutdown() - dedup cache cleared")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: shutdown() - dedup cache clear error: ${e.message}")
        }

        // 5. 清除 DexKit 缓存
        try {
            XposedBridge.log("$TAG: shutdown() - clearing DexKit cache")
            DexKitCache.clear()
            XposedBridge.log("$TAG: shutdown() - DexKit cache cleared")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: shutdown() - DexKit cache clear error: ${e.message}")
        }

        // 6. 释放 DexKit bridge
        try {
            val bridge = dexKitBridge
            if (bridge != null) {
                XposedBridge.log("$TAG: shutdown() - releasing DexKit bridge")
                try {
                    bridge.close()
                } catch (e: Exception) {
                    XposedBridge.log("$TAG: shutdown() - DexKit bridge close error: ${e.message}")
                }
                dexKitBridge = null
                XposedBridge.log("$TAG: shutdown() - DexKit bridge released")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: shutdown() - DexKit bridge release error: ${e.message}")
        }

        // 7. 记录最终统计
        val stats = getStatistics()
        XposedBridge.log("$TAG: shutdown() - final statistics: $stats")
        XposedBridge.log("$TAG: shutdown() - graceful shutdown completed")
    }
}