package com.musaed.analysis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.musaed.analysis.mcp.AnalysisLoop
import com.musaed.analysis.mcp.AnalysisResult
import com.musaed.analysis.mcp.AnalysisStore
import com.musaed.analysis.mcp.ChatAgent
import com.musaed.analysis.mcp.ChatOutcome
import com.musaed.analysis.mcp.ChatSession
import com.musaed.analysis.mcp.ChatStore
import com.musaed.analysis.mcp.EvidenceCard
import com.musaed.analysis.mcp.GateTestStore
import com.musaed.analysis.mcp.GateTester
import com.musaed.analysis.mcp.McpClient
import com.musaed.analysis.mcp.McpException
import com.musaed.analysis.mcp.ModelClient
import com.musaed.analysis.mcp.ModelException
import com.musaed.analysis.mcp.ProviderSettings
import com.musaed.analysis.mcp.ReportView
import com.musaed.analysis.mcp.SessionLog
import com.musaed.analysis.mcp.SessionLogStore
import com.musaed.analysis.mcp.StressTestStore
import com.musaed.analysis.mcp.StressTester
import com.musaed.analysis.mcp.SystemPrompt
import com.musaed.analysis.mcp.ToolRunner
import com.musaed.analysis.mcp.ToolsListStore
import java.io.File
import java.util.Locale
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

    companion object {
        private const val REQUEST_PICK_APK = 1001
    }

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

    // حد خطوات قراءة المحادثة: يُحفظ في الجهاز، الافتراضي 5،
    // وبلا حد = Int.MAX_VALUE (يختاره المستخدم من تبويب الإعدادات).
    private lateinit var chatLimitInput: EditText
    private lateinit var chatUnlimitedCheck: CheckBox
    private lateinit var chatLimitSaveButton: Button
    private lateinit var chatLimitStatus: TextView
    private var chatMaxToolSteps: Int = ChatAgent.DEFAULT_MAX_TOOL_STEPS

    // حد خطوات التحليل الكامل: يُحفظ في الجهاز، الافتراضي 80،
    // وبلا حد = Int.MAX_VALUE (يختاره المستخدم من تبويب الإعدادات).
    private lateinit var analysisLimitInput: EditText
    private lateinit var analysisUnlimitedCheck: CheckBox
    private lateinit var analysisLimitSaveButton: Button
    private lateinit var analysisLimitStatus: TextView
    private var analysisMaxSteps: Int = AnalysisLoop.DEFAULT_MAX_STEPS

    private var lastDir: File? = null

    // اختيار ملف APK (المرحلة 5): يُنسخ الملف المختار إلى مجلد الملفات
    // الخارجية للتطبيق — فخدمة MCP (عملية MT Manager) لا تستطيع قراءة
    // المجلد الداخلي الخاص بالتطبيق، بينما التخزين الخارجي مقروء لها.
    // الملف المختار يقدَّم على الحزمة المكتوبة في الحقل.
    private var chosenApkPath: String? = null
    private var chosenApkName: String? = null
    private lateinit var apkFileLabel: TextView

    // التقرير المنظّم (المرحلة 5): حاوية تُبنى من جديد مع كل تحليل ناجح،
    // وresultView يبقى للنتائج النصية البسيطة (قائمة أدوات، بوابة، إجهاد).
    private lateinit var resultsContainer: LinearLayout
    private lateinit var rawJsonView: TextView
    private lateinit var jsonToggleButton: Button

    // التبويبات الثلاثة: الرئيسية (الهدف + الإجراءات + النتائج)، المحادثة،
    // الإعدادات. العنوان والحالة ثابتان فوق التبويبات وتبديلها لا يمس
    // حالة الأزرار — فيبدّل المستخدم التبويب أثناء تشغيل عملية.
    private lateinit var mainTabButton: Button
    private lateinit var chatTabButton: Button
    private lateinit var settingsTabButton: Button
    private lateinit var mainScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // الجذر عمودي: عنوان + حالة ثابتان، ثم شريط تبويبات، ثم محتوى
        // التبويب النشط فقط (يملأ باقي الشاشة). كل تبويب له تمريره الخاص.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFF5F5F5.toInt())
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        statusView = TextView(this).apply {
            text = getString(R.string.status_not_checked)
            textSize = 15f
            setTextColor(0xFFE0E0E0.toInt())
            setPadding(dp(16), dp(6), dp(16), dp(4))
        }
        root.addView(titleView)
        root.addView(statusView)

        // شريط التبويبات
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        mainTabButton = tabButton(getString(R.string.tab_main), 0)
        chatTabButton = tabButton(getString(R.string.tab_chat), 1)
        settingsTabButton = tabButton(getString(R.string.tab_settings), 2)
        tabBar.addView(mainTabButton, tabButtonParams())
        tabBar.addView(chatTabButton, tabButtonParams())
        tabBar.addView(settingsTabButton, tabButtonParams())
        root.addView(tabBar)

        // حاويات التبويبات — الظاهر منها واحد فقط في كل لحظة
        val tabContentParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        mainScroll = ScrollView(this).apply { addView(mainContent) }
        root.addView(mainScroll, tabContentParams)

        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        settingsScroll = ScrollView(this).apply {
            addView(settingsContent)
            visibility = View.GONE
        }
        root.addView(settingsScroll, tabContentParams)

        buildChatTab(root, tabContentParams)

        // بطاقة الهدف
        val targetCard = sectionCard()
        targetCard.addView(sectionTitle(getString(R.string.target_title)))
        targetInput = styledField(getString(R.string.target_package_hint)).apply {
            setText(StressTester.DEFAULT_TARGET)
        }
        targetCard.addView(targetInput)
        // اختيار ملف APK (المرحلة 5): زر + اسم الملف المختار + زر إلغاء.
        val apkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val pickApkButton = styledButton(getString(R.string.pick_apk), false).apply {
            setOnClickListener { pickApkFile() }
        }
        apkRow.addView(
            pickApkButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        apkFileLabel = TextView(this).apply {
            text = getString(R.string.apk_none)
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        apkRow.addView(
            apkFileLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f),
        )
        val clearApkButton = styledButton(getString(R.string.apk_clear), false).apply {
            setOnClickListener { clearChosenApk() }
        }
        apkRow.addView(clearApkButton)
        targetCard.addView(apkRow)
        addCard(mainContent, targetCard)

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
        addCard(mainContent, actionsCard)

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
        addCard(settingsContent, settingsCard)

        // بطاقة إعدادات المحادثة — حد خطوات القراءة في كل رسالة
        val chatSettingsCard = sectionCard()
        chatSettingsCard.addView(sectionTitle(getString(R.string.chat_settings_title)))
        chatSettingsCard.addView(fieldLabel(getString(R.string.chat_limit_label)))
        chatLimitInput = styledField(getString(R.string.chat_limit_hint)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        chatSettingsCard.addView(chatLimitInput)
        chatUnlimitedCheck = CheckBox(this).apply {
            text = getString(R.string.chat_unlimited)
            textSize = 15f
            setTextColor(0xFFF5F5F5.toInt())
            setOnCheckedChangeListener { _, checked ->
                chatLimitInput.isEnabled = !checked
            }
        }
        chatSettingsCard.addView(chatUnlimitedCheck)
        chatSettingsCard.addView(TextView(this).apply {
            text = getString(R.string.chat_limit_warning)
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        chatLimitSaveButton = styledButton(getString(R.string.chat_limit_save), false).apply {
            setOnClickListener { saveChatLimit() }
        }
        chatSettingsCard.addView(chatLimitSaveButton, fullWidthParams())
        chatLimitStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        chatSettingsCard.addView(chatLimitStatus)
        addCard(settingsContent, chatSettingsCard)

        // بطاقة إعدادات التحليل — حد خطوات التحليل الكامل
        val analysisSettingsCard = sectionCard()
        analysisSettingsCard.addView(sectionTitle(getString(R.string.analysis_settings_title)))
        analysisSettingsCard.addView(fieldLabel(getString(R.string.analysis_limit_label)))
        analysisLimitInput = styledField(getString(R.string.analysis_limit_hint)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        analysisSettingsCard.addView(analysisLimitInput)
        analysisUnlimitedCheck = CheckBox(this).apply {
            text = getString(R.string.analysis_unlimited)
            textSize = 15f
            setTextColor(0xFFF5F5F5.toInt())
            setOnCheckedChangeListener { _, checked ->
                analysisLimitInput.isEnabled = !checked
            }
        }
        analysisSettingsCard.addView(analysisUnlimitedCheck)
        analysisSettingsCard.addView(TextView(this).apply {
            text = getString(R.string.analysis_limit_warning)
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        analysisLimitSaveButton = styledButton(getString(R.string.analysis_limit_save), false).apply {
            setOnClickListener { saveAnalysisLimit() }
        }
        analysisSettingsCard.addView(analysisLimitSaveButton, fullWidthParams())
        analysisLimitStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        analysisSettingsCard.addView(analysisLimitStatus)
        addCard(settingsContent, analysisSettingsCard)

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
            setOnClickListener { resetResults() }
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
        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        resultsCard.addView(resultsContainer)
        addCard(mainContent, resultsCard)

        setContentView(root)
        loadProviderSettings()
        loadChatLimit()
        loadAnalysisLimit()
        chatSession = ChatStore.load(mcpDir())
        renderChat()
        showTab(0)
    }

    /**
     * يبني تبويب المحادثة: رسائل تملأ الشاشة وتمرّر وحدها، وصف ثابت في
     * الأسفل فيه حقل الكتابة وزر الإرسال — مثل أي تطبيق محادثة. فوقه
     * صف «نفّذ» و«مسح».
     */
    private fun buildChatTab(root: LinearLayout, tabContentParams: LinearLayout.LayoutParams) {
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chatMessagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        chatScroll = ScrollView(this).apply { addView(chatMessagesLayout) }
        chatContainer.addView(
            chatScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
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
        val actionsRow = buttonRow(chatRunButton, chatClearButton).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        chatContainer.addView(actionsRow)
        chatInput = styledField(getString(R.string.chat_hint)).apply {
            minHeight = dp(48)
        }
        chatSendButton = styledButton(getString(R.string.chat_send), true).apply {
            setOnClickListener { sendChat() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        inputRow.addView(
            chatInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        inputRow.addView(
            chatSendButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
        chatContainer.addView(inputRow)
        root.addView(chatContainer, tabContentParams)
    }

    /** زر تبويب: ينقّل لتبويبه عند الضغط. */
    private fun tabButton(text: String, index: Int): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        minHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { showTab(index) }
    }

    private fun tabButtonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }

    private fun styleTabButton(button: Button, selected: Boolean) {
        button.background = roundedBg(
            if (selected) 0xFF1B5E4B.toInt() else 0xFF2A2A2A.toInt(),
            if (selected) 0 else 0xFF444444.toInt(),
            10,
        )
        button.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
        button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    /** يُظهر تبويباً واحداً ويخفي الآخرين، ويحدّث شكل شريط التبويبات. */
    private fun showTab(index: Int) {
        currentTab = index
        mainScroll.visibility = if (index == 0) View.VISIBLE else View.GONE
        chatContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
        settingsScroll.visibility = if (index == 2) View.VISIBLE else View.GONE
        styleTabButton(mainTabButton, index == 0)
        styleTabButton(chatTabButton, index == 1)
        styleTabButton(settingsTabButton, index == 2)
        if (index == 1) chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
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
        chatLimitSaveButton.isEnabled = !busy
        analysisLimitSaveButton.isEnabled = !busy
        chatSendButton.isEnabled = !busy
        chatRunButton.isEnabled = !busy
        chatClearButton.isEnabled = !busy
        shareButton.isEnabled = !busy && lastDir != null
    }

    /** يعيد رسم أدوار المحادثة (آخر 30 دوراً) في تبويب المحادثة. */
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
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
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
                val target = chosenApkPath
                    ?: targetInput.text.toString().trim().ifBlank { null }
                val agent = ChatAgent(
                    model::chat, runner, target,
                    lastReportJson?.take(2000),
                    maxToolSteps = chatMaxToolSteps,
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
                    } else {
                        statusView.text = getString(R.string.chat_answered)
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
        resetResults()
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
        resetResults()
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
        resetResults()
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
                    resetResults()
                    resultView.text = text
                    statusView.text = getString(R.string.saved_to, file.absolutePath)
                } else {
                    Toast.makeText(this, R.string.no_log_yet, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** حد خطوات التحليل: يُحفظ كعدد صحيح؛ Int.MAX_VALUE = بلا حد. */
    private fun loadAnalysisLimit() {
        analysisMaxSteps = getSharedPreferences("musaed_analysis", Context.MODE_PRIVATE)
            .getInt("analysis_max_steps", AnalysisLoop.DEFAULT_MAX_STEPS)
        fillAnalysisLimitFields()
    }

    private fun fillAnalysisLimitFields() {
        val unlimited = analysisMaxSteps == Int.MAX_VALUE
        analysisUnlimitedCheck.isChecked = unlimited
        analysisLimitInput.isEnabled = !unlimited
        analysisLimitInput.setText(if (unlimited) "" else analysisMaxSteps.toString())
    }

    /** يحفظ حد خطوات التحليل في الجهاز فقط (1–200، أو بلا حد). */
    private fun saveAnalysisLimit() {
        analysisMaxSteps = if (analysisUnlimitedCheck.isChecked) {
            Int.MAX_VALUE
        } else {
            val n = analysisLimitInput.text.toString().trim().toIntOrNull()
                ?: AnalysisLoop.DEFAULT_MAX_STEPS
            n.coerceIn(1, 200)
        }
        getSharedPreferences("musaed_analysis", Context.MODE_PRIVATE)
            .edit()
            .putInt("analysis_max_steps", analysisMaxSteps)
            .apply()
        fillAnalysisLimitFields()
        analysisLimitStatus.text = if (analysisMaxSteps == Int.MAX_VALUE) {
            getString(R.string.analysis_limit_saved_unlimited)
        } else {
            getString(R.string.analysis_limit_saved, analysisMaxSteps)
        }
    }

    /** حد خطوات القراءة: يُحفظ كعدد صحيح؛ Int.MAX_VALUE = بلا حد. */
    private fun loadChatLimit() {
        chatMaxToolSteps = getSharedPreferences("musaed_analysis", Context.MODE_PRIVATE)
            .getInt("chat_max_tool_steps", ChatAgent.DEFAULT_MAX_TOOL_STEPS)
        fillChatLimitFields()
    }

    private fun fillChatLimitFields() {
        val unlimited = chatMaxToolSteps == Int.MAX_VALUE
        chatUnlimitedCheck.isChecked = unlimited
        chatLimitInput.isEnabled = !unlimited
        chatLimitInput.setText(if (unlimited) "" else chatMaxToolSteps.toString())
    }

    /** يحفظ حد خطوات القراءة في الجهاز فقط (1–100، أو بلا حد). */
    private fun saveChatLimit() {
        chatMaxToolSteps = if (chatUnlimitedCheck.isChecked) {
            Int.MAX_VALUE
        } else {
            val n = chatLimitInput.text.toString().trim().toIntOrNull()
                ?: ChatAgent.DEFAULT_MAX_TOOL_STEPS
            n.coerceIn(1, 100)
        }
        getSharedPreferences("musaed_analysis", Context.MODE_PRIVATE)
            .edit()
            .putInt("chat_max_tool_steps", chatMaxToolSteps)
            .apply()
        fillChatLimitFields()
        chatLimitStatus.text = if (chatMaxToolSteps == Int.MAX_VALUE) {
            getString(R.string.chat_limit_saved_unlimited)
        } else {
            getString(R.string.chat_limit_saved, chatMaxToolSteps)
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
        resetResults()
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
    /** يعيد بطاقة النتائج للعرض النصي البسيط: يخفي التقرير المنظّم ويُظهر resultView. */
    private fun resetResults() {
        resultsContainer.removeAllViews()
        resultsContainer.visibility = View.GONE
        resultView.visibility = View.VISIBLE
        resultView.text = ""
    }

    /**
     * اختيار ملف APK (المرحلة 5): منتقي الملفات الكلاسيكي — فـ Activity
     * العادية لا تحمل Activity Result API دون اعتماد androidx.activity،
     * والتحقق المحلي يُبنى بلا تلك الحزمة.
     */
    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_APK)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_APK || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        statusView.text = getString(R.string.apk_copying)
        thread {
            try {
                val name = apkFileName(uri)
                if (!name.endsWith(".apk", ignoreCase = true)) {
                    runOnUiThread { statusView.text = getString(R.string.apk_not_apk) }
                    return@thread
                }
                // نسخة في التخزين الخارجي للتطبيق — مقروءة لخدمة MCP.
                val dir = File(getExternalFilesDir(null), "picked-apks").apply { mkdirs() }
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dest = File(dir, "${System.currentTimeMillis()}_$safeName")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("تعذّر فتح الملف المختار.")
                runOnUiThread {
                    chosenApkPath = dest.absolutePath
                    chosenApkName = name
                    apkFileLabel.text = getString(R.string.apk_chosen, name)
                    statusView.text = getString(R.string.apk_copied, name)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(
                        R.string.apk_copy_failed, e.message ?: e.toString(),
                    )
                }
            }
        }
    }

    private fun apkFileName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index) ?: "app.apk"
                }
            }
        return uri.lastPathSegment?.substringAfterLast("/") ?: "app.apk"
    }

    private fun clearChosenApk() {
        chosenApkPath = null
        chosenApkName = null
        apkFileLabel.text = getString(R.string.apk_none)
    }

    /**
     * يعرض تقرير التحليل منظّماً (المرحلة 5): النتيجة أولاً من JSON، ثم
     * التقرير العربي، ثم الأدلة كبطاقات قابلة للفتح، وJSON الخام خلف زر.
     * حالة «خادم صرف» تُعرض بسطرها الصريح — لا تُخفى ولا تُجمَّل.
     */
    private fun renderAnalysisResult(result: AnalysisResult) {
        resetResults()
        resultView.visibility = View.GONE
        resultsContainer.visibility = View.VISIBLE
        val summary = ReportView.parse(result.reportJson)
        val verdictLine = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFC9A227.toInt())
            text = if (summary != null) {
                val conf = summary.confidence?.let {
                    " — " + getString(
                        R.string.report_confidence,
                        "%.2f".format(Locale.US, it),
                    )
                } ?: ""
                getString(R.string.report_verdict, summary.verdictLabel) + conf
            } else {
                getString(R.string.report_verdict, "؟")
            }
        }
        resultsContainer.addView(verdictLine)
        summary?.gateText?.let { gate ->
            resultsContainer.addView(TextView(this).apply {
                textSize = 15f
                setTextColor(0xFFE0E0E0.toInt())
                text = getString(R.string.report_gate, gate)
            })
        }
        if (summary?.paymentSdk != null && summary.paymentSdk != "none") {
            resultsContainer.addView(TextView(this).apply {
                textSize = 15f
                setTextColor(0xFFE0E0E0.toInt())
                text = getString(R.string.report_sdk, summary.paymentSdk)
            })
        }
        if (summary?.verdict == "server") {
            resultsContainer.addView(TextView(this).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFC9A227.toInt())
                text = getString(R.string.report_not_modifiable)
            })
        }
        val arabicView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFE0E0E0.toInt())
            setPadding(0, dp(8), 0, 0)
            text = result.reportArabic ?: getString(R.string.report_arabic_missing)
        }
        resultsContainer.addView(arabicView)
        if (summary != null && summary.evidence.isNotEmpty()) {
            resultsContainer.addView(TextView(this).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFC9A227.toInt())
                setPadding(0, dp(12), 0, dp(4))
                text = getString(R.string.report_evidence_title)
            })
            summary.evidence.forEach { card ->
                resultsContainer.addView(evidenceCardView(card))
            }
        }
        jsonToggleButton = styledButton(getString(R.string.show_raw_json), false).apply {
            setOnClickListener {
                val showing = rawJsonView.visibility == View.VISIBLE
                rawJsonView.visibility = if (showing) View.GONE else View.VISIBLE
                text = getString(
                    if (showing) R.string.show_raw_json else R.string.hide_raw_json,
                )
            }
        }
        resultsContainer.addView(jsonToggleButton, fullWidthParams())
        rawJsonView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
            visibility = View.GONE
            text = result.reportJson ?: "{}"
        }
        resultsContainer.addView(rawJsonView)
        result.notes.forEach { note ->
            resultsContainer.addView(TextView(this).apply {
                textSize = 13f
                setTextColor(0xFFB0B0B0.toInt())
                setPadding(0, dp(6), 0, 0)
                text = "ملاحظة: $note"
            })
        }
    }

    /** بطاقة دليل واحدة: العنوان والمعرّف ظاهران، والمقتطف يُفتح بالضغط. */
    private fun evidenceCardView(card: EvidenceCard): LinearLayout {
        val detail = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE0E0E0.toInt())
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            text = card.excerpt.ifBlank { "—" }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBg(0xFF1E1E1E.toInt(), 0xFFC9A227.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            addView(TextView(this@MainActivity).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFE0E0E0.toInt())
                text = "#${card.id} — ${card.identifier.ifBlank { "؟" }}"
            })
            addView(TextView(this@MainActivity).apply {
                textSize = 13f
                setTextColor(0xFFB0B0B0.toInt())
                text = getString(R.string.report_evidence_tool, card.tool.ifBlank { "؟" })
            })
            addView(detail)
            setOnClickListener {
                detail.visibility =
                    if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    private fun runAnalysis(userInstructions: String? = null) {
        val settings = settingsFromFields()
        if (!settings.isConfigured()) {
            settingsStatus.text = getString(R.string.analysis_no_provider)
            return
        }
        providerSettings = settings
        // الملف المختار يقدَّم على الحزمة المكتوبة (المرحلة 5).
        val target = chosenApkPath
            ?: targetInput.text.toString().trim().ifBlank { StressTester.DEFAULT_TARGET }
        setBusy(true)
        statusView.text = getString(R.string.status_analysis_running)
        resetResults()
        thread {
            val client = McpClient()
            val log = SessionLog()
            val runner = ToolRunner(client::callTool, log)
            try {
                client.connect()
                val model = ModelClient(settings.toModelConfig())
                val loop = AnalysisLoop(model::chat, runner, log, SystemPrompt.TEXT, maxSteps = analysisMaxSteps)
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
                    if (result.success) {
                        renderAnalysisResult(result)
                    } else {
                        resultView.text = buildString {
                            result.notes.forEach { appendLine("ملاحظة: $it") }
                        }
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
