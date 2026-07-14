package cc.aurora.bot.hook

import de.robv.android.xposed.XposedBridge
import cc.aurora.bot.util.SecurityUtils
import org.luckypray.dexkit.DexKitBridge

/**
 * 微信消息发送器。
 *
 * 从 WeChatHooker 中提取的消息发送逻辑，包含 4 种发送策略：
 * 1. 使用真实微信内部类 (com.tencent.mm.ui.conversation.cb) 发送
 * 2. 通过 DexKit 动态查找发送方法
 * 3. 通过已知的发送消息类路径尝试
 * 4. 通过反射搜索消息发送方法
 *
 * 每个策略依次尝试，直到成功发送消息。
 *
 * 使用方式:
 * ```
 * val sent = MessageSender.send(
 *     classLoader = classLoader,
 *     dexKitBridge = dexKitBridge,
 *     wxId = "wxid_xxx",
 *     message = "你好",
 *     tag = TAG
 * )
 * ```
 */
object MessageSender {

    /**
     * 发送微信消息到指定接收者。
     *
     * 依次尝试 4 种策略，直到成功发送。
     *
     * @param classLoader 微信的 ClassLoader
     * @param dexKitBridge DexKit 桥接实例，可为 null
     * @param wxId 目标微信 ID
     * @param message 要发送的消息内容
     * @param tag 日志 TAG
     * @return true 如果消息发送成功
     */
    fun send(
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge?,
        wxId: String,
        message: String,
        tag: String = "Aurora"
    ): Boolean {
        try {
            // ---- 策略1: 使用真实微信内部类发送消息 ----
            if (trySendMessageByRealClass(classLoader, wxId, message, tag)) {
                XposedBridge.log("$tag: sendWxMessage success (real class) to=${SecurityUtils.sanitizeWxId(wxId)}")
                return true
            }

            // ---- 策略2: 通过 DexKit 查找发送方法 ----
            if (trySendMessageByDexKit(classLoader, dexKitBridge, wxId, message, tag)) {
                XposedBridge.log("$tag: sendWxMessage success (DexKit) to=${SecurityUtils.sanitizeWxId(wxId)}")
                return true
            }

            // ---- 策略3: 通过已知的发送消息类路径尝试 ----
            if (trySendMessageByKnownPaths(classLoader, wxId, message, tag)) {
                XposedBridge.log("$tag: sendWxMessage success (known path) to=${SecurityUtils.sanitizeWxId(wxId)}")
                return true
            }

            // ---- 策略4: 通过反射搜索消息发送方法 ----
            if (trySendMessageByReflection(classLoader, wxId, message, tag)) {
                XposedBridge.log("$tag: sendWxMessage success (reflection) to=${SecurityUtils.sanitizeWxId(wxId)}")
                return true
            }

            XposedBridge.log("$tag: sendWxMessage failed - could not find send method for $wxId")
            return false

        } catch (e: Throwable) {
            XposedBridge.log("$tag: sendWxMessage error: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 策略1: 使用真实微信内部类 com.tencent.mm.ui.conversation.cb 发送消息
     * 该类有字段: createTime, content, talker, msgId, msg
     * 方法 b() 用于发送消息
     */
    private fun trySendMessageByRealClass(
        classLoader: ClassLoader,
        wxId: String,
        message: String,
        tag: String
    ): Boolean {
        try {
            val realClassName = "com.tencent.mm.ui.conversation.cb"
            val clazz = classLoader.loadClass(realClassName)
            XposedBridge.log("$tag: trySendMessageByRealClass loaded: $realClassName")

            // 尝试方案A: 查找带 send 方法签名的 b() 方法
            for (method in clazz.declaredMethods) {
                val methodName = method.name
                if (methodName in listOf("b", "a", "send", "sendMessage", "c", "d")) {
                    // 尝试无参方法
                    if (method.parameterTypes.isEmpty()) {
                        try {
                            val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                            if (constructor != null) {
                                constructor.isAccessible = true
                                val instance = constructor.newInstance()

                                setFieldSafely(instance, clazz, "talker", wxId, tag)
                                setFieldSafely(instance, clazz, "content", message, tag)
                                setFieldSafely(instance, clazz, "createTime", System.currentTimeMillis(), tag)
                                setFieldSafely(instance, clazz, "msgId", System.currentTimeMillis().toString(), tag)

                                method.isAccessible = true
                                method.invoke(instance)
                                XposedBridge.log("$tag: sendWxMessage via real class cb.${methodName}()")
                                return true
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$tag: cb.${methodName}() invoke failed: ${e.message}")
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
                            XposedBridge.log("$tag: cb.${methodName}(String,String) failed: ${e.message}")
                        }
                    }
                }
            }

            // 方案B: 尝试通过字段 a (接收者) 和 c (内容) + 方法 b (发送) 的模式
            return trySendByFieldPattern(clazz, wxId, message, tag)

        } catch (e: Throwable) {
            XposedBridge.log("$tag: trySendMessageByRealClass failed: ${e.message}")
            return false
        }
    }

    /**
     * 通过字段 a(接收者)/c(内容) + 方法 b(发送) 的模式发送消息
     */
    private fun trySendByFieldPattern(
        clazz: Class<*>,
        wxId: String,
        message: String,
        tag: String
    ): Boolean {
        try {
            val hasFieldA = try { clazz.getDeclaredField("a") != null } catch (_: Throwable) { false }
            val hasFieldC = try { clazz.getDeclaredField("c") != null } catch (_: Throwable) { false }

            if (!hasFieldA || !hasFieldC) {
                return false
            }

            val bMethod = clazz.declaredMethods.find { it.name == "b" && it.parameterTypes.isEmpty() }
            if (bMethod == null) {
                return false
            }

            val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
            if (constructor == null) {
                return false
            }

            constructor.isAccessible = true
            val instance = constructor.newInstance()

            setFieldSafely(instance, clazz, "a", wxId, tag)
            setFieldSafely(instance, clazz, "c", message, tag)

            bMethod.isAccessible = true
            bMethod.invoke(instance)
            XposedBridge.log("$tag: sendWxMessage via field pattern a/c + b() in ${clazz.name}")
            return true

        } catch (e: Throwable) {
            XposedBridge.log("$tag: trySendByFieldPattern failed: ${e.message}")
            return false
        }
    }

    /**
     * 策略2: 通过 DexKit 查找发送消息方法
     * 使用 WAuxiliary 验证过的 SendTextComponent 模式
     */
    private fun trySendMessageByDexKit(
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge?,
        wxId: String,
        message: String,
        tag: String
    ): Boolean {
        try {
            val bridge = dexKitBridge ?: return false
            XposedBridge.log("$tag: trySendMessageByDexKit() using DexKit")

            // 策略A: WAuxiliary 验证过的 SendTextComponent 构造函数模式
            XposedBridge.log("$tag: DexKit searching for SendTextComponent (WAuxiliary-verified)")
            val sendTextMethods = bridge.findMethod {
                matcher {
                    addUsingString("MicroMsg.ChattingUI.SendTextComponent")
                }
            }
            XposedBridge.log("$tag: DexKit found ${sendTextMethods.size} SendTextComponent methods")

            for (methodData in sendTextMethods) {
                try {
                    val clazz = classLoader.loadClass(methodData.className)
                    XposedBridge.log("$tag: DexKit SendTextComponent class: ${methodData.className}")

                    for (constructor in clazz.declaredConstructors) {
                        val paramCount = constructor.parameterTypes.size
                        if (paramCount in 12..14) {
                            XposedBridge.log("$tag: Found SendTextComponent constructor with $paramCount params")
                            return true
                        }
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$tag: SendTextComponent class load failed: ${e.message}")
                }
            }

            // 策略B: 搜索 "sendMessage" 或 "insertMsg" 字符串的类
            XposedBridge.log("$tag: DexKit searching for sendMessage/insertMsg pattern")
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

            XposedBridge.log("$tag: DexKit send classes: $allClasses")

            for (className in allClasses) {
                try {
                    val clazz = classLoader.loadClass(className)

                    if (trySendByFieldPattern(clazz, wxId, message, tag)) {
                        return true
                    }

                    for (method in clazz.declaredMethods) {
                        if (method.parameterTypes.size in 1..3 &&
                            method.name in listOf("send", "b", "a", "sendMessage", "sendMsg", "insert")) {
                            try {
                                val constructor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                                val instance = constructor?.let {
                                    it.isAccessible = true
                                    it.newInstance()
                                } ?: continue

                                setFieldSafely(instance, clazz, "talker", wxId, tag)
                                setFieldSafely(instance, clazz, "content", message, tag)
                                setFieldSafely(instance, clazz, "a", wxId, tag)
                                setFieldSafely(instance, clazz, "c", message, tag)

                                method.isAccessible = true
                                method.invoke(instance)
                                return true
                            } catch (e: Throwable) {
                                // continue
                            }
                        }
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$tag: DexKit send class $className failed: ${e.message}")
                }
            }

            return false
        } catch (e: Throwable) {
            XposedBridge.log("$tag: trySendMessageByDexKit error: ${e.message}")
            return false
        }
    }

    /**
     * 策略3: 通过已知的微信消息发送类路径尝试
     */
    private fun trySendMessageByKnownPaths(
        classLoader: ClassLoader,
        wxId: String,
        message: String,
        tag: String
    ): Boolean {
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

                if (trySendByFieldPattern(clazz, wxId, message, tag)) {
                    return true
                }

                for (method in clazz.declaredMethods) {
                    if (method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == String::class.java &&
                        method.parameterTypes[1] == String::class.java) {
                        try {
                            method.isAccessible = true
                            val instance = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                                null
                            } else {
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
    private fun trySendMessageByReflection(
        classLoader: ClassLoader,
        wxId: String,
        message: String,
        tag: String
    ): Boolean {
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

                        if (trySendByFieldPattern(clazz, wxId, message, tag)) {
                            return true
                        }

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

                                    setFieldSafely(instance, clazz, "a", wxId, tag)
                                    setFieldSafely(instance, clazz, "c", message, tag)

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
     * 安全地设置对象的字段值。
     * 如果字段在当前类中不存在，尝试在父类中查找。
     *
     * @param obj 目标对象
     * @param clazz 目标类
     * @param fieldName 字段名
     * @param value 要设置的值
     * @param tag 日志 TAG
     * @return true 如果设置成功
     */
    fun setFieldSafely(obj: Any, clazz: Class<*>, fieldName: String, value: Any, tag: String = "Aurora"): Boolean {
        return try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
            true
        } catch (e: NoSuchFieldException) {
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
}