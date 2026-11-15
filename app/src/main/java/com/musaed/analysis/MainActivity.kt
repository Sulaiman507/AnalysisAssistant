package com.musaed.analysis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.musaed.analysis.mcp.AnalysisLoop
import com.musaed.analysis.mcp.AnalysisStore
import com.musaed.analysis.mcp.ChatAgent
import com.musaed.analysis.mcp.ChatOutcome
import com.musaed.analysis.mcp.ChatSession
import com.musaed.analysis.mcp.ChatStore
import com.musaed.analysis.mcp.GateTestStore
import com.musaed.analysis.mcp.GateTester
import com.musaed.analysis.mcp.McpClient
import com.musaed.analysis.mcp.McpException
import com.musaed.analysis.mcp.ModelClient
import com.musaed.analysis.mcp.ModelException
import com.musaed.analysis.mcp.ProviderSettings
import com.musaed.analysis.mcp.SessionLog
import com.musaed.analysis.mcp.SessionLogStore
import com.musaed.analysis.mcp.StressTestStore
import com.musaed.analysis.mcp.StressTester
import com.musaed.analysis.mcp.SystemPrompt
import com.musaed.analysis.mcp.ToolRunner
import com.musaed.analysis.mcp.ToolsListStore
import java.io.File
import kotlin.concurrent.thread

/**
 * الشاشة الرئيسية — واجهة داكنة رسمية (أخضر غامق + ذهبي)، بطاقات مقسّمة
 * (الهدف / الإجراءات / إعدادات النموذج / النتائج)، وكل الصفحة في تمريرة
 * واحدة بلا تمرير متداخل. كل استدعاء أداة يمر عبر المغلّف (مهلة + إعادة
 * محاولة واحدة) ويُسجَّل في سجل الجلسة بنتيجته الخام حرفياً.
 *
 * ملاحظة: حزمة التطبيق com.musaed.analysis مؤقتة وقابلة للتغيير.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var targetInput: EditText
    private lateinit var fetchButton: Button
    private lateinit var gateButton: Button
    private lateinit var stressButton: Button
    private lateinit var logButton: Button
    private lateinit var shareButton: Button

    // إعدادات مزوّد النموذج (المرحلة 3): تُحفظ في SharedPreferences فقط.
    private lateinit var providerNameInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var apiPathInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var fetchModelsButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var analysisButton: Button
    private lateinit var settingsStatus: TextView
    private var providerSettings: ProviderSettings = ProviderSettings.deepSeek()

    // المحادثة مع النموذج: تُحفظ على الجهاز فقط، وآخر تقرير تحليل يُمرَّر
    // كسياق للنموذج حتى يشرح منه. سجل أدوات المحادثة منفصل عن سجل التحليل.
    private lateinit var chatMessagesLayout: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatSendButton: Button
    private lateinit var chatRunButton: Button
    private lateinit var chatClearButton: Button
    private var chatSession = ChatSession()
    private var chatLog = SessionLog()
    private var lastReportJson: String? = null

    private var lastDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // كل الصفحة في تمريرة واحدة — لا تمرير متداخل: الأقسام والنتائج
        // كلها في ScrollView واحد، فلا تنقطع الأزرار ولا الحقول عن الشاشة.
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFF5F5F5.toInt())
        }
        statusView = TextView(this).apply {
            text = getString(R.string.status_not_checked)
            textSize = 15f
            setTextColor(0xFFE0E0E0.toInt())
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(titleView)
        root.addView(statusView)

        // بطاقة الهدف
        val targetCard = sectionCard()
        targetCard.addView(sectionTitle(getString(R.string.target_title)))
        targetInput = styledField(getString(R.string.target_package_hint)).apply {
            setText(StressTester.DEFAULT_TARGET)
        }
        targetCard.addView(targetInput)
        addCard(root, targetCard)

        // بطاقة الإجراءات
        val actionsCard = sectionCard()
        actionsCard.addView(sectionTitle(getString(R.string.actions_title)))
        fetchButton = styledButton(getString(R.string.fetch_tools), false).apply {
            setOnClickListener { fetchTools() }
        }
        gateButton = styledButton(getString(R.string.gate_test), false).apply {
            setOnClickListener { runGateTest() }
        }
        stressButton = styledButton(getString(R.string.stress_test), false).apply {
            setOnClickListener { runStressTest() }
        }
        logButton = styledButton(getString(R.string.view_log), false).apply {
            setOnClickListener { viewSessionLog() }
        }
        actionsCard.addView(buttonRow(fetchButton, gateButton))
        actionsCard.addView(buttonRow(stressButton, logButton))
        analysisButton = styledButton(getString(R.string.analysis_run), true).apply {
            setOnClickListener { runAnalysis() }
        }
        actionsCard.addView(analysisButton, fullWidthParams())
        shareButton = styledButton(getString(R.string.share_list), false).apply {
            isEnabled = false
            setOnClickListener { shareList() }
        }
        actionsCard.addView(shareButton, fullWidthParams())
        addCard(root, actionsCard)

        // بطاقة إعدادات النموذج — المفتاح يبقى في الجهاز فقط
        // (SharedPreferences) ولا يُرسل إلا إلى المزوّد نفسه.
        val settingsCard = sectionCard()
        settingsCard.addView(sectionTitle(getString(R.string.settings_title)))
        val presetDeepSeek = styledButton(getString(R.string.settings_preset_deepseek), false).apply {
            setOnClickListener { applyProviderPreset(ProviderSettings.deepSeek(apiKeyInput.text.toString().trim())) }
        }
        val presetGonka = styledButton(getString(R.string.settings_preset_gonka), false).apply {
            setOnClickListener { applyProviderPreset(ProviderSettings.gonkaRouter(apiKeyInput.text.toString().trim())) }
        }
        settingsCard.addView(buttonRow(presetDeepSeek, presetGonka))
        settingsCard.addView(fieldLabel(getString(R.string.settings_name_label)))
        providerNameInput = styledField(getString(R.string.settings_name_hint))
        settingsCard.addView(providerNameInput)
        settingsCard.addView(fieldLabel(getString(R.string.settings_key_label)))
        apiKeyInput = styledField(getString(R.string.settings_key_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        settingsCard.addView(apiKeyInput)
        settingsCard.addView(fieldLabel(getString(R.string.settings_base_label)))
        baseUrlInput = styledField(getString(R.string.settings_base_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        settingsCard.addView(baseUrlInput)
        settingsCard.addView(fieldLabel(getString(R.string.settings_path_label)))
        apiPathInput = styledField(getString(R.string.settings_path_hint))
        settingsCard.addView(apiPathInput)
        settingsCard.addView(fieldLabel(getString(R.string.settings_model_label)))
        modelInput = styledField(getString(R.string.settings_model_hint))
        settingsCard.addView(modelInput)
        fetchModelsButton = styledButton(getString(R.string.settings_fetch), false).apply {
            setOnClickListener { fetchModels() }
        }
        saveSettingsButton = styledButton(getString(R.string.settings_save), false).apply {
            setOnClickListener { saveProviderSettings() }
        }
        settingsCard.addView(buttonRow(fetchModelsButton, saveSettingsButton))
        settingsStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        settingsCard.addView(settingsStatus)
        addCard(root, settingsCard)

        // بطاقة المحادثة مع النموذج — تشرح قبل التنفيذ، وتقرأ الكود عند
        // الحاجة (قراءة فقط). الرسائل في نفس تمريرة الصفحة بلا تمرير
        // متداخل، وتُعرض آخر 30 دوراً فقط.
        val chatCard = sectionCard()
        chatCard.addView(sectionTitle(getString(R.string.chat_title)))
        chatMessagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        chatCard.addView(chatMessagesLayout)
        chatInput = styledField(getString(R.string.chat_hint)).apply {
            minHeight = dp(48)
        }
        chatCard.addView(chatInput)
        chatSendButton = styledButton(getString(R.string.chat_send), true).apply {
            setOnClickListener { sendChat() }
        }
        chatCard.addView(chatSendButton, fullWidthParams())
        chatRunButton = styledButton(getString(R.string.chat_run), false).apply {
            setOnClickListener {
                val instructions = chatSession.instructionsForAnalysis()
                if (instructions.isBlank()) {
                    Toast.makeText(this@MainActivity, R.string.chat_no_instructions, Toast.LENGTH_SHORT).show()
                } else {
                    runAnalysis(instructions)
                }
            }
        }
        chatClearButton = styledButton(getString(R.string.chat_clear), false).apply {
            setOnClickListener {
                chatSession = ChatSession()
                chatLog = SessionLog()
                ChatStore.save(mcpDir(), chatSession, chatLog)
                renderChat()
            }
        }
        chatCard.addView(buttonRow(chatRunButton, chatClearButton))
        addCard(root, chatCard)

        // بطاقة النتائج
        val resultsCard = sectionCard()
        val resultsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val resultsTitle = sectionTitle(getString(R.string.results_title)).apply {
            setPadding(0, 0, 0, 0)
        }
        resultsHeader.addView(
            resultsTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val clearButton = styledButton(getString(R.string.clear_results), false).apply {
            setOnClickListener { resultView.text = "" }
        }
        resultsHeader.addView(clearButton)
        resultsCard.addView(resultsHeader)
        resultView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFE0E0E0.toInt())
            minHeight = dp(120)
            setPadding(0, dp(8), 0, 0)
        }
        resultsCard.addView(resultView)
        addCard(root, resultsCard)

        setContentView(scroll)
        loadProviderSettings()
        chatSession = ChatStore.load(mcpDir())
        renderChat()
    }

    // ——— عناصر الواجهة الداكنة الرسمية ———

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: Int, stroke: Int = 0, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(0xFF1E1E1E.toInt(), 0xFF333333.toInt())
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun addCard(parent: LinearLayout, card: LinearLayout) {
        parent.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(0xFFC9A227.toInt())
        setPadding(0, 0, 0, dp(10))
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFFB0B0B0.toInt())
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun styledField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(0xFFF5F5F5.toInt())
        setHintTextColor(0xFF9E9E9E.toInt())
        background = roundedBg(0xFF2A2A2A.toInt(), 0xFF444444.toInt(), 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(48)
    }

    private fun styledButton(text: String, primary: Boolean): Button = Button(this).apply {
        this.text = text
        textSize = if (primary) 17f else 15f
        setTextColor(0xFFFFFFFF.toInt())
        if (primary) {
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(0xFF1B5E4B.toInt(), 0, 10)
        } else {
            background = roundedBg(0xFF2A2A2A.toInt(), 0xFF444444.toInt(), 10)
        }
        minHeight = dp(if (primary) 54 else 48)
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { i, b ->
                addView(
                    b,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) leftMargin = dp(8)
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    },
                )
            }
        }

    private fun fullWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }

    private fun setBusy(busy: Boolean) {
        fetchButton.isEnabled = !busy
        gateButton.isEnabled = !busy
        stressButton.isEnabled = !busy
        logButton.isEnabled = !busy
        analysisButton.isEnabled = !busy
        fetchModelsButton.isEnabled = !busy
        saveSettingsButton.isEnabled = !busy
        chatSendButton.isEnabled = !busy
        chatRunButton.isEnabled = !busy
        chatClearButton.isEnabled = !busy
        shareButton.isEnabled = !busy && lastDir != null
    }

    /** يعيد رسم أدوار المحادثة (آخر 30 دوراً) في بطاقة المحادثة. */
    private fun renderChat() {
        chatMessagesLayout.removeAllViews()
        val turns = chatSession.turns()
        if (turns.isEmpty()) {
            chatMessagesLayout.addView(TextView(this).apply {
                text = getString(R.string.chat_empty)
                textSize = 14f
                setTextColor(0xFF9E9E9E.toInt())
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        turns.takeLast(30).forEach { turn ->
            val who = if (turn.role == "user") {
                getString(R.string.chat_you)
            } else {
                getString(R.string.chat_model)
            }
            chatMessagesLayout.addView(TextView(this).apply {
                text = "$who: ${turn.content}"
                textSize = 15f
                setTextColor(0xFFF5F5F5.toInt())
                background = roundedBg(
                    if (turn.role == "user") 0xFF1B5E4B.toInt() else 0xFF2A2A2A.toInt(),
                    0, 10,
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) })
        }
    }

    /** يرسل رسالة محادثة للنموذج؛ قد يقرأ الكود ليشرح، أو يطلب «نفّذ». */
    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isBlank()) return
        val settings = settingsFromFields()
        if (!settings.isConfigured()) {
            settingsStatus.text = getString(R.string.analysis_no_provider)
            return
        }
        providerSettings = settings
        chatInput.setText("")
        setBusy(true)
        statusView.text = getString(R.string.chat_thinking)
        thread {
            val client = McpClient()
            chatLog = SessionLog()
            val runner = ToolRunner(client::callTool, chatLog)
            try {
                client.connect()
                val model = ModelClient(settings.toModelConfig())
                val target = targetInput.text.toString().trim().ifBlank { null }
                val agent = ChatAgent(
                    model::chat, runner, target,
                    lastReportJson?.take(2000),
                )
                val outcome = agent.respond(chatSession, text) { step ->
                    runOnUiThread { statusView.text = step }
                }
                val dir = mcpDir()
                ChatStore.save(dir, chatSession, chatLog)
                lastDir = dir
                runOnUiThread {
                    renderChat()
                    setBusy(false)
                    shareButton.isEnabled = true
                    if (outcome is ChatOutcome.RunAnalysis) {
                        statusView.text = getString(R.string.chat_run_started)
                        runAnalysis(outcome.instructions)
                    }
                }
            } catch (e: McpException) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: "")
                    recoverChatMessage(text)
                    setBusy(false)
                }
            } catch (e: ModelException) {
                runOnUiThread {
                    statusView.text = getString(R.string.analysis_failed, e.message ?: "")
                    recoverChatMessage(text)
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.analysis_failed, e.message ?: e.toString())
                    recoverChatMessage(text)
                    setBusy(false)
                }
            } finally {
                runner.shutdown()
            }
        }
    }

    /**
     * إن فشلت رسالة المحادثة قبل أن يسجّلها الوكيل (مثلاً فشل الاتصال
     * بخدمة MT قبل الرد)، تُحفظ رسالة المستخدم كما هي حتى لا تضيع من
     * الشاشة — فيعيد إرسالها لاحقاً.
     */
    private fun recoverChatMessage(text: String) {
        if (chatSession.turns().lastOrNull()?.content != text) {
            chatSession.addUser(text)
            ChatStore.save(mcpDir(), chatSession, chatLog)
        }
        renderChat()
    }

    private fun mcpDir(): File = File(filesDir, "mcp")

    private fun fetchTools() {
        setBusy(true)
        statusView.text = getString(R.string.status_fetching)
        resultView.text = ""
        thread {
            try {
                val client = McpClient()
                client.connect()
                val tools = client.listTools()
                val dir = mcpDir()
                val (jsonFile, _) = ToolsListStore.save(
                    dir, tools, client.lastServerName, client.lastServerVersion
                )
                lastDir = dir
                runOnUiThread {
                    statusView.text = getString(
                        R.string.status_ok, tools.size,
                        client.lastServerName, client.lastServerVersion
                    )
                    resultView.text = tools.joinToString("\n") { tool ->
                        "• ${tool.name}" + if (!tool.description.isNullOrBlank()) {
                            "\n  ${tool.description}"
                        } else {
                            ""
                        }
                    } + "\n\n" + getString(R.string.saved_to, jsonFile.absolutePath)
                    setBusy(false)
                    shareButton.isEnabled = true
                }
            } catch (e: McpException) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: "")
                    resultView.text = getString(R.string.how_to_start_service)
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: e.toString())
                    setBusy(false)
                }
            }
        }
    }

    /** اختبار البوابة عبر المغلّف: يُسجَّل كل استدعاء في سجل الجلسة. */
    private fun runGateTest() {
        setBusy(true)
        statusView.text = getString(R.string.status_gate_running)
        resultView.text = ""
        thread {
            val client = McpClient()
            val log = SessionLog()
            val runner = ToolRunner(client::callTool, log)
            try {
                client.connect()
                val tester = GateTester { name, args -> runner.call(name, args) }
                val result = tester.run { step ->
                    runOnUiThread { statusView.text = step }
                }
                val dir = mcpDir()
                GateTestStore.save(dir, result)
                SessionLogStore.save(dir, log)
                lastDir = dir
                runOnUiThread {
                    statusView.text = if (result.success) {
                        getString(
                            R.string.gate_ok,
                            GateTester.TEST_PACKAGE,
                            GateTester.GATE_CLASS_SIMPLE_NAME,
                        )
                    } else {
                        getString(R.string.gate_failed, result.summary)
                    }
                    resultView.text = result.report
                    setBusy(false)
                    shareButton.isEnabled = true
                }
            } catch (e: McpException) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: "")
                    resultView.text = getString(R.string.how_to_start_service)
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: e.toString())
                    setBusy(false)
                }
            } finally {
                runner.shutdown()
            }
        }
    }

    /** اختبار إجهاد طبقة الأدوات: ٢٠ استدعاء متتالياً على التطبيق الهدف. */
    private fun runStressTest() {
        val target = targetInput.text.toString().trim().ifBlank { StressTester.DEFAULT_TARGET }
        setBusy(true)
        statusView.text = getString(R.string.status_stress_running)
        resultView.text = ""
        thread {
            val client = McpClient()
            val log = SessionLog()
            val runner = ToolRunner(client::callTool, log)
            try {
                client.connect()
                val tester = StressTester(runner, log, target)
                val result = tester.run { step ->
                    runOnUiThread { statusView.text = step }
                }
                val dir = mcpDir()
                SessionLogStore.save(dir, log)
                StressTestStore.save(dir, result)
                lastDir = dir
                runOnUiThread {
                    statusView.text = if (result.success) {
                        getString(R.string.stress_ok, result.callsOk, result.targetPackage)
                    } else {
                        getString(R.string.stress_failed, result.summary)
                    }
                    resultView.text = result.report
                    setBusy(false)
                    shareButton.isEnabled = true
                }
            } catch (e: McpException) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: "")
                    resultView.text = getString(R.string.how_to_start_service)
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: e.toString())
                    setBusy(false)
                }
            } finally {
                runner.shutdown()
            }
        }
    }

    /** يعرض سجل الجلسة النصي المحفوظ (إن وُجد). */
    private fun viewSessionLog() {
        thread {
            val file = File(mcpDir(), SessionLogStore.TEXT_FILE)
            val text = if (file.exists()) file.readText() else null
            runOnUiThread {
                if (text != null) {
                    resultView.text = text
                    statusView.text = getString(R.string.saved_to, file.absolutePath)
                } else {
                    Toast.makeText(this, R.string.no_log_yet, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** يحمّل إعدادات المزوّد المحفوظة (أو الافتراضي DeepSeek) ويملأ الحقول. */
    private fun loadProviderSettings() {
        val json = getSharedPreferences("musaed_analysis", Context.MODE_PRIVATE)
            .getString("provider_settings", null)
        providerSettings = ProviderSettings.fromJson(json) ?: ProviderSettings.deepSeek()
        fillProviderFields(providerSettings)
    }

    private fun fillProviderFields(s: ProviderSettings) {
        providerNameInput.setText(s.name)
        apiKeyInput.setText(s.apiKey)
        baseUrlInput.setText(s.baseUrl)
        apiPathInput.setText(s.apiPath)
        modelInput.setText(s.model)
    }

    private fun settingsFromFields(): ProviderSettings {
        return ProviderSettings(
            name = providerNameInput.text.toString().trim(),
            apiKey = apiKeyInput.text.toString().trim(),
            baseUrl = baseUrlInput.text.toString().trim(),
            apiPath = apiPathInput.text.toString().trim().ifBlank { "/chat/completions" },
            model = modelInput.text.toString().trim(),
        )
    }

    /** يطبّق إعداداً جاهزاً (DeepSeek أو GonkaRouter) مع إبقاء المفتاح المدخل. */
    private fun applyProviderPreset(preset: ProviderSettings) {
        providerSettings = preset
        fillProviderFields(preset)
        settingsStatus.text = getString(R.string.settings_preset_applied, preset.name)
    }

    /** يحفظ إعدادات المزوّد في الجهاز فقط. */
    private fun saveProviderSettings() {
        providerSettings = settingsFromFields()
        getSharedPreferences("musaed_analysis", Context.MODE_PRIVATE)
            .edit()
            .putString("provider_settings", providerSettings.toJson())
            .apply()
        settingsStatus.text = if (providerSettings.isConfigured()) {
            getString(R.string.settings_saved, providerSettings.name, providerSettings.model)
        } else {
            getString(R.string.settings_saved_incomplete)
        }
    }

    /** يجلب نماذج المزوّد الفعلية (GET /models) لاختيار النموذج منها. */
    private fun fetchModels() {
        val settings = settingsFromFields()
        if (settings.apiKey.isBlank()) {
            settingsStatus.text = getString(R.string.settings_no_key)
            return
        }
        fetchModelsButton.isEnabled = false
        settingsStatus.text = getString(R.string.settings_fetching)
        resultView.text = ""
        thread {
            try {
                val client = ModelClient(settings.toModelConfig())
                val models = client.listModels()
                runOnUiThread {
                    fetchModelsButton.isEnabled = true
                    if (models.isEmpty()) {
                        settingsStatus.text = getString(R.string.settings_no_models, settings.name)
                    } else {
                        if (modelInput.text.toString().isBlank()) {
                            modelInput.setText(models[0])
                        }
                        settingsStatus.text = getString(R.string.settings_models, settings.name, models.size)
                        resultView.text = models.joinToString("\n") { "• $it" }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    fetchModelsButton.isEnabled = true
                    settingsStatus.text = getString(R.string.settings_fetch_failed, e.message ?: e.toString())
                }
            }
        }
    }

    /** التحليل الكامل (المرحلة 3): الفحص السريع ثم حلقة الوكيل ببرومبت v1.1.
     *  تعليمات المحادثة — إن وُجدت — تُمرَّر للحلقة مع رسالة المستخدم الأولى. */
    private fun runAnalysis(userInstructions: String? = null) {
        val settings = settingsFromFields()
        if (!settings.isConfigured()) {
            settingsStatus.text = getString(R.string.analysis_no_provider)
            return
        }
        providerSettings = settings
        val target = targetInput.text.toString().trim().ifBlank { StressTester.DEFAULT_TARGET }
        setBusy(true)
        statusView.text = getString(R.string.status_analysis_running)
        resultView.text = ""
        thread {
            val client = McpClient()
            val log = SessionLog()
            val runner = ToolRunner(client::callTool, log)
            try {
                client.connect()
                val model = ModelClient(settings.toModelConfig())
                val loop = AnalysisLoop(model::chat, runner, log, SystemPrompt.TEXT)
                val result = loop.run(target, userInstructions) { step ->
                    runOnUiThread { statusView.text = step }
                }
                val dir = mcpDir()
                SessionLogStore.save(dir, log)
                AnalysisStore.save(dir, result)
                lastDir = dir
                lastReportJson = result.reportJson
                runOnUiThread {
                    statusView.text = if (result.success) {
                        getString(R.string.analysis_ok, result.stepsUsed, result.targetPackage)
                    } else {
                        getString(R.string.analysis_failed, result.notes.lastOrNull() ?: "")
                    }
                    resultView.text = buildString {
                        if (result.reportJson != null) appendLine(result.reportJson)
                        result.notes.forEach { appendLine("ملاحظة: $it") }
                    }
                    setBusy(false)
                    shareButton.isEnabled = true
                }
            } catch (e: McpException) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: "")
                    resultView.text = getString(R.string.how_to_start_service)
                    setBusy(false)
                }
            } catch (e: ModelException) {
                runOnUiThread {
                    statusView.text = getString(R.string.analysis_failed, e.message ?: "")
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.analysis_failed, e.message ?: e.toString())
                    setBusy(false)
                }
            } finally {
                runner.shutdown()
            }
        }
    }

    private fun shareList() {
        val dir = lastDir ?: return
        val uris = ArrayList<Uri>()
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
            uris.add(FileProvider.getUriForFile(this, "${packageName}.fileprovider", file))
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_list)))
    }
}
