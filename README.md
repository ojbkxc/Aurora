# Aurora

> 微信 AI 自动回复机器人 -- 支持 DeepSeek / 通义千问 / 智谱 / 硅基流动 / 自定义 OpenAI 兼容 API

[![Build Status](https://img.shields.io/github/actions/workflow/status/HdShare/Aurora/build.yml?branch=master&label=Build)](https://github.com/HdShare/Aurora/actions)
[![Version](https://img.shields.io/badge/version-1.0.1-blue)](https://github.com/HdShare/Aurora/releases)
[![Xposed API](https://img.shields.io/badge/Xposed_API-93-green)](https://api.xposed.info/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://developer.android.com/)
[![License](https://img.shields.io/badge/license-Research%20%26%20Study-lightgrey)](./README.md)

---

## 目录

- [简介](#简介)
- [功能特性](#功能特性)
- [兼容性](#兼容性)
- [架构](#架构)
- [指令参考](#指令参考)
- [AI 提供商](#ai-提供商)
- [HTTP API](#http-api)
- [使用方法](#使用方法)
- [构建](#构建)
- [依赖](#依赖)
- [故障排除](#故障排除)
- [贡献指南](#贡献指南)
- [许可证](#许可证)
- [免责声明](#免责声明)

---

## 简介

Aurora 是一个基于 Xposed/LSPosed (YukiHookAPI) 框架的微信 AI 自动回复机器人模块。通过 Hook 微信进程实现消息拦截，调用 AI 大模型 API 进行智能回复，支持多轮对话、指令系统、订阅推送、HTTP API 等功能。

---

## 功能特性

### AI 自动回复

- 支持 **5 个 AI 提供商**：DeepSeek、通义千问 (DashScope)、智谱 (BigModel)、硅基流动 (SiliconFlow)、自定义 OpenAI 兼容 API
- 多轮对话上下文缓存，支持可配置的缓存轮数（默认 10 轮）
- 可自定义触发词和系统提示词 (System Prompt)
- 按厂商指定 AI 回复（如 `#AI:DS:` 指定 DeepSeek 回复）
- AI 请求队列管理，支持速率限制和指数退避重试

### 指令系统

- 支持 **36 种指令**，涵盖绑定管理、AI 配置、订阅管理、调教、群欢迎语、开发者模式等
- 所有指令以 `#` 前缀开头，支持精确前缀匹配
- 在微信聊天中直接发送指令即可操作

### HTTP API 服务器

- 内嵌 NanoHTTPD 服务器，默认端口 5888（支持端口回退到 5889、5890）
- 提供 6 个 RESTful 端点，支持外部程序集成

### 其他功能

- 每日定时订阅推送（默认 9:30）
- 群欢迎语自动回复
- 消息数据统计上报
- 对话缓存持久化（定时 20 分钟刷新到 SharedPreferences）
- 旧版配置自动迁移（从 GodHook 迁移到 Aurora）

---

## 兼容性

| 项目 | 要求 |
|------|------|
| 框架 | Xposed / LSPosed / LSPatch |
| 最低 Android 版本 | 8.0 (API 26) |
| 目标微信版本 | 配合 DexKit 动态适配微信混淆，理论上兼容多种微信版本 |

---

## 架构

### 多层 Hook 策略

Aurora 采用 **YukiHookAPI + DexKit** 作为核心 Hook 框架，通过多层策略定位微信消息处理类和方法：

```
策略 A: DexKit 精确查找 (WAuxiliary 验证模式)
  +-- 使用 doRevokeMsg xmlSrvMsgId=%d talker=%s 模式精确定位
  |   消息处理类，与消息接收密切相关
  |
策略 B: DexKit 字段特征匹配
  +-- 搜索同时包含 "msgId" 和 "talker" 字符串的类
  |   交叉匹配得到微信消息处理类（com.tencent.mm 包下）
  |
策略 C: 已知真实类名 Hook
  +-- 直接 Hook 已知的微信消息处理类
  |   （如 chatting.presenter.n, storage.MsgInfo 等）
  |
策略 D: 已知路径模式匹配
  +-- 按 com.tencent.mm.plugin.messenger.foundation 等路径
  |   查找包含 onNewMessage/onReceive 等方法的类
  |
策略 E: 字符串特征枚举
  +-- 在所有 com.tencent.mm 包下枚举包含 "onNewMessage" 方法的类
```

### 消息处理流程

```
Xposed 框架加载
    |
    v
HookEntry (IYukiHookXposedInit)
    +-- 初始化 DexKitBridge (从微信 APK)
    +-- 加载 WeChatHooker
            |
            +-- onHook() -- Hook Application.attachBaseContext
            |       |
            |       +-- onApplicationAttached()
            |               +-- 启动 HTTP Server (NanoHTTPD :5888)
            |               +-- 初始化消息拦截 (DexKit + 已知路径 + 字符串特征)
            |               +-- 恢复对话缓存 (SP -- 内存)
            |               +-- 启动定时器 (订阅推送/缓存刷新/数据上报)
            |
            +-- 消息处理流程:
            |   收到消息 -- 反射提取 wxId + content
            |       +-- 匹配指令 (#开头) -- CommandParser -- 执行对应操作
            |       +-- 匹配触发词 -- AiService.chat() -- OkHttp -- AI API -- 回复
            |
            +-- HTTP API 回调 (sendMessage/status/bindings/subscriptions)
```

### 消息发送机制

Aurora 通过反射调用微信内部方法实现消息发送，支持：
- 单聊消息发送
- 群聊消息发送
- 通过 HTTP API 批量发送（支持 wxId 数组格式）

### 多进程安全

Aurora 仅在微信主进程中工作，通过检查 `processName` 跳过子进程，避免重复注入。

---

## 指令参考

Aurora 提供 **36 种指令**，以 `#` 前缀开头，在微信聊天中直接发送即可使用：

### AI 指定厂商回复

| 指令 | 说明 |
|------|------|
| `#AI:DS:消息内容` | 指定 DeepSeek 回复 |
| `#AI:QW:消息内容` | 指定通义千问回复 |
| `#AI:SI:消息内容` | 指定硅基流动回复 |
| `#AI:ZP:消息内容` | 指定智谱回复 |
| `#AI:消息内容` | 指定默认 AI 回复 |
| `#AII:消息内容` | AI 图片生成 |
| `@测试` | AI 回复测试 |

### 绑定管理

| 指令 | 说明 |
|------|------|
| `#绑定聊天室` | 将当前聊天室绑定为 AI 回复目标 |
| `#解绑聊天室` | 解绑当前聊天室 |
| `#解绑全部聊天室` | 解绑所有聊天室 |
| `#当前聊天室` | 查看当前绑定状态 |
| `#聊天室ID` | 获取当前聊天室 ID |

### AI 配置

| 指令 | 说明 |
|------|------|
| `#deepseekKey:sk-xxx` | 设置 DeepSeek API Key |
| `#deepseekModel:deepseek-chat` | 设置 DeepSeek 模型 |
| `#qwenKey:sk-xxx` | 设置通义千问 API Key |
| `#qwenModel:qwen-turbo` | 设置通义千问模型 |
| `#siliconKey:sk-xxx` | 设置硅基流动 API Key |
| `#siliconModel:Qwen/Qwen2.5-7B-Instruct` | 设置硅基流动模型 |
| `#zhipuKey:xxx` | 设置智谱 API Key |
| `#zhipuModel:glm-4-flash` | 设置智谱模型 |
| `#API:https://api.openai.com/v1` | 设置自定义 API 地址 |
| `#KEY:sk-xxx` | 设置自定义 API Key |
| `#模型:gpt-4` | 设置当前使用的模型 |
| `#触发:机器人` | 设置触发词 |
| `#缓存:20` | 设置对话缓存轮数 |

### 订阅管理

| 指令 | 说明 |
|------|------|
| `#订阅:每日新闻` | 订阅每日推送 |
| `#取消订阅:每日新闻` | 取消订阅 |
| `#取消所有订阅` | 取消所有订阅 |
| `#当前订阅` | 查看当前订阅列表 |

### 其他

| 指令 | 说明 |
|------|------|
| `#调教` | 设置当前聊天室调教词 |
| `#默认调教` | 设置默认调教词 (System Prompt) |
| `#进群欢迎语:欢迎入群` | 设置群欢迎语 |
| `#查询本群欢迎语` | 查看当前群欢迎语 |
| `#取消本群欢迎语` | 取消群欢迎语 |
| `#开发模式` | 开启开发者模式 |
| `#关闭开发模式` | 关闭开发者模式 |
| `#无需艾特` | 设置无需 @ 即可触发 |
| `#需艾特` | 设置需要 @ 才能触发 |
| `#机器人信息` | 查看机器人运行状态 |
| `#API说明` | 查看完整指令列表 |
| `#重启机器人` | 重启机器人服务 |
| `#测试` | 测试机器人连接 |
| `#需求` | 提交功能需求 |
| `#艾特` | @ 机器人 |

---

## AI 提供商

Aurora 支持 **5 个 AI 提供商**，通过 OkHttp 调用 OpenAI 兼容的 Chat Completions API：

### 1. DeepSeek

| 配置项 | 值 |
|--------|-----|
| 默认 API 地址 | `https://api.deepseek.com/chat/completions` |
| 默认模型 | `deepseek-chat` |
| API Key 获取 | [platform.deepseek.com](https://platform.deepseek.com/) |

```bash
# 微信指令配置
#deepseekKey:sk-your-deepseek-api-key
#deepseekModel:deepseek-chat
```

### 2. 通义千问 (DashScope)

| 配置项 | 值 |
|--------|-----|
| 默认 API 地址 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 默认模型 | `qwen-turbo` |
| API Key 获取 | [dashscope.aliyun.com](https://dashscope.aliyun.com/) |

```bash
# 微信指令配置
#qwenKey:sk-your-qwen-api-key
#qwenModel:qwen-turbo
```

### 3. 智谱 (BigModel)

| 配置项 | 值 |
|--------|-----|
| 默认 API 地址 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| 默认模型 | `glm-4-flash` |
| API Key 获取 | [open.bigmodel.cn](https://open.bigmodel.cn/) |

```bash
# 微信指令配置
#zhipuKey:your-zhipu-api-key
#zhipuModel:glm-4-flash
```

注意：智谱提供商自动启用 Web Search 工具调用。

### 4. 硅基流动 (SiliconFlow)

| 配置项 | 值 |
|--------|-----|
| 默认 API 地址 | `https://api.siliconflow.cn/v1/chat/completions` |
| 默认模型 | `Qwen/Qwen2.5-7B-Instruct` |
| API Key 获取 | [siliconflow.cn](https://siliconflow.cn/) |

```bash
# 微信指令配置
#siliconKey:sk-your-siliconflow-api-key
#siliconModel:Qwen/Qwen2.5-7B-Instruct
```

### 5. 自定义 OpenAI 兼容 API

支持任何兼容 OpenAI Chat Completions API 的服务：

| 配置项 | 值 |
|--------|-----|
| 默认 API 地址 | 需手动配置 |
| 默认模型 | `gpt-3.5-turbo` |

```bash
# 微信指令配置
#API:https://your-api-endpoint.com/v1
#KEY:sk-your-custom-api-key
#模型:gpt-4
```

### 配置保存

所有配置通过 SharedPreferences 持久化存储，支持：
- 通过 Aurora 应用界面配置（可测试 API 连接）
- 通过微信 `#` 指令实时配置
- 自动从旧版 GodHook 配置迁移

---

## HTTP API

Aurora 内嵌 HTTP 服务器（基于 NanoHTTPD），默认端口 **5888**，支持端口回退（5889、5890）。

### 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| `GET` | `/health` | 健康检查 |
| `GET` | `/status` | 机器人运行状态 |
| `GET` | `/bindings` | 绑定聊天室列表 |
| `GET` | `/subscriptions` | 订阅列表 |
| `POST` | `/send` | 主动发送消息 |
| `GET` | `/` | API 文档页面 |

### 端点详情

#### GET /health

健康检查接口，返回服务运行状态。

```json
{
  "code": 0,
  "msg": "healthy",
  "uptime": 1720000000000
}
```

#### GET /status

获取机器人运行状态，包括配置信息、绑定列表、消息统计等。

```json
{
  "code": 0,
  "msg": "ok",
  "status": "running"
}
```

#### GET /bindings

获取已绑定的聊天室列表。

```json
{
  "code": 0,
  "msg": "ok",
  "count": 3,
  "bindings": ["wxid_abc", "123456@chatroom", "wxid_def"]
}
```

#### GET /subscriptions

获取当前订阅列表。

```json
{
  "code": 0,
  "msg": "ok",
  "count": 2,
  "subscriptions": [
    { "wxId": "wxid_abc", "content": "每日新闻", "time": "09:30" },
    { "wxId": "123456@chatroom", "content": "天气预报", "time": "09:30" }
  ]
}
```

#### POST /send

主动向指定 wxId 发送消息。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `wxIds` | string | 是 | 目标 wxId，支持逗号分隔多个或 JSON 数组格式 |
| `msg` | string | 是 | 要发送的消息内容 |

**wxIds 格式支持：**
- 单个：`wxid_abc`
- 逗号分隔：`wxid_abc,wxid_def`
- JSON 数组：`['wxid_abc','wxid_def']`

**请求示例：**

```bash
curl -X POST "http://localhost:5888/send?wxIds=wxid_abc&msg=Hello%20World"
```

```json
{
  "code": 0,
  "msg": "success",
  "sentTo": 1
}
```

#### GET /

返回 HTML 格式的 API 文档页面，包含所有端点说明。

---

## 使用方法

### 安装

1. 确保已安装 Xposed/LSPosed 框架
2. 下载并安装 Aurora APK
3. 在 Xposed/LSPosed 管理器中启用 Aurora 模块
4. 勾选作用域：**微信 (com.tencent.mm)**
5. 重启微信生效

### 配置 AI

1. 打开 Aurora 应用，配置至少一个 AI 厂商的 API Key
2. 配置触发词（默认：`机器人`）
3. 点击"保存配置"，或点击"测试 API 连接"验证配置
4. 在微信中发送触发词 + 消息，如 `机器人 你好`

### 指令使用

在微信中发送 `#` 开头的指令消息即可操作，例如：
- `#绑定聊天室` -- 将当前聊天室绑定为 AI 回复目标
- `#API说明` -- 查看完整指令列表
- `#机器人信息` -- 查看机器人运行状态

### 验证

安装完成后，可在 Xposed/LSPosed 日志中搜索 `AURORA` 标签确认模块已正常加载。成功加载的日志示例：

```
AURORA: DexKitBridge set from HookEntry
AURORA: onHook() called
AURORA: initMessageInterceptor() done via DexKit
AURORA: HttpServer started on port 5888
```

---

## 构建

### 环境要求

| 工具 | 版本 |
|------|------|
| Android Studio | Hedgehog (2023.1.1) 或更高版本 |
| JDK | 17 |
| Gradle | 8.2+ |
| KSP | Kotlin Symbol Processing |

### 编译命令

```bash
# 克隆项目
git clone https://github.com/HdShare/Aurora.git
cd Aurora

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK
./gradlew assembleRelease

# 清理构建产物
./gradlew clean
```

编译产物位于 `app/build/outputs/apk/release/`。

### GitHub Actions

项目配置了 GitHub Actions 自动编译流程，在 GitHub 仓库的 **Actions** 标签页中可以手动触发编译：

1. 进入仓库的 **Actions** 页面
2. 选择 **Build APK** 工作流
3. 点击 **Run workflow** 按钮
4. 编译完成后下载生成的 APK

---

## 依赖

| 依赖 | 用途 |
|------|------|
| [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) | Xposed Hook API 封装 |
| [DexKit](https://github.com/LuckyPray/DexKit) | 动态查找微信混淆类和方法 |
| [OkHttp](https://square.github.io/okhttp/) | AI API 网络请求 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 内嵌 HTTP 服务器 |
| [Gson](https://github.com/google/gson) | JSON 序列化 |
| Kotlin Coroutines | 异步处理 |

---

## 故障排除

### 模块未生效

1. 确认 Xposed/LSPosed 框架已正确安装并激活
2. 检查 Aurora 模块在 LSPosed 中是否已启用
3. 确认作用域已勾选 **微信 (com.tencent.mm)**
4. 重启微信（完全退出后重新打开，不是切后台）

### AI 回复无响应

1. 确认至少配置了一个 AI 厂商的 API Key
2. 检查触发词是否正确（默认：`机器人`）
3. 确认 AI API 地址可访问（网络连通性）
4. 查看 Xposed 日志中是否有 `AURORA` 标签的错误信息

### API 连接失败

1. 检查 API Key 是否正确且未过期
2. 检查 API 地址是否可访问（可能需要网络代理）
3. 使用 Aurora 应用中的"测试 API 连接"功能验证
4. 查看错误信息：`#机器人信息` 可查看当前状态

### HTTP API 无法访问

1. 确认微信已正常启动（HTTP Server 随微信进程启动）
2. 检查端口 5888 是否被占用（会自动回退到 5889/5890）
3. 在 Xposed 日志中搜索 `HttpServer` 确认启动端口

### 消息 Hook 未生效

1. 检查微信版本是否兼容
2. 查看 Xposed 日志中 `initMessageInterceptor` 的执行结果
3. DexKit 失败时会自动降级使用已知路径 Hook

### 获取微信 ID

- 在微信中发送 `#聊天室ID` 获取当前聊天室 ID
- 通过 HTTP API `/bindings` 查看已绑定的聊天室列表

---

## 贡献指南

欢迎贡献代码、提交 Issue 或提出改进建议。

### 贡献方式

1. **Fork** 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- 使用 Kotlin 编写
- 遵循 Android Kotlin 代码风格指南
- 指令定义在 `CommandParser.kt` 的 `CommandType` 枚举中
- AI 提供商定义在 `AiService.kt` 的 `AiProvider` 枚举中
- 添加适当的注释和文档

### 提交 Issue

提交 Issue 时请提供以下信息：

- 使用的框架及版本（Xposed / LSPosed / LSPatch）
- 微信版本号
- Android 版本
- 使用的 AI 提供商
- 相关 Xposed 日志（搜索 `AURORA` 标签）

---

## 许可证

本项目仅用于**学习和研究**目的，探讨 Android Hook 技术与 AI 大模型在即时通讯场景中的应用。

**禁止用于任何违法违规用途。** 使用本模块产生的一切后果由使用者自行承担。

---

## 免责声明

使用本模块产生的一切后果由使用者自行承担。请遵守相关法律法规和微信使用条款。