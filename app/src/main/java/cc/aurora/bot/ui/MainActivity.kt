package cc.aurora.bot.ui

import android.app.ProgressDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cc.aurora.bot.service.ai.AiProvider
import cc.aurora.bot.service.ai.AiService
import cc.aurora.bot.service.ai.ConnectionResult
import cc.aurora.bot.service.config.ConfigManager
import cc.aurora.bot.util.SecurityUtils
import kotlinx.coroutines.*

/**
 * Aurora 主配置界面
 * 管理所有 AI 厂商的 API Key、模型、触发词等配置
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var rootLayout: LinearLayout

    // 触发词相关
    private lateinit var etTriggerWord: EditText
    private lateinit var etCacheTimes: EditText
    private lateinit var etPrompt: EditText

    // DeepSeek 相关
    private lateinit var etDeepSeekKey: EditText
    private lateinit var etDeepSeekModel: EditText

    // 通义千问相关
    private lateinit var etQwenKey: EditText
    private lateinit var etQwenModel: EditText

    // 硅基流动相关
    private lateinit var etSiliconKey: EditText
    private lateinit var etSiliconModel: EditText

    // 智谱相关
    private lateinit var etZhiPuKey: EditText
    private lateinit var etZhiPuModel: EditText

    // 自定义 API 相关
    private lateinit var etCustomApi: EditText
    private lateinit var etCustomKey: EditText
    private lateinit var etCustomModel: EditText

    // 滚动位置保存
    private var savedScrollX = 0
    private var savedScrollY = 0

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 保存 ScrollListener 引用以便在 onDestroy 中移除，防止内存泄漏
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        savedScrollX = scrollView.scrollX
        savedScrollY = scrollView.scrollY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUI()
        loadConfig()
    }

    private fun createUI() {
        scrollView = ScrollView(this).apply {
            viewTreeObserver.addOnScrollChangedListener(scrollListener)
        }
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 48)
        }

        // 标题
        rootLayout.addView(TextView(this).apply {
            text = "Aurora - 微信AI自动回复"
            textSize = 22f
            setPadding(0, 0, 0, 8)
        })
        rootLayout.addView(TextView(this).apply {
            text = "配置管理"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 24)
        })

        // ===== 通用配置 =====
        addSection("通用配置")

        etTriggerWord = addInputField("触发词", "输入触发词，如：机器人")
        etCacheTimes = addInputField("对话缓存轮数", "缓存轮数，默认10", InputType.TYPE_CLASS_NUMBER)
        etPrompt = addInputField("默认调教 (System Prompt)", "输入系统提示词", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

        // ===== DeepSeek 配置 =====
        addSection("DeepSeek 配置")
        etDeepSeekKey = addInputField("API Key", "输入DeepSeek API Key")
        etDeepSeekModel = addInputField("模型", "默认 deepseek-chat", placeHolder = "deepseek-chat")

        // ===== 通义千问配置 =====
        addSection("通义千问 (DashScope) 配置")
        etQwenKey = addInputField("API Key", "输入通义千问 API Key")
        etQwenModel = addInputField("模型", "默认 qwen-turbo", placeHolder = "qwen-turbo")

        // ===== 硅基流动配置 =====
        addSection("硅基流动 (SiliconFlow) 配置")
        etSiliconKey = addInputField("API Key", "输入硅基流动 API Key")
        etSiliconModel = addInputField("模型", "默认 Qwen/Qwen2.5-7B-Instruct", placeHolder = "Qwen/Qwen2.5-7B-Instruct")

        // ===== 智谱配置 =====
        addSection("智谱 (BigModel) 配置")
        etZhiPuKey = addInputField("API Key", "输入智谱 API Key")
        etZhiPuModel = addInputField("模型", "默认 glm-4-flash", placeHolder = "glm-4-flash")

        // ===== 自定义 API 配置 =====
        addSection("自定义 OpenAI 兼容 API")
        etCustomApi = addInputField("API 地址", "输入自定义API地址，如 https://api.openai.com/v1")
        etCustomKey = addInputField("API Key", "输入自定义API Key")
        etCustomModel = addInputField("模型", "默认 gpt-3.5-turbo", placeHolder = "gpt-3.5-turbo")

        // ===== 保存按钮 =====
        rootLayout.addView(Button(this).apply {
            text = "保存配置"
            setOnClickListener {
                saveConfig()
                restoreScrollPosition()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
        })

        // ===== 测试按钮 =====
        rootLayout.addView(Button(this).apply {
            text = "测试 API 连接"
            setOnClickListener { testApiConnection() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        })

        // ===== Stats 区域 =====
        addSection("运行统计")
        val statsTextView = TextView(this).apply {
            text = "加载中..."
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 8, 0, 12)
            tag = "stats_view"
        }
        rootLayout.addView(statsTextView)

        // ===== Diagnostics 按钮 =====
        rootLayout.addView(Button(this).apply {
            text = "运行诊断"
            setOnClickListener { runDiagnostics() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        })

        scrollView.addView(rootLayout)
        setContentView(scrollView)
    }

    private fun addSection(title: String) {
        rootLayout.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF00D4AA.toInt())
            setPadding(0, 24, 0, 8)
        })
    }

    private fun addInputField(
        label: String,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        placeHolder: String? = null
    ): EditText {
        rootLayout.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 4)
        })

        val editText = EditText(this).apply {
            this.hint = placeHolder ?: hint
            this.inputType = inputType
            setPadding(24, 12, 24, 12)
            background = getDrawable(android.R.drawable.edit_text)
            setTextColor(0xFF333333.toInt())
            textSize = 14f
            minLines = if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) 3 else 1
        }

        rootLayout.addView(editText)
        return editText
    }

    private fun loadConfig() {
        val ctx = this

        etTriggerWord.setText(ConfigManager.getString(ctx, ConfigManager.KEY_TRIGGER_WORD, "机器人"))
        etCacheTimes.setText(ConfigManager.getInt(ctx, ConfigManager.KEY_CACHE_TIMES, 10).toString())
        etPrompt.setText(ConfigManager.getString(ctx, ConfigManager.KEY_PROMPT, "你是一个友好的微信AI助手，请用简洁自然的中文回复。"))

        etDeepSeekKey.setText(ConfigManager.getString(ctx, ConfigManager.KEY_DEEPSEEK_KEY))
        etDeepSeekModel.setText(ConfigManager.getString(ctx, ConfigManager.KEY_DEEPSEEK_MODEL))
        etQwenKey.setText(ConfigManager.getString(ctx, ConfigManager.KEY_QWEN_KEY))
        etQwenModel.setText(ConfigManager.getString(ctx, ConfigManager.KEY_QWEN_MODEL))
        etSiliconKey.setText(ConfigManager.getString(ctx, ConfigManager.KEY_SILICON_KEY))
        etSiliconModel.setText(ConfigManager.getString(ctx, ConfigManager.KEY_SILICON_MODEL))
        etZhiPuKey.setText(ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_KEY))
        etZhiPuModel.setText(ConfigManager.getString(ctx, ConfigManager.KEY_ZHIPU_MODEL))

        etCustomApi.setText(ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_API))
        etCustomKey.setText(ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_KEY))
        etCustomModel.setText(ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_MODEL))

        restoreScrollPosition()
        refreshStats()
    }

    private fun saveConfig() {
        val ctx = this

        // 消毒所有用户输入，防止注入攻击
        ConfigManager.saveString(ctx, ConfigManager.KEY_TRIGGER_WORD, SecurityUtils.sanitizeInput(etTriggerWord.text.toString().trim(), 256))
        ConfigManager.saveInt(ctx, ConfigManager.KEY_CACHE_TIMES, etCacheTimes.text.toString().trim().toIntOrNull() ?: 10)
        ConfigManager.saveString(ctx, ConfigManager.KEY_PROMPT, SecurityUtils.sanitizeInput(etPrompt.text.toString().trim(), 5000))

        ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_KEY, SecurityUtils.sanitizeInput(etDeepSeekKey.text.toString().trim(), 512))
        ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_MODEL, SecurityUtils.sanitizeInput(etDeepSeekModel.text.toString().trim(), 256))
        ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_KEY, SecurityUtils.sanitizeInput(etQwenKey.text.toString().trim(), 512))
        ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_MODEL, SecurityUtils.sanitizeInput(etQwenModel.text.toString().trim(), 256))
        ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_KEY, SecurityUtils.sanitizeInput(etSiliconKey.text.toString().trim(), 512))
        ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_MODEL, SecurityUtils.sanitizeInput(etSiliconModel.text.toString().trim(), 256))
        ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_KEY, SecurityUtils.sanitizeInput(etZhiPuKey.text.toString().trim(), 512))
        ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_MODEL, SecurityUtils.sanitizeInput(etZhiPuModel.text.toString().trim(), 256))

        ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_API, SecurityUtils.sanitizeInput(etCustomApi.text.toString().trim(), 2048))
        ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_KEY, SecurityUtils.sanitizeInput(etCustomKey.text.toString().trim(), 512))
        ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_MODEL, SecurityUtils.sanitizeInput(etCustomModel.text.toString().trim(), 256))

        Toast.makeText(this, "配置已保存 (需重启微信生效)", Toast.LENGTH_LONG).show()
    }

    private fun restoreScrollPosition() {
        scrollView.post {
            scrollView.scrollTo(savedScrollX, savedScrollY)
        }
    }

    /**
     * 实际测试 API 连接：使用 AiService.checkConnection() 测试各已配置的 AI 厂商，
     * 验证 API Key 和连通性，不发送完整消息。
     */
    private fun testApiConnection() {
        // 先保存当前配置
        saveConfig()

        val ctx = this

        // 收集所有已配置的提供商
        val providers = listOf(
            TestProvider(AiProvider.DEEPSEEK, ConfigManager.KEY_DEEPSEEK_KEY, ConfigManager.KEY_DEEPSEEK_MODEL),
            TestProvider(AiProvider.QWEN, ConfigManager.KEY_QWEN_KEY, ConfigManager.KEY_QWEN_MODEL),
            TestProvider(AiProvider.SILICON, ConfigManager.KEY_SILICON_KEY, ConfigManager.KEY_SILICON_MODEL),
            TestProvider(AiProvider.ZHIPU, ConfigManager.KEY_ZHIPU_KEY, ConfigManager.KEY_ZHIPU_MODEL),
            TestProvider(AiProvider.CUSTOM, ConfigManager.KEY_CUSTOM_KEY, ConfigManager.KEY_CUSTOM_MODEL)
        ).filter { tp ->
            ConfigManager.getString(ctx, tp.keyField).isNotBlank()
        }

        if (providers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("测试 API 连接")
                .setMessage("没有配置任何 API Key，请先填写至少一个厂商的 API Key。")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        // 显示加载指示器
        val progressDialog = ProgressDialog(this).apply {
            setTitle("测试 API 连接")
            setMessage("正在测试 API 连接...")
            setCancelable(false)
            show()
        }

        scope.launch {
            val sb = StringBuilder("API 连接测试结果:\n\n")
            var testedCount = 0

            for (tp in providers) {
                try {
                    val apiKey = ConfigManager.getString(ctx, tp.keyField)
                    val model = ConfigManager.getString(ctx, tp.modelField).ifBlank { tp.provider.defaultModel }
                    val customUrl = if (tp.provider == AiProvider.CUSTOM) {
                        ConfigManager.getString(ctx, ConfigManager.KEY_CUSTOM_API)
                    } else null

                    val result = withContext(Dispatchers.IO) {
                        AiService.checkConnection(
                            provider = tp.provider,
                            apiKey = apiKey,
                            model = model,
                            customBaseUrl = customUrl
                        )
                    }

                    if (result.success) {
                        sb.append("[OK] ${tp.provider.displayName}: ${result.message}")
                        sb.append(" (延迟: ${result.latencyMs}ms)")
                        sb.append("\n\n")
                    } else {
                        sb.append("[FAIL] ${tp.provider.displayName}: ${result.message}\n\n")
                    }
                } catch (e: Exception) {
                    sb.append("[FAIL] ${tp.provider.displayName}: ${e.message}\n\n")
                }
                testedCount++
            }

            sb.append("共测试 $testedCount 个提供商\n")
            sb.append("\n提示: 请在微信中发送 #API说明 查看完整指令列表")

            progressDialog.dismiss()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("API 连接测试")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /**
     * 运行诊断：收集配置信息和基本状态，显示诊断结果。
     */
    private fun runDiagnostics() {
        val ctx = this
        val progressDialog = ProgressDialog(this).apply {
            setTitle("运行诊断")
            setMessage("正在收集诊断信息...")
            setCancelable(false)
            show()
        }

        scope.launch {
            val sb = StringBuilder("=== Aurora 诊断报告 ===\n\n")

            try {
                val summary = withContext(Dispatchers.IO) {
                    ConfigManager.getConfigSummary(ctx)
                }

                // 系统信息
                sb.appendLine("【系统信息】")
                sb.appendLine("  触发词: ${summary["triggerWord"] ?: "未设置"}")
                sb.appendLine("  缓存轮数: ${summary["cacheTimes"] ?: "10"}")
                sb.appendLine("  发送计数: ${summary["sendCount"] ?: "0"}")
                sb.appendLine("  绑定数量: ${summary["bindingCount"] ?: "0"}")
                sb.appendLine("  开发者数量: ${summary["devIdCount"] ?: "0"}")
                sb.appendLine()

                // AI 厂商配置状态
                sb.appendLine("【AI 厂商配置】")
                val providers = listOf(
                    "DeepSeek" to "deepseekKeyConfigured",
                    "通义千问" to "qwenKeyConfigured",
                    "硅基流动" to "siliconKeyConfigured",
                    "智谱" to "zhipuKeyConfigured",
                    "自定义" to "customKeyConfigured"
                )
                for ((name, key) in providers) {
                    val configured = summary[key] == "true"
                    sb.appendLine("  $name: ${if (configured) "已配置" else "未配置"}")
                }
                sb.appendLine()

                // 模型配置
                sb.appendLine("【模型配置】")
                sb.appendLine("  DeepSeek: ${summary["deepseekModel"]?.ifBlank { "默认" } ?: "默认"}")
                sb.appendLine("  通义千问: ${summary["qwenModel"]?.ifBlank { "默认" } ?: "默认"}")
                sb.appendLine("  硅基流动: ${summary["siliconModel"]?.ifBlank { "默认" } ?: "默认"}")
                sb.appendLine("  智谱: ${summary["zhipuModel"]?.ifBlank { "默认" } ?: "默认"}")
                sb.appendLine("  自定义: ${summary["customModel"]?.ifBlank { "默认" } ?: "默认"}")
                sb.appendLine()

                // 自定义 API
                sb.appendLine("【自定义 API】")
                sb.appendLine("  URL: ${summary["customApiUrl"] ?: "未配置"}")
                sb.appendLine()

                // 任何配置问题
                sb.appendLine("【诊断建议】")
                val hasAnyKey = providers.any { summary[it.second] == "true" }
                if (!hasAnyKey) {
                    sb.appendLine("  警告: 未配置任何 AI 厂商的 API Key")
                    sb.appendLine("  建议: 请至少配置一个 AI 厂商的 Key")
                }
                if ((summary["bindingCount"]?.toIntOrNull() ?: 0) == 0) {
                    sb.appendLine("  提示: 尚未绑定任何聊天室")
                    sb.appendLine("  建议: 在微信中发送 #绑定聊天室 进行绑定")
                }
                sb.appendLine("  提示: 请在微信中发送 #机器人信息 查看运行状态")

            } catch (e: Exception) {
                sb.appendLine("  诊断失败: ${e.message}")
            }

            progressDialog.dismiss()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("诊断结果")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /**
     * 刷新 Stats 区域的显示内容
     */
    private fun refreshStats() {
        val ctx = this
        scope.launch(Dispatchers.IO) {
            try {
                val summary = ConfigManager.getConfigSummary(ctx)
                val statsText = """
                    发送总数: ${summary["sendCount"] ?: "0"}
                    绑定数量: ${summary["bindingCount"] ?: "0"}
                    开发者数量: ${summary["devIdCount"] ?: "0"}
                    缓存轮数: ${summary["cacheTimes"] ?: "10"}
                    触发词: ${summary["triggerWord"]?.ifBlank { "未设置" } ?: "未设置"}
                """.trimIndent()

                withContext(Dispatchers.Main) {
                    // 查找 stats_view 并更新
                    val statsView = rootLayout.findViewWithTag<TextView>("stats_view")
                    statsView?.text = statsText
                }
            } catch (e: Exception) {
                android.util.Log.w("Aurora", "refreshStats failed: ${e.message}")
            }
        }
    }

    /**
     * 销毁 Activity，安全释放所有资源。
     *
     * 清理顺序：
     * 1. 移除 ScrollListener，防止内存泄漏
     * 2. 保存当前滚动位置
     * 3. 取消所有正在运行的协程
     * 4. 清除所有视图引用，帮助 GC 回收
     * 5. 调用父类 onDestroy
     */
    override fun onDestroy() {
        // 1. 移除 ScrollListener，防止内存泄漏
        if (::scrollView.isInitialized) {
            scrollView.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            savedScrollX = scrollView.scrollX
            savedScrollY = scrollView.scrollY
        }

        // 2. 取消所有协程，防止协程泄漏
        scope.cancel()

        // 3. 清除子视图引用，帮助 GC 回收
        if (::rootLayout.isInitialized) {
            rootLayout.removeAllViews()
        }

        super.onDestroy()
    }

    /**
     * 测试用数据类
     */
    private data class TestProvider(
        val provider: AiProvider,
        val keyField: String,
        val modelField: String
    )
}