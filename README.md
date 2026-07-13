# Aurora

> 微信 AI 自动回复机器人 - 支持 DeepSeek / 通义千问 / 智谱 / 硅基流动 / 自定义 OpenAI 兼容 API

[![Version](https://img.shields.io/badge/version-1.0.1-blue)](https://github.com/HdShare/Aurora/releases)
[![API](https://img.shields.io/badge/Xposed_API-93-green)](https://api.xposed.info/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://developer.android.com/)

## 简介

Aurora 是一个基于 Xposed/LSPosed (YukiHookAPI) 框架的微信 AI 自动回复机器人模块。通过 Hook 微信进程实现消息拦截，调用 AI 大模型 API 进行智能回复，支持多轮对话、指令系统、订阅推送、HTTP API 等功能。

## 功能特性

### AI 自动回复
- 支持 **DeepSeek**、**通义千问 (DashScope)**、**智谱 (BigModel)**、**硅基流动 (SiliconFlow)** 及自定义 OpenAI 兼容 API
- 多轮对话上下文缓存，支持可配置的缓存轮数
- 可自定义触发词和系统提示词 (System Prompt)
- 按厂商指定 AI 回复（如 `#AI:DS:` 指定 DeepSeek 回复）

### 指令系统 (30+ 种指令)
| 类别 | 指令示例 |
|------|----------|
| 绑定管理 | `#绑定聊天室`、`#解绑聊天室`、`#当前聊天室` |
| AI 配置 | `#deepseekKey:`、`#模型:`、`#触发:`、`#缓存:` |
| 订阅管理 | `#订阅:`、`#取消订阅:`、`#当前订阅` |
| 调教 | `#调教`、`#默认调教` |
| 群欢迎语 | `#进群欢迎语:`、`#查询本群欢迎语` |
| 开发者模式 | `#开发模式`、`#关闭开发模式` |
| 系统 | `#机器人信息`、`#API说明`、`#重启机器人` |

### HTTP API 服务器 (端口 5888)
- `GET /health` - 健康检查
- `GET /status` - 机器人状态
- `GET /bindings` - 绑定聊天室列表
- `GET /subscriptions` - 订阅列表
- `POST /send?wxIds=xxx&msg=xxx` - 主动发送消息

### 其他功能
- 每日定时订阅推送
- 群欢迎语自动回复
- 消息数据统计上报
- 对话缓存持久化

## 兼容性

- **框架**: Xposed / LSPosed / LSPatch
- **最低 Android 版本**: 8.0 (API 26)
- **目标微信版本**: 需配合 DexKit 动态适配微信混淆，理论上兼容多种微信版本

## 技术架构

项目采用 **YukiHookAPI + DexKit** 作为核心 Hook 框架，通过三层策略定位微信消息处理类和方法：

```
Xposed 框架加载
    │
    ▼
HookEntry (IYukiHookXposedInit)
    ├── 初始化 DexKitBridge (从微信 APK)
    └── 加载 WeChatHooker
            │
            ├── onHook() → Hook Application.attachBaseContext
            │       │
            │       └── onApplicationAttached()
            │               ├── 启动 HTTP Server (NanoHTTPD :5888)
            │               ├── 初始化消息拦截 (DexKit + 已知路径 + 字符串特征)
            │               ├── 恢复对话缓存 (SP → 内存)
            │               └── 启动定时器 (订阅推送/缓存刷新/数据上报)
            │
            ├── 消息处理流程:
            │   收到消息 → 反射提取 wxId + content
            │       ├── 匹配指令 (#开头) → CommandParser → 执行对应操作
            │       └── 匹配触发词 → AiService.chat() → OkHttp → AI API → 回复
            │
            └── HTTP API 回调 (sendMessage/status/bindings/subscriptions)
```

## 使用方法

### 安装

1. 确保已安装 Xposed/LSPosed 框架
2. 下载并安装 Aurora APK
3. 在 Xposed/LSPosed 管理器中启用 Aurora 模块
4. 勾选作用域：**微信 (com.tencent.mm)**
5. 重启微信生效

### 配置 AI

1. 打开 Aurora 应用，配置至少一个 AI 厂商的 API Key
2. 配置触发词（默认：机器人）
3. 点击"保存配置"
4. 在微信中发送触发词 + 消息，如 `机器人 你好`

### 指令使用

在微信中发送 `#` 开头的指令消息即可，例如：
- `#绑定聊天室` - 将当前聊天室绑定为 AI 回复目标
- `#API说明` - 查看完整指令列表
- `#机器人信息` - 查看机器人运行状态

## 构建

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Gradle 8.2+
- KSP (Kotlin Symbol Processing)

### 编译步骤

```bash
# 克隆项目
git clone https://github.com/HdShare/Aurora.git
cd Aurora

# 编译 Release APK
./gradlew assembleRelease
```

编译产物位于 `app/build/outputs/apk/release/`。

### GitHub Actions

项目配置了 GitHub Actions 自动编译流程，在 GitHub 仓库的 **Actions** 标签页中可以手动触发编译：

1. 进入仓库的 **Actions** 页面
2. 选择 **Build APK** 工作流
3. 点击 **Run workflow** 按钮
4. 编译完成后下载生成的 APK

## 依赖

| 依赖 | 用途 |
|------|------|
| [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) | Xposed Hook API 封装 |
| [DexKit](https://github.com/LuckyPray/DexKit) | 动态查找微信混淆类和方法 |
| [OkHttp](https://square.github.io/okhttp/) | AI API 网络请求 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 内嵌 HTTP 服务器 |
| [Gson](https://github.com/google/gson) | JSON 序列化 |
| Kotlin Coroutines | 异步处理 |

## 许可证

本项目仅供学习和研究使用，请勿用于非法用途。

## 免责声明

使用本模块产生的一切后果由使用者自行承担。请遵守相关法律法规和微信使用条款。