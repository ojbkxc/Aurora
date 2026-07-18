package cc.aurora.bot.hook

import com.highcapable.yukihookapi.hook.xposed.annotation.XposedInit
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File

@XposedInit
class HookEntry : IYukiHookXposedInit {

    companion object {
        /** 递归扫描目录的最大深度，防止无限递归导致栈溢出 */
        private const val MAX_SCAN_DEPTH = 3
    }

    override fun onHook() = encase {
        // 加载微信进程的 hook 逻辑
        loadApp(name = "com.tencent.mm", hooker = WeChatHooker())

        // 初始化 DexKitBridge 从微信 APK 路径
        try {
            val modulePath = this.modulePath
            if (modulePath != null) {
                val apkPath = modulePath
                XposedBridge.log("AURORA: DexKit initializing from: $apkPath")

                // 尝试初始化 DexKit
                try {
                    DexKitBridge.create(apkPath!!)?.let { bridge ->
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
     * 修正了 Kotlin 中 glob 模式不生效的问题，改为手动遍历目录。
     */
    private fun tryFallbackDexKit() {
        try {
            // 尝试直接路径
            val directPath = "/data/app/com.tencent.mm/base.apk"
            if (tryInitDexKitFromPath(directPath)) {
                return
            }

            // 扫描 /data/app/ 目录下所有 com.tencent.mm-* 子目录
            // 传入空的 visited set 和初始深度 0，防止无限递归
            tryScanWeChatApkInDirectory("/data/app", mutableSetOf(), 0)
        } catch (e: Exception) {
            XposedBridge.log("AURORA: tryFallbackDexKit error: ${e.message}")
        }
    }

    /**
     * 尝试从指定路径初始化 DexKit
     * @return true 如果成功初始化
     */
    private fun tryInitDexKitFromPath(apkPath: String): Boolean {
        return try {
            val apkFile = File(apkPath)
            if (apkFile.exists() && apkFile.isFile) {
                DexKitBridge.create(apkFile.absolutePath)?.let { bridge ->
                    XposedBridge.log("AURORA: DexKit initialized from: ${apkFile.absolutePath}")
                    WeChatHooker.setDexKitBridge(bridge)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            XposedBridge.log("AURORA: DexKit init from $apkPath failed: ${e.message}")
            false
        }
    }

    /**
     * 在指定目录中扫描微信 APK。
     * 遍历父目录下的所有子目录，查找名称匹配 com.tencent.mm-* 的目录，
     * 然后检查其中的 base.apk 文件。
     *
     * @param parentPath 要扫描的父目录路径
     * @param visited 已扫描过的目录绝对路径集合，防止重复扫描和循环引用
     * @param depth 当前递归深度，达到 MAX_SCAN_DEPTH 时停止递归
     */
    private fun tryScanWeChatApkInDirectory(
        parentPath: String,
        visited: MutableSet<String>,
        depth: Int
    ) {
        // 深度限制：防止无限递归导致栈溢出
        if (depth >= MAX_SCAN_DEPTH) {
            XposedBridge.log("AURORA: max scan depth ($MAX_SCAN_DEPTH) reached at $parentPath")
            return
        }

        try {
            val parentDir = File(parentPath)
            val canonicalPath = parentDir.canonicalPath

            // 防止重复扫描同一目录（处理符号链接循环等情况）
            if (!visited.add(canonicalPath)) {
                XposedBridge.log("AURORA: skipping already visited directory: $canonicalPath")
                return
            }

            if (!parentDir.exists() || !parentDir.isDirectory) {
                XposedBridge.log("AURORA: parent directory does not exist: $parentPath")
                return
            }

            val children = parentDir.listFiles()
            if (children == null || children.isEmpty()) {
                XposedBridge.log("AURORA: no entries in $parentPath")
                return
            }

            // 筛选名称以 "com.tencent.mm-" 开头的目录
            val weChatDirs = children.filter { file ->
                file.isDirectory && file.name.startsWith("com.tencent.mm-")
            }.sortedByDescending { it.lastModified() } // 优先尝试最新的

            if (weChatDirs.isEmpty()) {
                XposedBridge.log("AURORA: no com.tencent.mm-* directories found in $parentPath")

                // 额外尝试: 也检查子目录中的子目录（某些 Android 版本嵌套更深）
                for (child in children) {
                    if (child.isDirectory) {
                        tryScanWeChatApkInDirectory(child.absolutePath, visited, depth + 1)
                    }
                }
                return
            }

            XposedBridge.log("AURORA: found ${weChatDirs.size} WeChat directories in $parentPath")

            for (weChatDir in weChatDirs) {
                val baseApk = File(weChatDir, "base.apk")
                if (baseApk.exists() && baseApk.isFile) {
                    XposedBridge.log("AURORA: trying WeChat APK: ${baseApk.absolutePath}")
                    if (tryInitDexKitFromPath(baseApk.absolutePath)) {
                        return
                    }
                }

                // 也尝试 lib 目录下的 APK 分片
                val splitApks = weChatDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".apk") && file.name.startsWith("split_")
                }
                if (splitApks != null) {
                    for (splitApk in splitApks) {
                        if (tryInitDexKitFromPath(splitApk.absolutePath)) {
                            return
                        }
                    }
                }
            }

            XposedBridge.log("AURORA: All WeChat APK paths in $parentPath failed to initialize DexKit")
        } catch (e: Exception) {
            XposedBridge.log("AURORA: tryScanWeChatApkInDirectory error: ${e.message}")
        }
    }
}