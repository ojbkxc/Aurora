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

# Keep Gson serialized classes
-keep class cc.aurora.bot.service.ai.** { *; }
-keep class cc.aurora.bot.service.wx.dto.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.NanoHTTPD { *; }
