package cc.aurora.bot.hook

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    companion object {
        private const val MAX_SCAN_DEPTH = 3
    }

    override fun onHook() {
        // 先尝试从模块路径初始化 DexKit（备用方案）
        tryInitDexKitFromModule()
        // 注册 WeChatHooker
        YukiHookAPI.encase(WeChatHooker())
    }

    /**
     * 尝试从模块路径初始化 DexKitBridge
     */
    private fun tryInitDexKitFromModule() {
        try {
            val apkPath = "/data/app/com.tencent.mm/base.apk"
            if (tryInitDexKitFromPath(apkPath)) return

            tryScanWeChatApkInDirectory("/data/app", mutableSetOf(), 0)
        } catch (e: Exception) {
            XposedBridge.log("AURORA: tryInitDexKitFromModule error: ${e.message}")
        }
    }

    private fun tryInitDexKitFromPath(apkPath: String): Boolean {
        return try {
            val apkFile = File(apkPath)
            if (apkFile.exists() && apkFile.isFile) {
                val bridge = DexKitBridge.create(apkFile.absolutePath)
                if (bridge != null) {
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

    private fun tryScanWeChatApkInDirectory(
        parentPath: String,
        visited: MutableSet<String>,
        depth: Int
    ) {
        if (depth >= MAX_SCAN_DEPTH) return
        try {
            val parentDir = File(parentPath)
            val canonicalPath = parentDir.canonicalPath
            if (!visited.add(canonicalPath)) return
            if (!parentDir.exists() || !parentDir.isDirectory) return

            val children = parentDir.listFiles() ?: return
            val weChatDirs = children.filter { it.isDirectory && it.name.startsWith("com.tencent.mm-") }
                .sortedByDescending { it.lastModified() }

            if (weChatDirs.isEmpty()) {
                for (child in children) {
                    if (child.isDirectory) tryScanWeChatApkInDirectory(child.absolutePath, visited, depth + 1)
                }
                return
            }

            for (weChatDir in weChatDirs) {
                val baseApk = File(weChatDir, "base.apk")
                if (baseApk.exists() && tryInitDexKitFromPath(baseApk.absolutePath)) return
                weChatDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") && f.name.startsWith("split_") }
                    ?.forEach { if (tryInitDexKitFromPath(it.absolutePath)) return }
            }
        } catch (e: Exception) {
            XposedBridge.log("AURORA: tryScanWeChatApkInDirectory error: ${e.message}")
        }
    }
}