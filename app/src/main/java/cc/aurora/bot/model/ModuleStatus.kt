package cc.aurora.bot.model

/**
 * 模块运行状态枚举。
 *
 * 表示 Aurora 各模块的当前运行状态，用于健康检查和状态上报。
 * 状态流转: INITIALIZING -> RUNNING -> DEGRADED/ERROR -> SHUTDOWN
 */
enum class ModuleState {
    /** 模块正在初始化中，尚未就绪 */
    INITIALIZING,

    /** 模块正常运行 */
    RUNNING,

    /** 模块降级运行（部分功能不可用，但核心功能正常） */
    DEGRADED,

    /** 模块发生错误，功能不可用 */
    ERROR,

    /** 模块已关闭 */
    SHUTDOWN
}

/**
 * AI 提供商配置状态枚举。
 *
 * 表示某个 AI 提供商的 API Key 和配置状态。
 */
enum class AiProviderStatus {
    /** API Key 已正确配置，可以正常使用 */
    CONFIGURED,

    /** API Key 未配置 */
    NOT_CONFIGURED,

    /** 已配置但连接测试失败或发生错误 */
    ERROR
}