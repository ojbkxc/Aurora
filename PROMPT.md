# Aurora 项目开发指南

## 项目概述
Aurora 是一个基于 Xposed/LSPosed (YukiHookAPI) 框架的微信 AI 自动回复机器人模块。通过 Hook 微信进程实现消息拦截，调用 AI 大模型 API 进行智能回复，支持多轮对话、指令系统、订阅推送、HTTP API 等功能。

## 技术栈
- **语言**: Kotlin
- **框架**: YukiHookAPI 1.2.1 + KSP Xposed 注解处理器
- **核心库**: DexKit 2.0.1, OkHttp 4.12.0, NanoHTTPD 2.3.1, Gson 2.10.1, Kotlin Coroutines 1.7.3
- **构建**: Gradle 8.2, AGP 8.2.0, Kotlin 1.9.22, KSP 1.9.22-1.0.17
- **最低 SDK**: 26 (Android 8.0), 目标 SDK: 34

## 目标应用
- **包名**: `com.tencent.mm` (微信)
- **参考 APK**: 微信 arm64 APK（需自行从手机提取或从官方渠道下载）
- **架构**: 通过 YukiHookAPI 注入微信主进程

## 项目结构
```
Aurora/
├── app/
│   ├── build.gradle.kts          # 模块构建配置 (versionCode, versionName, 依赖)
│   ├── proguard-rules.pro        # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 应用清单 (Xposed 模块声明)
│       ├── assets/xposed_init    # Xposed 入口: cc.aurora.bot.hook.HookEntry
│       ├── java/cc/aurora/bot/
│       │   ├── app/
│       │   │   └── DefaultApplication.kt  # Application 初始化
│       │   ├── hook/
│       │   │   ├── HookEntry.kt           # Xposed 入口 (YukiHookAPI)
│       │   │   ├── WeChatHooker.kt        # 核心: 消息处理+AI回复+指令+HTTP+定时器
│       │   │   ├── MessageReceiver.kt     # 消息接收: 五层Hook策略拦截微信消息
│       │   │   └── MessageSender.kt       # 消息发送: 四种策略发送微信消息
│       │   ├── service/
│       │   │   ├── ai/AiService.kt        # AI 调用服务 (DeepSeek/通义千问/智谱/硅基流动/自定义)
│       │   │   ├── command/CommandParser.kt  # 指令解析器 (30+ 种指令)
│       │   │   ├── config/ConfigManager.kt   # 配置管理
│       │   │   ├── http/HttpServer.kt        # HTTP API 服务器 (端口 5888)
│       │   │   └── wx/dto/Models.kt          # 数据模型
│       │   └── ui/
│       │       └── MainActivity.kt  # 代码动态构建 UI 的配置界面
│       └── res/values/ (strings.xml, themes.xml)
├── .github/workflows/build.yml   # GitHub Actions 自动编译
└── README.md
```

## 核心架构
```
Xposed 框架加载
    │
    ▼
HookEntry (IYukiHookXposedInit)
    ├── 初始化 DexKitBridge (从微信 APK)
    └── 加载 WeChatHooker
            │
            ├── onHook() → Hook Application.attachBaseContext
            │       └── onApplicationAttached()
            │           ├── 启动 HTTP Server (NanoHTTPD :5888)
            │           ├── 初始化消息拦截 (DexKit + 已知路径 + 字符串特征)
            │           ├── 恢复对话缓存 (SP → 内存)
            │           └── 启动定时器 (订阅推送/缓存刷新/数据上报)
            │
            ├── 消息处理: 收到消息 → 反射提取 wxId + content
            │   ├── 匹配指令 (#开头) → CommandParser → 执行对应操作
            │   └── 匹配触发词 → AiService.chat() → OkHttp → AI API → 回复
            │
            └── HTTP API 回调 (sendMessage/status/bindings/subscriptions)
```

## 支持的 AI 厂商
- DeepSeek (api.deepseek.com)
- 通义千问 DashScope (dashscope.aliyuncs.com)
- 智谱 BigModel (open.bigmodel.cn)
- 硅基流动 SiliconFlow (api.siliconflow.cn)
- 自定义 OpenAI 兼容 API

## 微信 APK 兼容性验证
已验证 `C:\GitHub\po\weixin_arm64.apk`：
- 大小: 167.4 MB (17 个 dex 文件)
- 包名: `com.tencent.mm`
- 关键目标类全部存在: MMApplicationLike, SelectContactUI, MsgInfo, SnsObject, ConversationInfo, NotificationManager

## 版本管理
- 版本号定义在 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`
- 当前版本: v1.0.1 (versionCode: 101)

## 构建与发布
- 本地编译: `./gradlew assembleRelease`
- GitHub Actions: 在 Actions 页面点击 "Build APK" → "Run workflow" 即可触发编译
- 编译产物: `app/build/outputs/apk/release/`
- 发布: 在 GitHub Releases 页面手动上传 APK

## 修改代码注意事项
- 版本号变更需修改 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`
- 新增 AI 厂商需在 `AiService.kt` 中添加对应的 API URL 和请求格式
- 新增指令需在 `CommandParser.kt` 中添加解析逻辑，并在 `WeChatHooker.kt` 中添加处理分支
- 修改 `WeChatHooker.kt` 时注意: 这是核心文件，所有 Hook 逻辑、定时器、HTTP 路由都在此文件中
- 消息拦截采用五层策略: DexKit doRevokeMsg → DexKit 字段匹配 → 已知真实类名 → 已知路径模式 → 字符串特征枚举
- 消息发送采用四种策略: 真实内部类 → DexKit 动态查找 → 已知路径 → 反射搜索