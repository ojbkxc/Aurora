package cc.aurora.bot.hook

import android.os.Build
import com.highcapable.yukihookapi.annotation.XposedInit
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File

@XposedInit
class HookEntry : IYukiHookXposedInit {
    override fun onHook() {
        // 加载微信进程的 hook 逻辑
        loadAppHooker(WeChatHooker())

        // 初始化 DexKitBridge 从微信 APK 路径
        try {
            val modulePath = startupParam?.modulePath
            if (modulePath != null) {
                val apkPath = modulePath
                XposedBridge.log("AURORA: DexKit initializing from: $apkPath")

                // 尝试初始化 DexKit
                try {
                    DexKitBridge.create(apkPath)?.let { bridge ->
                        XposedBridge.log("AURORA: DexKit initialized successfully")
                        // 将 bridge 传递给 WeChatHooker
                        WeChatHooker.setDexKitBridge(bridge)
                    } ?: run {
                        XposedBridge.log("AURORA: DexKitBridge.create returned null, trying fallback")
                        // 备用: 尝试从包名对应的 APK 路径
                        tryFallbackDexKit()
                    }
                } catch (e: Exception) {
                    XposedBridge.log("AURORA: DexKit create failed: ${e.message}, trying fallback")
                    tryFallbackDexKit()
                }
            } else {
                XposedBridge.log("AURORA: modulePath is null, trying fallback DexKit init")
                tryFallbackDexKit()
            }
        } catch (e: Exception) {
            XposedBridge.log("AURORA: DexKit initialization error: ${e.message}")
        }
    }

    /**
     * 备用 DexKit 初始化: 尝试从常见路径加载微信 APK
     */
    private fun tryFallbackDexKit() {
        try {
            val possiblePaths = listOf(
                "/data/app/com.tencent.mm-*/base.apk",
                "/data/app/com.tencent.mm/base.apk",
            )

            for (pathPattern in possiblePaths) {
                try {
                    if (pathPattern.contains("*")) {
                        val parentDir = File(pathPattern.substringBeforeLast("/"))
                        if (parentDir.exists()) {
                            val matched = parentDir.listFiles { dir, name ->
                                name.startsWith("com.tencent.mm-") && dir.isDirectory
                            }
                            if (matched != null && matched.isNotEmpty()) {
                                val apkFile = File(matched[0], "base.apk")
                                if (apkFile.exists()) {
                                    DexKitBridge.create(apkFile.absolutePath)?.let { bridge ->
                                        XposedBridge.log("AURORA: DexKit fallback initialized from: ${apkFile.absolutePath}")
                                        WeChatHooker.setDexKitBridge(bridge)
                                        return
                                    }
                                }
                            }
                        }
                    } else {
                        val apkFile = File(pathPattern)
                        if (apkFile.exists()) {
                            DexKitBridge.create(apkFile.absolutePath)?.let { bridge ->
                                XposedBridge.log("AURORA: DexKit fallback initialized from: $pathPattern")
                                WeChatHooker.setDexKitBridge(bridge)
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    XposedBridge.log("AURORA: DexKit fallback path $pathPattern failed: ${e.message}")
                }
            }
            XposedBridge.log("AURORA: All DexKit fallback paths failed")
        } catch (e: Exception) {
            XposedBridge.log("AURORA: tryFallbackDexKit error: ${e.message}")
        }
    }
}