package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportViewTest {

    private val serverReport = """
        {"obfuscation": "high", "verdict": "server", "confidence": 0.7,
         "evidence": [
           {"id": 1, "tool": "mt_apk_dex_read_class_outline", "identifier": "Llf/j;", "excerpt": "billing manager"},
           {"id": 2, "tool": "mt_apk_dex_read_pseudocode", "identifier": "Llf/j;->E()Z", "excerpt": "if (x == null) return false;"}
         ],
         "gate": {"class": "Llf/j", "method": "E", "signature": "()Z"},
         "payment_sdk": "BillingClient",
         "signature_check": {"found": false, "evidence_id": 0},
         "patch_plan": [], "open_questions": ["أين فحص حالة الاشتراك؟"]}
    """.trimIndent()

    @Test
    fun `parses a full server report`() {
        val summary = ReportView.parse(serverReport)!!
        assertEquals("server", summary.verdict)
        assertEquals("خادم صرف", summary.verdictLabel)
        assertEquals(0.7, summary.confidence!!, 0.001)
        assertEquals("Llf/j.E()Z", summary.gateText)
        assertEquals(2, summary.evidence.size)
        assertEquals(2, summary.evidence[1].id)
        assertEquals("mt_apk_dex_read_pseudocode", summary.evidence[1].tool)
        assertEquals("Llf/j;->E()Z", summary.evidence[1].identifier)
        assertTrue(summary.patchPlanEmpty)
        assertEquals("BillingClient", summary.paymentSdk)
    }

    @Test
    fun `verdict labels cover all five values`() {
        assertEquals("محلي صرف", ReportView.verdictLabel("local"))
        assertEquals("محلي مع كاش خادم", ReportView.verdictLabel("local_cached"))
        assertEquals("خادم صرف", ReportView.verdictLabel("server"))
        assertEquals("غير موجود", ReportView.verdictLabel("none"))
        assertEquals("غير مؤكد", ReportView.verdictLabel("uncertain"))
        assertEquals("weird", ReportView.verdictLabel("weird"))
    }

    @Test
    fun `garbage and missing verdict return null`() {
        assertNull(ReportView.parse("ليس JSON"))
        assertNull(ReportView.parse("{}"))
        assertNull(ReportView.parse(null))
    }

    @Test
    fun `missing optional fields default safely`() {
        val summary = ReportView.parse("""{"verdict": "local"}""")!!
        assertEquals("local", summary.verdict)
        assertNull(summary.confidence)
        assertNull(summary.gateText)
        assertTrue(summary.evidence.isEmpty())
        assertTrue(summary.patchPlanEmpty)
        assertNull(summary.paymentSdk)
    }
}
