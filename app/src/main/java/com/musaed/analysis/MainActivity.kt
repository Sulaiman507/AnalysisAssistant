package com.musaed.analysis

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.musaed.analysis.mcp.GateTestStore
import com.musaed.analysis.mcp.GateTester
import com.musaed.analysis.mcp.McpClient
import com.musaed.analysis.mcp.McpException
import com.musaed.analysis.mcp.SessionLog
import com.musaed.analysis.mcp.SessionLogStore
import com.musaed.analysis.mcp.StressTestStore
import com.musaed.analysis.mcp.StressTester
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
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(shareButton)
        setContentView(root)
    }

    private fun setBusy(busy: Boolean) {
        fetchButton.isEnabled = !busy
        gateButton.isEnabled = !busy
        stressButton.isEnabled = !busy
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

    private fun shareList() {
        val dir = lastDir ?: return
        val uris = ArrayList<Uri>()
        listOf(
            ToolsListStore.JSON_FILE,
            ToolsListStore.TEXT_FILE,
            GateTestStore.GATE_FILE,
            SessionLogStore.JSON_FILE,
            SessionLogStore.TEXT_FILE,
            StressTestStore.STRESS_FILE,
        ).map { File(dir, it) }.filter { it.exists() }.forEach { file ->
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
