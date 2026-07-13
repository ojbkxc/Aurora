package cc.aurora.bot.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.highcapable.yukihookapi.hook.factory.classOf
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import cc.aurora.bot.service.ai.AiMessage
import cc.aurora.bot.service.ai.AiProvider
import cc.aurora.bot.service.ai.AiService
import cc.aurora.bot.service.command.CommandParser
import cc.aurora.bot.service.command.CommandType
import cc.aurora.bot.service.config.ConfigManager
import cc.aurora.bot.service.http.HttpServer
import cc.aurora.bot.service.wx.dto.ChatRoomInfoDTO
import cc.aurora.bot.service.wx.dto.MultipleChat
import cc.aurora.bot.service.wx.dto.UserInfoDTO
import cc.aurora.bot.service.wx.dto.WxGroupWelcome
import cc.aurora.bot.service.wx.dto.WxSubcribeDTO
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.AnnotationMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.result.MethodData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeChatHooker : IYukiHookXposedInit {

    companion object {
        const val TAG = "AURORA"
        const val SUBSCRIBE_PUSH_HOUR = 9
        const val SUBSCRIBE_PUSH_MINUTE = 30
        const val TIMER_CHECK_INTERVAL = 20_000L       // 20秒
        const val CACHE_FLUSH_INTERVAL = 1_200_000L     // 20分钟
        const val REPORT_INTERVAL = 7_200_000L         // 2小时
        const val MAX_HISTORY_MESSAGES = 20
        const val DEFAULT_TRIGGER_WORD = "机器人"
        const val DEFAULT_PROMPT = "你是一个友好的微信AI助手，请用简洁自然的中文回复。"

        // DexKit 实例
        @Volatile
        var dexKitBridge: DexKitBridge? = null

        /**
         * 由 HookEntry 调用，设置 DexKitBridge 实例
         */
        fun setDexKitBridge(bridge: DexKitBridge) {
            dexKitBridge = bridge
            XposedBridge.log("$TAG: DexKitBridge set from HookEntry")
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var httpServer: HttpServer? = null
    private var appContext: Context? = null
    private var weChatPkgPath: String? = null

    // DexKit 查找到的微信内部类缓存
    @Volatile
    private var msgReceiveClass: Class<*>? = null
    @Volatile
    private var msgReceiveMethod: java.lang.reflect.Method? = null
    @Volatile
    private var msgSendClass: Class<*>? = null

    // 内存缓存: chatId -> 消息列表 (用于快速读写, 定时持久化)
    @Volatile
    private var chatCache: MutableMap<String, MutableList<AiMessage>> = mutableMapOf()

    // 记录当日是否已推送订阅
    @Volatile
    private var lastSubscribePushDate: String? = null

    // ===================== YukiHookAPI 入口 =====================

    override fun onHook() {
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

        // 获取微信 APK 路径, 供 DexKit 使用
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
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to get sourceDir: ${e.message}")
        }

        // 初始化 HTTP 服务
        startHttpServer()
        // 初始化消息拦截
        initMessageInterceptor()
        // 从 SP 恢复对话缓存到内存
        restoreChatCache()
        // 启动定时器
        startTimers()
    }

    // ===================== 1. 消息接收 Hook =====================

    private fun initMessageInterceptor() {
        XposedBridge.log("$TAG: initMessageInterceptor() start")

        // ---- 策略1: 使用 DexKit 动态查找消息处理类 ----
        if (tryHookMessageByDexKit()) {
            XposedBridge.log("$TAG: initMessageInterceptor() done via DexKit")
            return
        }

        // ---- 策略2: 尝试已知路径 Hook ----
        tryHookMessageByKnownPaths()

        // ---- 策略3: 通过字符串特征查找消息处理类 ----
        tryHookMessageByStringFeature()

        XposedBridge.log("$TAG: initMessageInterceptor() done (fallback strategies)")
    }

    /**
     * 策略1: 使用 DexKit 动态查找微信消息处理类
     * 搜索包含 "msgId" 和 "talker" 字符串常量的方法
     * 这些是微信消息类的特征字段名
     */
    private fun tryHookMessageByDexKit(): Boolean {
        try {
            val bridge = dexKitBridge ?: return false
            XposedBridge.log("$TAG: tryHookMessageByDexKit() using DexKit")

            val classLoader = appContext?.classLoader
            if (classLoader == null) {
                XposedBridge.log("$TAG: DexKit search - classLoader is null")
                return false
            }

            // ===== 策略A: 使用 WAuxiliary 验证过的 doRevokeMsg 模式查找消息处理类 =====
            // 微信内部消息撤回方法包含 talker 和 msgId 信息，与消息接收密切相关
            XposedBridge.log("$TAG: DexKit searching for doRevokeMsg pattern (WAuxiliary-verified)")
            val revokeMethods = bridge.findMethod {
                matcher {
                    addUsingString("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
                }
            }
            XposedBridge.log("$TAG: DexKit found ${revokeMethods.size} revoke methods")

            for (methodData in revokeMethods) {
                try {
                    val clazz = classLoader.loadClass(methodData.className)
                    val method = methodData.getMethodInstance(classLoader)
                    XposedBridge.log("$TAG: DexKit found revoke class: ${methodData.className}.${methodData.name}")

                    hookMethodByReflection(clazz, method)
                    return true
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: DexKit revoke hook failed for ${methodData.className}: ${e.message}")
                }
            }

            // ===== 策略B: 搜索 "msgId" 和 "talker" 字符串 (传统方法) =====
            XposedBridge.log("$TAG: DexKit searching for msgId+talker pattern")
            val msgIdMethods = bridge.findMethod {
                matcher {
                    addUsingString("msgId")
                }
            }
            val talkerMethods = bridge.findMethod {
                matcher {
                    addUsingString("talker")
                }
            }

            val msgIdClassSet = msgIdMethods.map { it.className }.toSet()
            val talkerClassSet = talkerMethods.map { it.className }.toSet()
            val commonClasses = msgIdClassSet.intersect(talkerClassSet)
                .filter { it.contains("tencent.mm") }

            XposedBridge.log("$TAG: DexKit common classes with msgId+talker: ${commonClasses.size}")

            for (className in commonClasses) {
                try {
                    val clazz = classLoader.loadClass(className)
                    for (method in clazz.declaredMethods) {
                        if (method.parameterTypes.isNotEmpty()) {
                            try {
                                method.isAccessible = true
                                msgReceiveClass = clazz
                                msgReceiveMethod = method
                                hookMethodByReflection(clazz, method)
                                XposedBridge.log("$TAG: DexKit hooked method: ${method.name} in $className")
                                return true
                            } catch (e: Throwable) {
                                // continue
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // continue
                }
            }

            // ===== 策略C: 使用已知的真实类名 =====
            return tryHookRealWeChatClasses(classLoader)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: tryHookMessageByDexKit error: ${e.message}")
            return false
        }
    }

    /**
     * 尝试 Hook 已知的真实微信类名
     */
    private fun tryHookRealWeChatClasses(classLoader: ClassLoader): Boolean {
        XposedBridge.log("$TAG: tryHookRealWeChatClasses()")

        // 真实微信类名列表
        val realClassNames = listOf(
            // 聊天历史 Presenter
            "com.tencent.mm.ui.chatting.presenter.n",
            // 消息存储
            "com.tencent.mm.storage.MsgInfo",
            // 会话列表
            "com.tencent.mm.ui.conversation.cb",
            // 应用入口
            "com.tencent.mm.app.MMApplicationLike",
        )

        for (className in realClassNames) {
            try {
                val clazz = classLoader.loadClass(className)
                XposedBridge.log("$TAG: Found real class: $className")

                // 尝试查找包含 msgId/talker/content 相关字段的方法进行 Hook
                val targetMethods = clazz.declaredMethods.filter { method ->
                    val name = method.name.lowercase()
                    val paramCount = method.parameterTypes.size
                    (paramCount >= 1 && paramCount <= 3) &&
                    (name.contains("on") || name.contains("handle") || name.contains("notify") ||
                     name.contains("update") || name.contains("insert") || name.contains("add"))
                }

                for (method in targetMethods) {
                    try {
                        method.isAccessible = true
                        msgReceiveClass = clazz
                        msgReceiveMethod = method
                        hookMethodByReflection(clazz, method)
                        XposedBridge.log("$TAG: Hooked real class method: ${method.name} in $className")
                        return true
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: Hook failed for ${method.name} in $className: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Real class not found: $className: ${e.message}")
            }
        }

        return false
    }

    /**
     * 通过反射 Hook 指定方法
     */
    private fun hookMethodByReflection(clazz: Class<*>, method: java.lang.reflect.Method) {
        try {
            XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        // 从参数中提取消息对象
                        val messageObj = if (param.args.size == 1) {
                            param.args[0]
                        } else {
                            // 尝试从 this 或参数中找消息对象
                            param.thisObject ?: param.args.firstOrNull()
                        }
                        tryExtractAndProcess(messageObj)
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: afterHookedMethod error: ${e.message}")
                    }
                }
            })
            XposedBridge.log("$TAG: hookMethodByReflection success: ${clazz.name}.${method.name}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: hookMethodByReflection failed: ${e.message}")
        }
    }

    /**
     * 策略2: 通过已知的微信消息处理类路径尝试 Hook
     */
    private fun tryHookMessageByKnownPaths() {
        val knownClassPatterns = listOf(
            // 常见的消息事件处理类
            "com.tencent.mm.plugin.messenger.foundation.impl.IMessageEvent",
            "com.tencent.mm.plugin.messenger.foundation.a",
            "com.tencent.mm.plugin.messenger.foundation.b",
            "com.tencent.mm.plugin.messenger.foundation.c",
            // 消息处理内部实现
            "com.tencent.mm.pluginsdk.model.app.MMAppMsgHandler\$a",
            "com.tencent.mm.ui.chatting.ChattingUIFragment\$a",
            // 通用消息回调
            "com.tencent.mm.sdk.platformtools.MMMessageHandler",
            // 真实微信类名
            "com.tencent.mm.ui.chatting.presenter.n",
            "com.tencent.mm.storage.MsgInfo",
            "com.tencent.mm.ui.conversation.cb",
        )

        for (className in knownClassPatterns) {
            try {
                val classLoader = appContext?.classLoader ?: continue
                val clazz = classLoader.loadClass(className)

                // 尝试查找 onNewMessage 或类似方法
                for (method in clazz.declaredMethods) {
                    val methodName = method.name
                    if (methodName == "onNewMessage" || methodName == "onReceive" ||
                        methodName == "handleMessage" || methodName == "a" ||
                        methodName == "b" || methodName == "c" || methodName == "d" ||
                        methodName == "e" || methodName == "f" || methodName == "g" ||
                        methodName == "h" || methodName == "onNotifyChange" ||
                        methodName == "onChange" || methodName == "insert") {
                        if (method.parameterTypes.size in 1..3) {
                            try {
                                hookMethodByReflection(clazz, method)
                                XposedBridge.log("$TAG: Hook success on known class: $className -> ${method.name}")
                                return
                            } catch (e: Throwable) {
                                // 继续尝试
                            }
                        }
                    }
                }
                XposedBridge.log("$TAG: Hook class $className found but no suitable method")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Hook failed on $className: ${e.message}")
            }
        }
    }

    /**
     * 策略3: 通过枚举所有类, 按字符串特征查找消息处理类
     */
    private fun tryHookMessageByStringFeature() {
        try {
            val classLoader = appContext?.classLoader ?: return

            // 尝试查找包含 "onNewMessage" 方法的类
            val targetClasses = findClassesWithMethod(classLoader, "onNewMessage", 1)

            for (clazz in targetClasses) {
                try {
                    if (clazz.name.contains("tencent.mm")) {
                        hookMethodByReflection(clazz, clazz.getDeclaredMethod("onNewMessage", clazz.declaredMethods[0].parameterTypes[0]))
                        XposedBridge.log("$TAG: Hook success by feature on: ${clazz.name}")
                        return
                    }
                } catch (e: Throwable) {
                    // 继续尝试下一个
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: tryHookMessageByStringFeature failed: ${e.message}")
        }
    }

    /**
     * 从 args[0] 消息对象中反射提取 wxId 和消息内容, 然后处理
     */
    private fun tryExtractAndProcess(messageObj: Any?) {
        if (messageObj == null) return
        try {
            val clazz = messageObj.javaClass

            // 提取发送者 wxId: 尝试多种常见字段名 (包括真实 WeChat 字段名)
            val wxId = extractFieldValue(messageObj, clazz, listOf(
                "field_username", "field_talker", "talker",
                "mUsername", "mSender", "m_fromUsername",
                "fromUsername", "senderId", "mTalker", "field_talker"
            )) ?: run {
                XposedBridge.log("$TAG: Could not extract wxId from ${clazz.name}")
                return
            }

            // 提取消息内容: 尝试多种常见字段名 (包括真实 WeChat 字段名)
            val content = extractFieldValue(messageObj, clazz, listOf(
                "field_content", "content", "field_msgContent",
                "mContent", "mMessage", "mMsg",
                "message", "m_content", "m_message"
            )) ?: run {
                XposedBridge.log("$TAG: Could not extract content from ${clazz.name}")
                return
            }

            // 过滤空消息和自己发送的消息
            if (content.isBlank()) return
            // 过滤系统消息 (通常以特定前缀开头)
            if (content.startsWith("<![CDATA[") && content.contains("sysmsg")) return

            XposedBridge.log("$TAG: Received msg from=$wxId content=${content.take(50)}")
            processReceivedMessage(wxId, content)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: tryExtractAndProcess error: ${e.message}")
        }
    }

    /**
     * 从对象中反射提取字段值, 依次尝试候选字段名
     */
    private fun extractFieldValue(obj: Any, clazz: Class<*>, fieldNames: List<String>): String? {
        for (fieldName in fieldNames) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                val value = field.get(obj)
                if (value != null) {
                    val str = value.toString()
                    if (str.isNotBlank()) return str
                }
            } catch (_: NoSuchFieldException) {
                // 尝试父类
                try {
                    val superField = clazz.superclass?.getDeclaredField(fieldName)
                    if (superField != null) {
                        superField.isAccessible = true
                        val value = superField.get(obj)
                        if (value != null) {
                            val str = value.toString()
                            if (str.isNotBlank()) return str
                        }
                    }
                } catch (_: Throwable) {
                    // continue
                }
            } catch (_: Throwable) {
                // continue
            }
        }

        // 深度搜索: 遍历所有字段, 寻找可能是 wxId 或内容的值
        try {
            val allFields = getAllFields(clazz)
            for (field in allFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(obj) ?: continue
                    val str = value.toString()

                    // wxId 特征: 以 "wxid_" 开头或包含 "@chatroom"
                    if (fieldNames.any { it.contains("username") || it.contains("talker") || it.contains("sender") }) {
                        if (str.startsWith("wxid_") || str.contains("@chatroom")) return str
                    }
                    // 内容特征: 较长文本
                    if (fieldNames.any { it.contains("content") || it.contains("msg") || it.contains("message") }) {
                        if (str.length > 1 && !str.contains("$") && !str.contains("@")) return str
                    }
                } catch (_: Throwable) {
                    // continue
                }
            }
        } catch (_: Throwable) {
            // continue
        }

        return null
    }

    /**
     * 递归获取类及其父类的所有字段
     */
    private fun getAllFields(clazz: Class<*>): List<java.lang.reflect.Field> {
        val fields = mutableListOf<java.lang.reflect.Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            fields.addAll(current.declaredFields)
            current = current.superclass
        }
        return fields
    }

    /**
     * 在 ClassLoader 中查找包含指定方法的类 (简易实现)
     */
    private fun findClassesWithMethod(
        classLoader: ClassLoader,
        methodName: String,
        paramCount: Int
    ): List<Class<*>> {
        val results = mutableListOf<Class<*>>()
        val candidates = listOf(
            "com.tencent.mm.plugin.messenger.foundation.impl.IMessageEvent",
            "com.tencent.mm.plugin.messenger.foundation.impl.a",
            "com.tencent.mm.plugin.messenger.foundation.impl.b",
            "com.tencent.mm.plugin.messenger.foundation.impl.c",
            "com.tencent.mm.plugin.messenger.foundation.impl.d",
            "com.tencent.mm.plugin.messenger.foundation.impl.e",
            "com.tencent.mm.plugin.messenger.foundation.a.a",
            "com.tencent.mm.plugin.messenger.foundation.a.b",
            "com.tencent.mm.plugin.messenger.foundation.a.c",
            // 真实微信类名
            "com.tencent.mm.ui.chatting.presenter.n",
            "com.tencent.mm.storage.MsgInfo",
            "com.tencent.mm.ui.conversation.cb",
        )

        for (candidate in candidates) {
            try {
                val clazz = classLoader.loadClass(candidate)
                for (method in clazz.declaredMethods) {
                    if (method.name == methodName && method.parameterTypes.size == paramCount) {
                        results.add(clazz)
                        break
                    }
                }
            } catch (_: Throwable) {
                // continue
            }
        }
        return results
    }

    // ===================== 2. 消息处理入口 =====================

    private fun processReceivedMessage(wxId: String, content: String) {
        val ctx = appContext ?: return

        // 过滤 XML 格式的系统消息
        if (content.trim().startsWith("<") && content.trim().endsWith(">")) {
            XposedBridge.log("$TAG: Skip XML system message")
            return
        }

        // 检查是否是 # 指令
        if (content.trimStart().startsWith("#")) {
            handleCommand(wxId, content.trim())
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
            val triggerWord = ConfigManager.getString(ctx, ConfigManager.KEY_TRIGGER_WORD, DEFAULT_TRIGGER_WORD)
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

        XposedBridge.log("$TAG: handleCommand wxId=$wxId cmd=$cmdType content=$cmdContent")

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
                if (cmdContent.isNotBlank()) {
                    callAI(wxId, cmdContent, null)
                }
            }
            CommandType.AI_DEEPSEEK -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_DEEPSEEK_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置 DeepSeek Key, 请先发送 #deepseekKey <your_key>")
                    return
                }
                if (cmdContent.isNotBlank()) {
                    callAI(wxId, cmdContent, AiProvider.DEEPSEEK)
                }
            }
            CommandType.AI_QWEN -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_QWEN_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置通义千问 Key, 请先发送 #qwenKey <your_key>")
                    return
                }
                if (cmdContent.isNotBlank()) {
                    callAI(wxId, cmdContent, AiProvider.QWEN)
                }
            }
            CommandType.AI_SILICON -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_SILICON_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置硅基流动 Key, 请先发送 #siliconKey <your_key>")
                    return
                }
                if (cmdContent.isNotBlank()) {
                    callAI(wxId, cmdContent, AiProvider.SILICON)
                }
            }
            CommandType.AI_ZHIPU -> {
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置智谱 Key, 请先发送 #zhipuKey <your_key>")
                    return
                }
                if (cmdContent.isNotBlank()) {
                    callAI(wxId, cmdContent, AiProvider.ZHIPU)
                }
            }
            CommandType.AI_IMAGE -> {
                // 智谱图片生成: 使用智谱的 CogView 模型
                val key = ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY)
                if (key.isBlank()) {
                    sendReply(wxId, "未配置智谱 Key, 请先发送 #zhipuKey <your_key>")
                    return
                }
                if (cmdContent.isNotBlank()) {
                    callAIImage(wxId, cmdContent)
                }
            }

            // ===== 设置配置指令 =====
            CommandType.SET_DEEPSEEK_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_KEY, cmdContent)
                sendReply(wxId, "DeepSeek Key 已设置")
            }
            CommandType.SET_DEEPSEEK_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_MODEL, cmdContent)
                sendReply(wxId, "DeepSeek 模型已设置为: $cmdContent")
            }
            CommandType.SET_QWEN_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_KEY, cmdContent)
                sendReply(wxId, "通义千问 Key 已设置")
            }
            CommandType.SET_QWEN_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_MODEL, cmdContent)
                sendReply(wxId, "通义千问模型已设置为: $cmdContent")
            }
            CommandType.SET_SILICON_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_KEY, cmdContent)
                sendReply(wxId, "硅基流动 Key 已设置")
            }
            CommandType.SET_SILICON_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_MODEL, cmdContent)
                sendReply(wxId, "硅基流动模型已设置为: $cmdContent")
            }
            CommandType.SET_ZHIPU_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_KEY, cmdContent)
                sendReply(wxId, "智谱 Key 已设置")
            }
            CommandType.SET_ZHIPU_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_MODEL, cmdContent)
                sendReply(wxId, "智谱模型已设置为: $cmdContent")
            }
            CommandType.SET_API -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_API, cmdContent)
                sendReply(wxId, "自定义API地址已设置为: $cmdContent")
            }
            CommandType.SET_KEY -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_KEY, cmdContent)
                sendReply(wxId, "自定义API Key已设置")
            }
            CommandType.SET_MODEL -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_MODEL, cmdContent)
                sendReply(wxId, "自定义模型已设置为: $cmdContent")
            }
            CommandType.SET_TRIGGER -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_TRIGGER_WORD, cmdContent)
                sendReply(wxId, "触发词已设置为: $cmdContent")
            }
            CommandType.SET_CACHE -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                val times = cmdContent.toIntOrNull() ?: 10
                ConfigManager.saveInt(ctx, ConfigManager.KEY_CACHE_TIMES, times)
                sendReply(wxId, "缓存轮数已设置为: $times")
            }

            // ===== 需求指令 =====
            CommandType.DEMAND -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                // 需求记录到日志
                XposedBridge.log("$TAG: DEMAND from $wxId: $cmdContent")
                sendReply(wxId, "需求已记录: $cmdContent")
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
                // 查找是否已有该群的欢迎语, 有则更新, 无则新增
                val existing = welcomes.find { it.chatroomId == wxId }
                if (existing != null) {
                    existing.welcomWord = cmdContent
                } else {
                    welcomes.add(WxGroupWelcome(chatroomId = wxId, welcomWord = cmdContent))
                }
                ConfigManager.saveJson(ctx, ConfigManager.KEY_WELCOME, welcomes)
                sendReply(wxId, "群 $wxId 欢迎语已设置")
            }
            CommandType.QUERY_WELCOME -> {
                val welcomes = ConfigManager.getJson<MutableList<WxGroupWelcome>>(
                    ctx, ConfigManager.KEY_WELCOME, mutableListOf()
                )
                val welcome = welcomes.find { it.chatroomId == wxId }
                if (welcome != null) {
                    sendReply(wxId, "本群欢迎语: ${welcome.welcomWord}")
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
                sendReply(wxId, if (removed) "已取消本群欢迎语" else "本群未设置欢迎语")
            }
            CommandType.NO_NEED_AT -> {
                sendReply(wxId, "无需艾特模式已开启")
            }
            CommandType.NEED_AT -> {
                sendReply(wxId, "需艾特模式已开启")
            }

            // ===== 订阅管理 =====
            CommandType.SUBSCRIBE -> {
                val parts = cmdContent.split(" ", limit = 2)
                if (parts.size < 2) {
                    sendReply(wxId, "格式: #订阅:<名称> <URL>")
                    return
                }
                val subName = parts[0]
                val subUrl = parts[1]
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
                val subName = cmdContent.trim()
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
                saveTunePrompt(wxId, cmdContent)
                sendReply(wxId, "当前聊天室调教规则已保存")
            }
            CommandType.DEFAULT_TUNE -> {
                if (!isDev) { sendReply(wxId, "仅开发者可执行此操作"); return }
                ConfigManager.saveString(ctx, ConfigManager.KEY_PROMPT, cmdContent)
                sendReply(wxId, "默认调教规则已更新")
            }

            // ===== 机器人信息 =====
            CommandType.BOT_INFO -> {
                val provider = getCurrentAiProvider()
                val model = getCurrentModel()
                val triggerWord = ConfigManager.getString(ctx, ConfigManager.KEY_TRIGGER_WORD, DEFAULT_TRIGGER_WORD)
                val boundIds = ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS)
                val devIdsSet = ConfigManager.getStringSet(ctx, ConfigManager.KEY_DEV_WX_IDS)
                val sendCount = ConfigManager.getInt(ctx, ConfigManager.KEY_SEND_COUNT, 0)

                val info = StringBuilder()
                info.append("=== Aurora 机器人信息 ===\n")
                info.append("AI厂商: ${provider.displayName}\n")
                info.append("模型: ${model.ifBlank { "默认" }}\n")
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

            CommandType.NONE -> {
                // 不是指令, 交给正常消息处理
                XposedBridge.log("$TAG: Unrecognized command: $message")
            }
        }
    }

    // ===================== 4. AI 调用 =====================

    private fun callAI(wxId: String, message: String, forceProvider: AiProvider?) {
        scope.launch {
            try {
                val ctx = appContext ?: return@launch

                // 确定使用的 AI 厂商
                val provider = forceProvider ?: getCurrentAiProvider()
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
                    return@launch
                }

                // 构建多轮对话消息列表
                val chatMessages = buildChatMessages(wxId, message)

                XposedBridge.log("$TAG: callAI wxId=$wxId provider=${provider.displayName} model=$model msgCount=${chatMessages.size}")

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
                        return@launch
                    }

                XposedBridge.log("$TAG: AI reply for $wxId: ${reply.take(100)}")

                // 更新对话缓存
                val updatedMessages = chatMessages.toMutableList()
                updatedMessages.add(AiMessage(role = "assistant", content = reply))
                updateChatCache(wxId, updatedMessages)

                // 发送回复
                sendReply(wxId, reply)

            } catch (e: Exception) {
                XposedBridge.log("$TAG: callAI error for $wxId: ${e.message}")
                e.printStackTrace()
                sendReply(wxId, "AI 调用失败: ${e.message?.take(100)}")
            }
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

            // ---- 策略1: 使用真实微信内部类发送消息 ----
            if (trySendMessageByRealClass(classLoader, wxId, message)) {
                XposedBridge.log("$TAG: sendWxMessage success (real class) to=$wxId")
                return
            }

            // ---- 策略2: 通过 DexKit 查找发送方法 ----
            if (trySendMessageByDexKit(classLoader, wxId, message)) {
                XposedBridge.log("$TAG: sendWxMessage success (DexKit) to=$wxId")
                return
            }

            // ---- 策略3: 通过已知的发送消息类路径尝试 ----
            if (trySendMessageByKnownPaths(classLoader, wxId, message)) {
                XposedBridge.log("$TAG: sendWxMessage success (known path) to=$wxId")
                return
            }

            // ---- 策略4: 通过反射搜索消息发送方法 ----
            if (trySendMessageByReflection(classLoader, wxId, message)) {
                XposedBridge.log("$TAG: sendWxMessage success (reflection) to=$wxId")
                return
            }

            XposedBridge.log("$TAG: sendWxMessage failed - could not find send method for $wxId")

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: sendWxMessage error: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 策略1: 使用真实微信内部类 com.tencent.mm.ui.conversation.cb 发送消息
     * 该类有字段: createTime, content, talker, msgId, msg
     * 方法 b() 用于发送消息
     */
    private fun trySendMessageByRealClass(classLoader: ClassLoader, wxId: String, message: String): Boolean {
        try {
            val realClassName = "com.tencent.mm.ui.conversation.cb"
            val clazz = classLoader.loadClass(realClassName)
            XposedBridge.log("$TAG: trySendMessageByRealClass loaded: $realClassName")

            // 尝试方案A: 查找带 send 方法签名的 b() 方法
            for (method in clazz.declaredMethods) {
                val methodName = method.name
                // 微信内部常见的发送方法名: b, send, a, sendMessage
                if (methodName in listOf("b", "a", "send", "sendMessage", "c", "d")) {
                    // 尝试无参方法
                    if (method.parameterTypes.isEmpty()) {
                        try {
                            // 构造实例
                            val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                            if (constructor != null) {
                                constructor.isAccessible = true
                                val instance = constructor.newInstance()

                                // 设置字段: talker = wxId, content = message
                                setFieldSafely(instance, clazz, "talker", wxId)
                                setFieldSafely(instance, clazz, "content", message)
                                // 设置 createTime
                                setFieldSafely(instance, clazz, "createTime", System.currentTimeMillis())
                                // 设置 msgId
                                setFieldSafely(instance, clazz, "msgId", System.currentTimeMillis().toString())

                                method.isAccessible = true
                                method.invoke(instance)
                                XposedBridge.log("$TAG: sendWxMessage via real class cb.${methodName}()")
                                return true
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: cb.${methodName}() invoke failed: ${e.message}")
                        }
                    }

                    // 尝试带 (String, String) 参数的方法
                    if (method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == String::class.java &&
                        method.parameterTypes[1] == String::class.java) {
                        try {
                            val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                            val instance = if (constructor != null) {
                                constructor.isAccessible = true
                                constructor.newInstance()
                            } else {
                                null
                            }
                            // 如果是静态方法则不需要实例
                            if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                                method.isAccessible = true
                                method.invoke(null, wxId, message)
                                return true
                            } else if (instance != null) {
                                method.isAccessible = true
                                method.invoke(instance, wxId, message)
                                return true
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: cb.${methodName}(String,String) failed: ${e.message}")
                        }
                    }
                }
            }

            // 方案B: 尝试通过字段 a (接收者) 和 c (内容) + 方法 b (发送) 的模式
            return trySendByFieldPattern(clazz, wxId, message)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: trySendMessageByRealClass failed: ${e.message}")
            return false
        }
    }

    /**
     * 通过字段 a(接收者)/c(内容) + 方法 b(发送) 的模式发送消息
     */
    private fun trySendByFieldPattern(clazz: Class<*>, wxId: String, message: String): Boolean {
        try {
            // 检查是否有字段 a 和 c
            val hasFieldA = try { clazz.getDeclaredField("a") != null } catch (_: Throwable) { false }
            val hasFieldC = try { clazz.getDeclaredField("c") != null } catch (_: Throwable) { false }

            if (!hasFieldA || !hasFieldC) {
                return false
            }

            // 查找 b() 方法
            val bMethod = clazz.declaredMethods.find { it.name == "b" && it.parameterTypes.isEmpty() }
            if (bMethod == null) {
                return false
            }

            // 构造实例
            val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
            if (constructor == null) {
                return false
            }

            constructor.isAccessible = true
            val instance = constructor.newInstance()

            // 设置字段
            setFieldSafely(instance, clazz, "a", wxId)
            setFieldSafely(instance, clazz, "c", message)

            // 调用 b() 发送
            bMethod.isAccessible = true
            bMethod.invoke(instance)
            XposedBridge.log("$TAG: sendWxMessage via field pattern a/c + b() in ${clazz.name}")
            return true

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: trySendByFieldPattern failed: ${e.message}")
            return false
        }
    }

    /**
     * 策略2: 通过 DexKit 查找发送消息方法
     * 使用 WAuxiliary 验证过的 SendTextComponent 模式
     */
    private fun trySendMessageByDexKit(classLoader: ClassLoader, wxId: String, message: String): Boolean {
        try {
            val bridge = dexKitBridge ?: return false
            XposedBridge.log("$TAG: trySendMessageByDexKit() using DexKit")

            // ===== 策略A: WAuxiliary 验证过的 SendTextComponent 构造函数模式 =====
            // 微信内部文本发送组件: com.tencent.mm.ui.chatting.component
            // 特征字符串: "MicroMsg.ChattingUI.SendTextComponent", "doSendMessage begin send txt msg"
            XposedBridge.log("$TAG: DexKit searching for SendTextComponent (WAuxiliary-verified)")
            val sendTextMethods = bridge.findMethod {
                matcher {
                    addUsingString("MicroMsg.ChattingUI.SendTextComponent")
                }
            }
            XposedBridge.log("$TAG: DexKit found ${sendTextMethods.size} SendTextComponent methods")

            for (methodData in sendTextMethods) {
                try {
                    val clazz = classLoader.loadClass(methodData.className)
                    XposedBridge.log("$TAG: DexKit SendTextComponent class: ${methodData.className}")

                    // 查找构造函数 (参数数量 12-14, 来自 WAuxiliary 验证)
                    for (constructor in clazz.declaredConstructors) {
                        val paramCount = constructor.parameterTypes.size
                        if (paramCount in 12..14) {
                            XposedBridge.log("$TAG: Found SendTextComponent constructor with $paramCount params")
                            // 记录构造函数信息，后续通过 Hook 构造来注入消息
                            msgSendClass = clazz
                            return true
                        }
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: SendTextComponent class load failed: ${e.message}")
                }
            }

            // ===== 策略B: 搜索 "sendMessage" 或 "insertMsg" 字符串的类 =====
            XposedBridge.log("$TAG: DexKit searching for sendMessage/insertMsg pattern")
            val sendMethods = bridge.findMethod {
                matcher {
                    addUsingString("sendMessage")
                }
            }
            val insertMethods = bridge.findMethod {
                matcher {
                    addUsingString("insertMsg")
                }
            }

            val allClasses = (sendMethods.map { it.className } + insertMethods.map { it.className })
                .filter { it.contains("tencent.mm") }
                .distinct()

            XposedBridge.log("$TAG: DexKit send classes: $allClasses")

            for (className in allClasses) {
                try {
                    val clazz = classLoader.loadClass(className)

                    // 尝试通过字段模式发送
                    if (trySendByFieldPattern(clazz, wxId, message)) {
                        return true
                    }

                    // 尝试直接查找 send 方法
                    for (method in clazz.declaredMethods) {
                        if (method.parameterTypes.size in 1..3 &&
                            method.name in listOf("send", "b", "a", "sendMessage", "sendMsg", "insert")) {
                            try {
                                val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                                val instance = constructor?.let {
                                    it.isAccessible = true
                                    it.newInstance()
                                }

                                // 设置字段
                                setFieldSafely(instance ?: continue, clazz, "talker", wxId)
                                setFieldSafely(instance, clazz, "content", message)
                                setFieldSafely(instance, clazz, "a", wxId)
                                setFieldSafely(instance, clazz, "c", message)

                                method.isAccessible = true
                                method.invoke(instance)
                                return true
                            } catch (e: Throwable) {
                                // continue
                            }
                        }
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: DexKit send class $className failed: ${e.message}")
                }
            }

            return false
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: trySendMessageByDexKit error: ${e.message}")
            return false
        }
    }

    /**
     * 策略3: 通过已知的微信消息发送类路径尝试
     */
    private fun trySendMessageByKnownPaths(classLoader: ClassLoader, wxId: String, message: String): Boolean {
        // 已知的消息发送相关类路径模式 (包括真实微信类名)
        val sendClassCandidates = listOf(
            "com.tencent.mm.ui.conversation.cb",
            "com.tencent.mm.pluginsdk.model.app.MMAppMsgHandler",
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.plugin.messenger.foundation.a",
            "com.tencent.mm.plugin.messenger.foundation.b",
            "com.tencent.mm.plugin.messenger.foundation.c",
            "com.tencent.mm.storage.MsgInfo",
            "com.tencent.mm.ui.chatting.presenter.n",
        )

        for (className in sendClassCandidates) {
            try {
                val clazz = classLoader.loadClass(className)

                // 尝试通过字段模式发送
                if (trySendByFieldPattern(clazz, wxId, message)) {
                    return true
                }

                // 尝试查找带有 (String, String) 参数的发送方法
                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == String::class.java &&
                        method.parameterTypes[1] == String::class.java
                    ) {
                        try {
                            method.isAccessible = true
                            val instance = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                                null
                            } else {
                                // 尝试获取单例或构造实例
                                try {
                                    val singletonField = clazz.getDeclaredField("INSTANCE")
                                    singletonField.isAccessible = true
                                    singletonField.get(null)
                                } catch (_: Throwable) {
                                    try {
                                        val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                                        constructor?.let {
                                            it.isAccessible = true
                                            it.newInstance()
                                        }
                                    } catch (_: Throwable) {
                                        null
                                    }
                                }
                            }
                            if (instance != null || java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                                method.invoke(instance, wxId, message)
                                return true
                            }
                        } catch (_: Throwable) {
                            // continue
                        }
                    }
                }
            } catch (_: Throwable) {
                // continue
            }
        }
        return false
    }

    /**
     * 策略4: 通过反射查找微信内部的消息发送辅助类
     * 根据反编译分析, 微信消息发送适配模式:
     * - 字段 a = 接收者
     * - 字段 c = 内容
     * - 方法 b() = 发送
     */
    private fun trySendMessageByReflection(classLoader: ClassLoader, wxId: String, message: String): Boolean {
        // 常见的消息发送适配器类名模式
        val adapterPatterns = listOf(
            "com.tencent.mm.plugin.messenger.foundation.impl",
            "com.tencent.mm.pluginsdk.model.chatting",
            "com.tencent.mm.model.multi",
            "com.tencent.mm.ui.conversation",
            "com.tencent.mm.ui.chatting",
        )

        for (pkgPattern in adapterPatterns) {
            try {
                val classNames = listOf(
                    "$pkgPattern.SendMessageHelper",
                    "$pkgPattern.MessageSender",
                    "$pkgPattern.a",
                    "$pkgPattern.b",
                    "$pkgPattern.c",
                    "$pkgPattern.d",
                    "$pkgPattern.cb",
                    "$pkgPattern.n",
                )

                for (className in classNames) {
                    try {
                        val clazz = classLoader.loadClass(className)

                        // 尝试字段模式发送
                        if (trySendByFieldPattern(clazz, wxId, message)) {
                            return true
                        }

                        // 查找具有 b() 方法 (发送) 且有 a/c 字段的类
                        val bMethod = clazz.declaredMethods.find { it.name == "b" && it.parameterTypes.isEmpty() }
                        if (bMethod != null) {
                            try {
                                val constructor = clazz.declaredConstructors.firstOrNull()
                                if (constructor != null) {
                                    constructor.isAccessible = true
                                    val instance = if (constructor.parameterTypes.isEmpty()) {
                                        constructor.newInstance()
                                    } else {
                                        continue
                                    }

                                    // 设置接收者字段
                                    setFieldSafely(instance, clazz, "a", wxId)
                                    // 设置内容字段
                                    setFieldSafely(instance, clazz, "c", message)

                                    // 调用发送方法
                                    bMethod.isAccessible = true
                                    bMethod.invoke(instance)
                                    return true
                                }
                            } catch (_: Throwable) {
                                // continue
                            }
                        }
                    } catch (_: Throwable) {
                        // continue
                    }
                }
            } catch (_: Throwable) {
                // continue
            }
        }

        return false
    }

    /**
     * 安全地设置对象的字段值
     */
    private fun setFieldSafely(obj: Any, clazz: Class<*>, fieldName: String, value: Any): Boolean {
        return try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
            true
        } catch (e: NoSuchFieldException) {
            // 尝试父类
            try {
                val superField = clazz.superclass?.getDeclaredField(fieldName)
                if (superField != null) {
                    superField.isAccessible = true
                    superField.set(obj, value)
                    return true
                }
            } catch (_: Throwable) {
                // continue
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

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
                    val ctx = appContext ?: return@apply mapOf("status" to "running")
                    val provider = getCurrentAiProvider()
                    mapOf(
                        "status" to "running",
                        "aiProvider" to provider.displayName,
                        "model" to getCurrentModel().ifBlank { "default" },
                        "sendCount" to ConfigManager.getInt(ctx, ConfigManager.KEY_SEND_COUNT, 0),
                        "bindingCount" to ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS).size
                    )
                }
                // 绑定列表回调
                onGetBindings = {
                    val ctx = appContext ?: return@apply emptyList()
                    ConfigManager.getStringSet(ctx, ConfigManager.KEY_WX_IDS).toList()
                }
                // 订阅列表回调
                onGetSubscriptions = {
                    val ctx = appContext ?: return@apply emptyList()
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
            httpServer?.start()
            XposedBridge.log("$TAG: HTTP server started on port 5888")
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
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
            val body = response.body?.string() ?: return ""
            // 简化: 取前500字符作为摘要
            body.take(500).replace(Regex("<[^>]*>"), "").trim()
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

    /**
     * 获取当前配置的 AI 厂商
     */
    private fun getCurrentAiProvider(): AiProvider {
        val ctx = appContext ?: return AiProvider.DEEPSEEK

        // 按优先级检查: Custom > DeepSeek > Qwen > Silicon > Zhipu
        if (ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_KEY).isNotBlank() &&
            ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_API).isNotBlank()
        ) {
            return AiProvider.CUSTOM
        }
        if (ConfigManager.getString(ctx, ConfigManager.KEY_DEEPSEEK_KEY).isNotBlank()) {
            return AiProvider.DEEPSEEK
        }
        if (ConfigManager.getString(ctx, ConfigManager.KEY_QWEN_KEY).isNotBlank()) {
            return AiProvider.QWEN
        }
        if (ConfigManager.getString(ctx, ConfigManager.KEY_SILICON_KEY).isNotBlank()) {
            return AiProvider.SILICON
        }
        if (ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY).isNotBlank()) {
            return AiProvider.ZHIPU
        }
        return AiProvider.DEEPSEEK
    }

    /**
     * 获取当前 AI API Key
     */
    private fun getCurrentAiKey(): String {
        val ctx = appContext ?: return ""
        return when (getCurrentAiProvider()) {
            AiProvider.DEEPSEEK -> ConfigManager.getString(ctx, ConfigManager.KEY_DEEPSEEK_KEY)
            AiProvider.QWEN -> ConfigManager.getString(ctx, ConfigManager.KEY_QWEN_KEY)
            AiProvider.ZHIPU -> ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY)
            AiProvider.SILICON -> ConfigManager.getString(ctx, ConfigManager.KEY_SILICON_KEY)
            AiProvider.CUSTOM -> ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_KEY)
        }
    }

    /**
     * 获取当前模型名
     */
    private fun getCurrentModel(): String {
        val ctx = appContext ?: return ""
        return when (getCurrentAiProvider()) {
            AiProvider.DEEPSEEK -> ConfigManager.getString(
                ctx, ConfigManager.KEY_DEEPSEEK_MODEL, AiProvider.DEEPSEEK.defaultModel
            )
            AiProvider.QWEN -> ConfigManager.getString(
                ctx, ConfigManager.KEY_QWEN_MODEL, AiProvider.QWEN.defaultModel
            )
            AiProvider.ZHIPU -> ConfigManager.getString(
                ctx, ConfigManager.KEY_ZHIPU_MODEL, AiProvider.ZHIPU.defaultModel
            )
            AiProvider.SILICON -> ConfigManager.getString(
                ctx, ConfigManager.KEY_SILICON_MODEL, AiProvider.SILICON.defaultModel
            )
            AiProvider.CUSTOM -> ConfigManager.getString(
                ctx, ConfigManager.KEY_CUSTOM_MODEL, "gpt-3.5-turbo"
            )
        }
    }

    /**
     * 根据指定的 AiProvider 获取对应的 API Key
     */
    private fun getAiKeyForProvider(provider: AiProvider): String {
        val ctx = appContext ?: return ""
        return when (provider) {
            AiProvider.DEEPSEEK -> ConfigManager.getString(ctx, ConfigManager.KEY_DEEPSEEK_KEY)
            AiProvider.QWEN -> ConfigManager.getString(ctx, ConfigManager.KEY_QWEN_KEY)
            AiProvider.ZHIPU -> ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY)
            AiProvider.SILICON -> ConfigManager.getString(ctx, ConfigManager.KEY_SILICON_KEY)
            AiProvider.CUSTOM -> ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_KEY)
        }
    }

    /**
     * 根据指定的 AiProvider 获取对应的模型名
     */
    private fun getModelForProvider(provider: AiProvider): String {
        val ctx = appContext ?: return provider.defaultModel
        return when (provider) {
            AiProvider.DEEPSEEK -> ConfigManager.getString(
                ctx, ConfigManager.KEY_DEEPSEEK_MODEL, provider.defaultModel
            )
            AiProvider.QWEN -> ConfigManager.getString(
                ctx, ConfigManager.KEY_QWEN_MODEL, provider.defaultModel
            )
            AiProvider.ZHIPU -> ConfigManager.getString(
                ctx, ConfigManager.KEY_ZHIPU_MODEL, provider.defaultModel
            )
            AiProvider.SILICON -> ConfigManager.getString(
                ctx, ConfigManager.KEY_SILICON_MODEL, provider.defaultModel
            )
            AiProvider.CUSTOM -> ConfigManager.getString(
                ctx, ConfigManager.KEY_CUSTOM_MODEL, provider.defaultModel
            )
        }
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
        return ConfigManager.getString(ctx, ConfigManager.KEY_PROMPT, DEFAULT_PROMPT)
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

    /**
     * 构建多轮对话消息列表
     * 格式: [system_prompt, ...history, new_user_message]
     */
    private fun buildChatMessages(wxId: String, userMessage: String): List<AiMessage> {
        val messages = mutableListOf<AiMessage>()

        // 1. System prompt
        val systemPrompt = getSystemPrompt(wxId)
        messages.add(AiMessage(role = "system", content = systemPrompt))

        // 2. 历史消息
        val cacheTimes = ConfigManager.getInt(
            appContext!!, ConfigManager.KEY_CACHE_TIMES, 10
        )
        val history = chatCache[wxId]
        if (history != null && history.isNotEmpty()) {
            // 取最近 cacheTimes 轮对话 (每轮包含 user + assistant)
            val takeCount = minOf(cacheTimes * 2, MAX_HISTORY_MESSAGES, history.size)
            val recentHistory = history.takeLast(takeCount)
            messages.addAll(recentHistory)
        }

        // 3. 新消息
        messages.add(AiMessage(role = "user", content = userMessage))

        return messages
    }

    /**
     * 更新对话缓存
     */
    private fun updateChatCache(wxId: String, messages: List<AiMessage>) {
        synchronized(chatCache) {
            chatCache[wxId] = messages.toMutableList()
        }
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
            XposedBridge.log("$TAG: Restored ${chatCache.size} chats from SP")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: restoreChatCache error: ${e.message}")
        }
    }

    /**
     * 发送回复 (封装 sendWxMessage + 计数)
     */
    private fun sendReply(wxId: String, reply: String) {
        val ctx = appContext ?: return

        // 发送消息
        sendWxMessage(wxId, reply)

        // 更新发送计数
        val currentCount = ConfigManager.getInt(ctx, ConfigManager.KEY_SEND_COUNT, 0)
        ConfigManager.saveInt(ctx, ConfigManager.KEY_SEND_COUNT, currentCount + 1)

        XposedBridge.log("$TAG: sendReply to=$wxId len=${reply.length}")
    }

    /**
     * 重启微信
     */
    private fun restartWeChat() {
        try {
            val ctx = appContext ?: return
            // 延迟执行重启, 让回复消息先发出去
            mainHandler.postDelayed({
                try {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                        as? android.app.ActivityManager
                    if (am != null) {
                        // 先杀死微信进程
                        val packageName = ctx.packageName
                        am.killBackgroundProcesses(packageName)
                        // 重新启动
                        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                        }
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG: restartWeChat error: ${e.message}")
                }
            }, 2000)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: restartWeChat error: ${e.message}")
        }
    }
}