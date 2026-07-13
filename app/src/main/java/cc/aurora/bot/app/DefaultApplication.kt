package cc.aurora.bot.app

import android.app.Application
import android.util.Log

class DefaultApplication : Application() {

    companion object {
        private const val TAG = "Aurora"
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化日志
        Log.d(TAG, "Aurora Application initialized")
    }
}
