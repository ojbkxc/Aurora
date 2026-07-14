# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep YukiHookAPI classes
-keep class com.highcapable.yukihookapi.** { *; }

# Keep Xposed API
-keep class de.robv.android.xposed.** { *; }

# Keep Gson serialized classes - AI models and Wx DTO models
# Package-level keep covers all data classes in these packages
-keep class cc.aurora.bot.service.ai.** { *; }
-keep class cc.aurora.bot.service.wx.dto.** { *; }

# Keep all model data classes with SerializedName annotations
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep NanoHTTPD (package-level keep covers all nested classes)
-keep class fi.iki.elonen.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Gson
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.** { *; }
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.annotations.** { *; }
-dontwarn com.google.gson.**

# Keep DexKit
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# Keep ConfigManager and other service objects (singleton objects)
-keep class cc.aurora.bot.service.config.ConfigManager { *; }
-keep class cc.aurora.bot.service.http.HttpServer { *; }
-keep class cc.aurora.bot.service.http.HttpServer$ServerStatus { *; }
-keep class cc.aurora.bot.service.command.CommandParser { *; }

# Keep application class
-keep class cc.aurora.bot.app.DefaultApplication { *; }

# Keep hook classes
-keep class cc.aurora.bot.hook.HookEntry { *; }
-keep class cc.aurora.bot.hook.WeChatHooker { *; }
-keep class cc.aurora.bot.hook.WeChatHooker$Companion { *; }

# Keep MainActivity
-keep class cc.aurora.bot.ui.MainActivity { *; }