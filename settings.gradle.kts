pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JCenter 镜像 (Xposed API 等旧依赖)
        maven("https://maven.aliyun.com/repository/public") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.tiann")
            }
        }
    }
}

rootProject.name = "Aurora"
include(":app")
