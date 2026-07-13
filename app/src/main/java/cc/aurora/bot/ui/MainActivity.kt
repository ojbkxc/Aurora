package cc.aurora.bot.ui

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cc.aurora.bot.R
import cc.aurora.bot.service.config.ConfigManager

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUI()
        loadConfig()
    }

    private fun createUI() {
        scrollView = ScrollView(this)
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
            setOnClickListener { saveConfig() }
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
    }

    private fun saveConfig() {
        val ctx = this

        ConfigManager.saveString(ctx, ConfigManager.KEY_TRIGGER_WORD, etTriggerWord.text.toString().trim())
        ConfigManager.saveInt(ctx, ConfigManager.KEY_CACHE_TIMES, etCacheTimes.text.toString().trim().toIntOrNull() ?: 10)
        ConfigManager.saveString(ctx, ConfigManager.KEY_PROMPT, etPrompt.text.toString().trim())

        ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_KEY, etDeepSeekKey.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_DEEPSEEK_MODEL, etDeepSeekModel.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_KEY, etQwenKey.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_QWEN_MODEL, etQwenModel.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_KEY, etSiliconKey.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_SILICON_MODEL, etSiliconModel.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_KEY, etZhiPuKey.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_ZHIPU_MODEL, etZhiPuModel.text.toString().trim())

        ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_API, etCustomApi.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_KEY, etCustomKey.text.toString().trim())
        ConfigManager.saveString(ctx, ConfigManager.KEY_CUSTOM_MODEL, etCustomModel.text.toString().trim())

        Toast.makeText(this, "配置已保存 (需重启微信生效)", Toast.LENGTH_LONG).show()
    }

    private fun testApiConnection() {
        // 保存当前配置
        saveConfig()

        val ctx = this
        val sb = StringBuilder("API 配置状态:\n\n")

        // 检查各厂商配置
        listOf(
            Triple("DeepSeek", ConfigManager.KEY_DEEPSEEK_KEY, ConfigManager.KEY_DEEPSEEK_MODEL),
            Triple("通义千问", ConfigManager.KEY_QWEN_KEY, ConfigManager.KEY_QWEN_MODEL),
            Triple("硅基流动", ConfigManager.KEY_SILICON_KEY, ConfigManager.KEY_SILICON_MODEL),
            Triple("智谱", ConfigManager.KEY_ZHIPU_KEY, ConfigManager.KEY_ZHIPU_MODEL),
            Triple("自定义", ConfigManager.KEY_CUSTOM_KEY, ConfigManager.KEY_CUSTOM_MODEL)
        ).forEach { (name, keyField, modelField) ->
            val key = ConfigManager.getString(ctx, keyField)
            val model = ConfigManager.getString(ctx, modelField)
            val status = if (key.isNotBlank()) "已配置" else "未配置"
            sb.append("$name: $status")
            if (model.isNotBlank()) sb.append(" (模型: $model)")
            sb.append("\n")
        }

        sb.append("\n触发词: ${ConfigManager.getString(ctx, ConfigManager.KEY_TRIGGER_WORD, "机器人")}\n")
        sb.append("缓存轮数: ${ConfigManager.getInt(ctx, ConfigManager.KEY_CACHE_TIMES, 10)}\n")
        sb.append("\n提示: 请在微信中发送 #API说明 查看完整指令列表")

        AlertDialog.Builder(this)
            .setTitle("配置检测")
            .setMessage(sb.toString())
            .setPositiveButton("确定", null)
            .show()
    }
}