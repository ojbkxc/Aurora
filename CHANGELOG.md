# Aurora 更新日志

本文档记录 Aurora 项目的版本变更历史。

## [v1.0.1] - 2026-07-15

### 新增
- **ModuleLifecycle 接口**: 新增 `ModuleLifecycle` 生命周期接口，定义 `onInitialized()`、`onShutdown()`、`onError(error)` 回调方法，在 `WeChatHooker` 中实现，统一管理模块生命周期。
- **ConfigValidator 配置验证器**: 新增 `ConfigValidator` 工具类，提供 API Key 格式、URL 格式、模型名称、触发词、缓存轮数范围、日志级别等验证功能，支持批量验证。
- **WeChat 版本兼容性检查**: 在 `WeChatHooker` 中添加 `checkWeChatVersion()` 方法，使用 PackageManager 检测微信版本号，对未经过测试的版本记录警告日志。

### 改进
- **错误边界处理**: 在 `WeChatHooker.onApplicationAttached()` 中为每个初始化步骤（DexKit、HTTP 服务器、消息拦截、缓存恢复、定时器、队列处理器）添加独立的 try-catch 块，防止单个步骤失败引发级联故障。
- **资源清理增强**: 改进 `MainActivity.onDestroy()` 中的资源清理逻辑，增加子视图移除和更详细的注释说明。
- **KDoc 文档化**: 为 `AiService.kt`、`CommandParser.kt`、`ConfigManager.kt`、`HttpServer.kt` 中的所有公开方法添加完整的 KDoc 注释，包括 `@param`、`@return` 和 `@throws` 标签。
- **默认配置值文档化**: 在 `ConfigManager` 的所有配置键常量上添加 KDoc，明确标注默认值和取值范围。

---

## [v1.0.0] - 2026-06

### 初始版本功能

#### 核心功能
- **微信消息自动回复**: 基于 Xposed/LSPosed 框架 Hook 微信消息处理流程，实现自动 AI 回复。
- **多 AI 厂商支持**: 支持 DeepSeek、通义千问 (DashScope)、硅基流动 (SiliconFlow)、智谱 (BigModel) 以及自定义 OpenAI 兼容 API。
- **指令系统**: 完整的 `#` 指令系统，支持 AI 对话、绑定管理、配置设置、订阅管理、调教规则、群欢迎语、开发者模式等。
- **多轮对话**: 支持上下文感知的多轮对话缓存，可配置缓存轮数（1-50 轮），带 LRU 淘汰策略。
- **HTTP API 服务器**: 内置 NanoHTTPD 服务器，提供 RESTful API 接口用于外部调用（发送消息、查询状态、健康检查等）。
- **速率限制**: AI 调用和 HTTP 请求均内置速率限制，防止滥用。
- **消息去重**: 基于 LinkedHashMap 的 LRU 消息去重机制，防止重复处理同一条消息。

#### 架构
- **模块化设计**: 分离的 AI 服务、配置管理、指令解析、对话管理、HTTP 服务、通知服务等模块。
- **DexKit 集成**: 使用 DexKit 动态查找微信内部类，支持多种微信版本，带缓存预热机制。
- **配置管理**: 基于 SharedPreferences 的配置管理，带内存缓存和 TTL 过期机制，支持旧版数据迁移。
- **性能指标收集**: 内置 MetricsCollector 追踪 AI 调用、消息处理、指令执行和 HTTP 请求延迟。
- **健康检查**: 提供 `healthCheck()` 方法检查 DexKit、HTTP 服务器、ConfigManager 和 AI Key 的运行状态。

#### 技术细节
- 使用 Kotlin 协程进行异步 AI 调用和队列处理
- 使用 OkHttp 进行 HTTP 请求，带连接池复用
- 支持 AI 调用重试（最多 2 次）和指数退避
- 对话缓存定时持久化（每 20 分钟）
- 订阅推送定时器（每日 09:30）
- 前台服务通知保持 HTTP 服务器运行
- 完整的日志系统，支持多级别日志输出