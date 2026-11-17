package com.musaed.analysis.mcp

import java.net.HttpURLConnection

/**
 * الاتصال الجاري الوحيد (زر «إيقاف إجباري» — 2026-11-17): يتتبع آخر
 * اتصال HTTP نشط لعميل النموذج أو عميل MCP فيقطعه disconnect() فيُجهض
 * القراءة المعلقة فوراً بدل انتظار المهلة. التطبيق يشغّل محادثة أو
 * تحليلاً واحداً في كل مرة (setBusy)، فاتصال واحد نشط يكفي عملياً؛
 * وإن لم يكن هناك اتصال جارٍ فالإلغاء بلا أثر، ويبقى علم الإيقاف في
 * ChatAgent هو الكابح الأساسي للحلقة.
 */
internal object ActiveHttp {
    @Volatile
    var connection: HttpURLConnection? = null

    fun cancel() {
        connection?.disconnect()
    }
}
