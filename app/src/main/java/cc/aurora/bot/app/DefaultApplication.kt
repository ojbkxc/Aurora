package cc.aurora.bot.app

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import cc.aurora.bot.service.config.ConfigManager

class DefaultApplication : Application() {

    companion object {
        private const val TAG = "Aurora"

        @Volatile
        @JvmStatic
        var instance: DefaultApplication? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化日志：打印应用和版本信息
        logAppInfo()

        // 初始化 ConfigManager 迁移
        ConfigManager.migrateIfNeeded(this)

        Log.d(TAG, "Aurora Application initialized successfully")
    }

    /**
     * 打印应用版本和设备信息
     */
    private fun logAppInfo() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "Aurora Application starting")
            Log.d(TAG, "  Version: $versionName ($versionCode)")
            Log.d(TAG, "  Package: $packageName")
            Log.d(TAG, "  Android SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            Log.d(TAG, "  Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            Log.d(TAG, "========================================")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Failed to get app version info: ${e.message}")
            Log.d(TAG, "Aurora Application starting (version unknown)")
        }
    }
}