package com.musaed.analysis

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.musaed.analysis.mcp.GateTestStore
import com.musaed.analysis.mcp.GateTester
import com.musaed.analysis.mcp.McpClient
import com.musaed.analysis.mcp.McpException
import com.musaed.analysis.mcp.ToolsListStore
import java.io.File
import kotlin.concurrent.thread

/**
 * شاشة الفحص — المرحلة ١: زر واحد يجلب قائمة الأدوات الحية من خدمة MT MCP
 * ويعرضها ويحفظها في ملفين (JSON + نص) لمشاركتهما، وزر «اختبار البوابة»
 * يفتح تطبيق اختبار حقيقي عبر MCP ويقرأ مخطط كلاس البوابة (بوابة قبول
 * المرحلة ١) ويحفظ النتيجة في gate_test.txt.
 *
 * ملاحظة: حزمة التطبيق com.musaed.analysis مؤقتة وقابلة للتغيير.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var fetchButton: Button
    private lateinit var gateButton: Button
    private lateinit var shareButton: Button

    private var lastDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // واجهة عربية بسيطة مبنية برمجياً (بلا Compose في المرحلة ١)
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
        fetchButton = Button(this).apply {
            text = getString(R.string.fetch_tools)
            setOnClickListener { fetchTools() }
        }
        gateButton = Button(this).apply {
            text = getString(R.string.gate_test)
            setOnClickListener { runGateTest() }
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
        root.addView(fetchButton)
        root.addView(gateButton)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(shareButton)
        setContentView(root)
    }

    private fun fetchTools() {
        fetchButton.isEnabled = false
        shareButton.isEnabled = false
        statusView.text = getString(R.string.status_fetching)
        resultView.text = ""
        thread {
            try {
                val client = McpClient()
                client.connect()
                val tools = client.listTools()
                val dir = File(filesDir, "mcp")
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
                    shareButton.isEnabled = true
                    fetchButton.isEnabled = true
                }
            } catch (e: McpException) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: "")
                    resultView.text = getString(R.string.how_to_start_service)
                    fetchButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: e.toString())
                    fetchButton.isEnabled = true
                }
            }
        }
    }

    /** اختبار البوابة: فتح تطبيق الاختبار عبر MCP وقراءة مخطط كلاس البوابة. */
    private fun runGateTest() {
        gateButton.isEnabled = false
        fetchButton.isEnabled = false
        shareButton.isEnabled = false
        statusView.text = getString(R.string.status_gate_running)
        resultView.text = ""
        thread {
            try {
                val client = McpClient()
                client.connect()
                val tester = GateTester { name, args -> client.callTool(name, args) }
                val result = tester.run { step ->
                    runOnUiThread { statusView.text = step }
                }
                val dir = File(filesDir, "mcp")
                GateTestStore.save(dir, result)
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
                    shareButton.isEnabled = true
                    gateButton.isEnabled = true
                    fetchButton.isEnabled = true
                }
            } catch (e: McpException) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: "")
                    resultView.text = getString(R.string.how_to_start_service)
                    gateButton.isEnabled = true
                    fetchButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.status_failed, e.message ?: e.toString())
                    gateButton.isEnabled = true
                    fetchButton.isEnabled = true
                }
            }
        }
    }

    private fun shareList() {
        val dir = lastDir ?: return
        val json = File(dir, ToolsListStore.JSON_FILE)
        val txt = File(dir, ToolsListStore.TEXT_FILE)
        val gate = File(dir, GateTestStore.GATE_FILE)
        val uris = ArrayList<Uri>()
        listOf(json, txt, gate).filter { it.exists() }.forEach { file ->
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
