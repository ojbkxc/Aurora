package cc.aurora.bot.hook

import de.robv.android.xposed.XposedBridge
import cc.aurora.bot.util.SecurityUtils
import org.luckypray.dexkit.DexKitBridge

/**
 * 微信消息接收器。
 *
 * 从 WeChatHooker 中提取的消息接收/拦截逻辑，负责 Hook 微信内部的消息处理类，
 * 接收新消息并提取发送者 wxId 和消息内容。
 *
 * 包含 3 种 Hook 策略：
 * 1. 使用 DexKit 动态查找消息处理类
 * 2. 通过已知路径 Hook
 * 3. 通过字符串特征查找消息处理类
 *
 * 使用方式:
 * ```
 * val receiver = MessageReceiver
 * receiver.init(
 *     classLoader = classLoader,
 *     dexKitBridge = dexKitBridge,
 *     onMessageReceived = { wxId, content -> processMessage(wxId, content) },
 *     tag = TAG
 * )
 * ```
 */
object MessageReceiver {

    /**
     * 消息接收回调接口。
     */
    fun interface MessageCallback {
        /**
         * 当收到新消息时调用。
         *
         * @param wxId 发送者微信 ID
         * @param content 消息内容
         */
        fun onMessageReceived(wxId: String, content: String)
    }

    @Volatile
    private var receiveClass: Class<*>? = null
    @Volatile
    private var receiveMethod: java.lang.reflect.Method? = null

    private var classLoader: ClassLoader? = null
    private var dexKitBridge: DexKitBridge? = null
    private var messageCallback: MessageCallback? = null
    private var tag: String = "Aurora"

    /**
     * 初始化消息接收器并开始 Hook。
     *
     * @param cl 微信的 ClassLoader
     * @param bridge DexKit 桥接实例，可为 null
     * @param callback 消息接收回调
     * @param logTag 日志 TAG
     */
    fun init(
        cl: ClassLoader,
        bridge: DexKitBridge?,
        callback: MessageCallback,
        logTag: String = "Aurora"
    ) {
        this.classLoader = cl
        this.dexKitBridge = bridge
        this.messageCallback = callback
        this.tag = logTag

        initMessageInterceptor()
    }

    /**
     * 获取已 Hook 的接收类。
     */
    fun getReceiveClass(): Class<*>? = receiveClass

    /**
     * 获取已 Hook 的接收方法。
     */
    fun getReceiveMethod(): java.lang.reflect.Method? = receiveMethod

    // ===================== 消息拦截初始化 =====================

    private fun initMessageInterceptor() {
        XposedBridge.log("$tag: initMessageInterceptor() start")

        // ---- 策略1: 使用 DexKit 动态查找消息处理类 ----
        if (tryHookMessageByDexKit()) {
            XposedBridge.log("$tag: initMessageInterceptor() done via DexKit")
            return
        }

        // ---- 策略2: 尝试已知路径 Hook ----
        tryHookMessageByKnownPaths()

        // ---- 策略3: 通过字符串特征查找消息处理类 ----
        tryHookMessageByStringFeature()

        XposedBridge.log("$tag: initMessageInterceptor() done (fallback strategies)")
    }

    /**
     * 策略1: 使用 DexKit 动态查找微信消息处理类
     * 搜索包含 "msgId" 和 "talker" 字符串常量的方法
     */
    private fun tryHookMessageByDexKit(): Boolean {
        try {
            val bridge = dexKitBridge ?: return false
            val cl = classLoader ?: return false
            XposedBridge.log("$tag: tryHookMessageByDexKit() using DexKit")

            // 策略A: 使用 WAuxiliary 验证过的 doRevokeMsg 模式查找消息处理类
            XposedBridge.log("$tag: DexKit searching for doRevokeMsg pattern (WAuxiliary-verified)")
            val revokeMethods = bridge.findMethod {
                matcher {
                    addUsingString("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
                }
            }
            XposedBridge.log("$tag: DexKit found ${revokeMethods.size} revoke methods")

            for (methodData in revokeMethods) {
                try {
                    val clazz = cl.loadClass(methodData.className)
                    val method = methodData.getMethodInstance(cl)
                    XposedBridge.log("$tag: DexKit found revoke class: ${methodData.className}.${methodData.name}")

                    hookMethodByReflection(clazz, method)
                    return true
                } catch (e: Throwable) {
                    XposedBridge.log("$tag: DexKit revoke hook failed for ${methodData.className}: ${e.message}")
                }
            }

            // 策略B: 搜索 "msgId" 和 "talker" 字符串 (传统方法)
            XposedBridge.log("$tag: DexKit searching for msgId+talker pattern")
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

            XposedBridge.log("$tag: DexKit common classes with msgId+talker: ${commonClasses.size}")

            for (className in commonClasses) {
                try {
                    val clazz = cl.loadClass(className)
                    for (method in clazz.declaredMethods) {
                        if (method.parameterTypes.isNotEmpty()) {
                            try {
                                method.isAccessible = true
                                receiveClass = clazz
                                receiveMethod = method
                                hookMethodByReflection(clazz, method)
                                XposedBridge.log("$tag: DexKit hooked method: ${method.name} in $className")
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

            // 策略C: 使用已知的真实类名
            return tryHookRealWeChatClasses(cl)

        } catch (e: Throwable) {
            XposedBridge.log("$tag: tryHookMessageByDexKit error: ${e.message}")
            return false
        }
    }

    /**
     * 尝试 Hook 已知的真实微信类名
     */
    private fun tryHookRealWeChatClasses(cl: ClassLoader): Boolean {
        XposedBridge.log("$tag: tryHookRealWeChatClasses()")

        val realClassNames = listOf(
            "com.tencent.mm.ui.chatting.presenter.n",
            "com.tencent.mm.storage.MsgInfo",
            "com.tencent.mm.ui.conversation.cb",
            "com.tencent.mm.app.MMApplicationLike",
        )

        for (className in realClassNames) {
            try {
                val clazz = cl.loadClass(className)
                XposedBridge.log("$tag: Found real class: $className")

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
                        receiveClass = clazz
                        receiveMethod = method
                        hookMethodByReflection(clazz, method)
                        XposedBridge.log("$tag: Hooked real class method: ${method.name} in $className")
                        return true
                    } catch (e: Throwable) {
                        XposedBridge.log("$tag: Hook failed for ${method.name} in $className: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("$tag: Real class not found: $className: ${e.message}")
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
                        val messageObj = if (param.args.size == 1) {
                            param.args[0]
                        } else {
                            param.thisObject ?: param.args.firstOrNull()
                        }
                        tryExtractAndProcess(messageObj)
                    } catch (e: Throwable) {
                        XposedBridge.log("$tag: afterHookedMethod error: ${e.message}")
                    }
                }
            })
            XposedBridge.log("$tag: hookMethodByReflection success: ${clazz.name}.${method.name}")
        } catch (e: Throwable) {
            XposedBridge.log("$tag: hookMethodByReflection failed: ${e.message}")
        }
    }

    /**
     * 策略2: 通过已知的微信消息处理类路径尝试 Hook
     */
    private fun tryHookMessageByKnownPaths() {
        val knownClassPatterns = listOf(
            "com.tencent.mm.plugin.messenger.foundation.impl.IMessageEvent",
            "com.tencent.mm.plugin.messenger.foundation.a",
            "com.tencent.mm.plugin.messenger.foundation.b",
            "com.tencent.mm.plugin.messenger.foundation.c",
            "com.tencent.mm.pluginsdk.model.app.MMAppMsgHandler\$a",
            "com.tencent.mm.ui.chatting.ChattingUIFragment\$a",
            "com.tencent.mm.sdk.platformtools.MMMessageHandler",
            "com.tencent.mm.ui.chatting.presenter.n",
            "com.tencent.mm.storage.MsgInfo",
            "com.tencent.mm.ui.conversation.cb",
        )

        val cl = classLoader ?: return

        for (className in knownClassPatterns) {
            try {
                val clazz = cl.loadClass(className)

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
                                XposedBridge.log("$tag: Hook success on known class: $className -> ${method.name}")
                                return
                            } catch (e: Throwable) {
                                // 继续尝试
                            }
                        }
                    }
                }
                XposedBridge.log("$tag: Hook class $className found but no suitable method")
            } catch (e: Throwable) {
                XposedBridge.log("$tag: Hook failed on $className: ${e.message}")
            }
        }
    }

    /**
     * 策略3: 通过枚举所有类, 按字符串特征查找消息处理类
     */
    private fun tryHookMessageByStringFeature() {
        try {
            val cl = classLoader ?: return

            val targetClasses = findClassesWithMethod(cl, "onNewMessage", 1)

            for (clazz in targetClasses) {
                try {
                    if (clazz.name.contains("tencent.mm")) {
                        hookMethodByReflection(clazz, clazz.getDeclaredMethod("onNewMessage", clazz.declaredMethods[0].parameterTypes[0]))
                        XposedBridge.log("$tag: Hook success by feature on: ${clazz.name}")
                        return
                    }
                } catch (e: Throwable) {
                    // 继续尝试下一个
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$tag: tryHookMessageByStringFeature failed: ${e.message}")
        }
    }

    /**
     * 从 args[0] 消息对象中反射提取 wxId 和消息内容, 然后处理
     */
    private fun tryExtractAndProcess(messageObj: Any?) {
        if (messageObj == null) return
        try {
            val clazz = messageObj.javaClass

            val wxId = extractFieldValue(messageObj, clazz, listOf(
                "field_username", "field_talker", "talker",
                "mUsername", "mSender", "m_fromUsername",
                "fromUsername", "senderId", "mTalker", "field_talker"
            )) ?: run {
                XposedBridge.log("$tag: Could not extract wxId from ${clazz.name}")
                return
            }

            val content = extractFieldValue(messageObj, clazz, listOf(
                "field_content", "content", "field_msgContent",
                "mContent", "mMessage", "mMsg",
                "message", "m_content", "m_message"
            )) ?: run {
                XposedBridge.log("$tag: Could not extract content from ${clazz.name}")
                return
            }

            if (content.isBlank()) return
            if (content.startsWith("<![CDATA[") && content.contains("sysmsg")) return

            val safeWxId = SecurityUtils.sanitizeWxId(wxId)
            val safeContent = SecurityUtils.sanitizeInput(content, 5000)
            XposedBridge.log("$tag: Received msg from=$safeWxId content=${safeContent.take(50)}")
            messageCallback?.onMessageReceived(wxId, safeContent)

        } catch (e: Throwable) {
            XposedBridge.log("$tag: tryExtractAndProcess error: ${e.message}")
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

                    if (fieldNames.any { it.contains("username") || it.contains("talker") || it.contains("sender") }) {
                        if (str.startsWith("wxid_") || str.contains("@chatroom")) return str
                    }
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
}