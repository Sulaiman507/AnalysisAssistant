package com.musaed.analysis

import android.app.Activity
import android.content.Context
import android.content.Intent
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
 * شاشة الفحص — المرحلة ٢: جلب قائمة الأدوات الحية من خدمة MT MCP، واختبار
 * البوابة، واختبار إجهاد طبقة الأدوات (٢٠ استدعاء متتالياً)، وعرض سجل
 * الجلسة. كل استدعاء أداة يمر عبر المغلّف (مهلة + إعادة محاولة واحدة)
 * ويُسجَّل في سجل الجلسة بنتيجته الخام حرفياً.
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

    private var lastDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // واجهة عربية بسيطة مبنية برمجياً (بلا Compose في المرحلة ٢)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        }
        statusView = TextView(this).apply {
            text = getString(R.string.status_not_checked)
            textSize = 16f
        }
        targetInput = EditText(this).apply {
            setText(StressTester.DEFAULT_TARGET)
            hint = getString(R.string.target_package_hint)
        }
        fetchButton = Button(this).apply {
            text = getString(R.string.fetch_tools)
            setOnClickListener { fetchTools() }
        }
        gateButton = Button(this).apply {
            text = getString(R.string.gate_test)
            setOnClickListener { runGateTest() }
        }
        stressButton = Button(this).apply {
            text = getString(R.string.stress_test)
            setOnClickListener { runStressTest() }
        }
        logButton = Button(this).apply {
            text = getString(R.string.view_log)
            setOnClickListener { viewSessionLog() }
        }
        val scroll = ScrollView(this)
        resultView = TextView(this).apply {
            textSize = 15f
        }
        scroll.addView(resultView)
        shareButton = Button(this).apply {
            text = getString(R.string.share_list)
            isEnabled = false
            setOnClickListener { shareList() }
        }
        root.addView(titleView)
        root.addView(statusView)
        root.addView(targetInput)
        root.addView(fetchButton)
        root.addView(gateButton)
        root.addView(stressButton)
        root.addView(logButton)
        // إعدادات مزوّد النموذج (المرحلة 3) — كما في شاشة «Add Provider»:
        // الاسم والمفتاح وBase URL والمسار والنموذج. المفتاح يبقى في الجهاز
        // فقط (SharedPreferences) ولا يُرسل إلا إلى المزوّد نفسه.
        val settingsTitle = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 20f
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val presetDeepSeek = Button(this).apply {
            text = getString(R.string.settings_preset_deepseek)
            setOnClickListener { applyProviderPreset(ProviderSettings.deepSeek(apiKeyInput.text.toString().trim())) }
        }
        val presetGonka = Button(this).apply {
            text = getString(R.string.settings_preset_gonka)
            setOnClickListener { applyProviderPreset(ProviderSettings.gonkaRouter(apiKeyInput.text.toString().trim())) }
        }
        presetRow.addView(
            presetDeepSeek,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        presetRow.addView(
            presetGonka,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        providerNameInput = EditText(this).apply {
            hint = getString(R.string.settings_name_hint)
        }
        apiKeyInput = EditText(this).apply {
            hint = getString(R.string.settings_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        baseUrlInput = EditText(this).apply {
            hint = getString(R.string.settings_base_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        apiPathInput = EditText(this).apply {
            hint = getString(R.string.settings_path_hint)
        }
        modelInput = EditText(this).apply {
            hint = getString(R.string.settings_model_hint)
        }
        val settingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fetchModelsButton = Button(this).apply {
            text = getString(R.string.settings_fetch)
            setOnClickListener { fetchModels() }
        }
        saveSettingsButton = Button(this).apply {
            text = getString(R.string.settings_save)
            setOnClickListener { saveProviderSettings() }
        }
        settingsRow.addView(
            fetchModelsButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        settingsRow.addView(
            saveSettingsButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        settingsStatus = TextView(this).apply {
            textSize = 15f
        }
        root.addView(settingsTitle)
        root.addView(presetRow)
        root.addView(providerNameInput)
        root.addView(apiKeyInput)
        root.addView(baseUrlInput)
        root.addView(apiPathInput)
        root.addView(modelInput)
        root.addView(settingsRow)
        root.addView(settingsStatus)
        analysisButton = Button(this).apply {
            text = getString(R.string.analysis_run)
            setOnClickListener { runAnalysis() }
        }
        root.addView(analysisButton)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(shareButton)
        setContentView(root)
        loadProviderSettings()
    }

    private fun setBusy(busy: Boolean) {
        fetchButton.isEnabled = !busy
        gateButton.isEnabled = !busy
        stressButton.isEnabled = !busy
        analysisButton.isEnabled = !busy
        shareButton.isEnabled = !busy && lastDir != null
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

    /** التحليل الكامل (المرحلة 3): الفحص السريع ثم حلقة الوكيل ببرومبت v1.1. */
    private fun runAnalysis() {
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
                val result = loop.run(target) { step ->
                    runOnUiThread { statusView.text = step }
                }
                val dir = mcpDir()
                SessionLogStore.save(dir, log)
                AnalysisStore.save(dir, result)
                lastDir = dir
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
