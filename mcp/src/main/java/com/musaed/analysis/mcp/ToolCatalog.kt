package com.musaed.analysis.mcp

/**
 * دليل الأدوات الذي يُرسَل للنموذج مع أول رسالة في حلقة التحليل.
 *
 * السبب (دليل حي 2026-11-15): أول تشغيل على الجهاز (clear) أضاع النموذج
 * كل الخطوات الـ40 دون استدعاء ناجح واحد — لأنه لم يعرف أسماء الأدوات
 * الحقيقية ولا معاملاتها، فاخترع أدوات غير موجودة أصلاً وخمن معاملات
 * خاطئة (package بدلاً من workspaceId، وclass بدلاً من locator، وpath
 * بدلاً من locator). الخادم رفض كل ذلك بـ INVALID_ARGUMENT. هذا الدليل
 * هو الحل: الأسماء والمعاملات هنا هي نفسها المستخدمة في الكود العامل
 * (GateTester وContextBuilder وStressTest وQuickScan) ومُتحقق منها حياً
 * ضد القائمة الحية (67 أداة) — وفحص verify-local.sh خطوة 1 يتحقق من كل
 * اسم mt_ في الكود ضد تلك القائمة.
 */
object ToolCatalog {

    /** أسماء الأدوات التي يسمح للنموذج باستدعائها — أي اسم آخر يُرفض محلياً. */
    val NAMES: Set<String> = setOf(
        "mt_apk_list_installed_apps",
        "mt_apk_workspace_open",
        "mt_apk_search",
        "mt_apk_dex_list_classes",
        "mt_apk_dex_read_class_outline",
        "mt_apk_dex_read_pseudocode",
        "mt_apk_read_text",
        "mt_apk_dex_xref",
        "mt_apk_list_entries",
        "mt_apk_resource_list",
        "mt_apk_read_signature",
        "mt_apk_continue",
        "mt_apk_workspace_delete",
    )

    /** نص الدليل كما يُرسل للنموذج — أسماء ومعاملات وأمثلة حرفية. */
    val TEXT: String = """
        الأدوات المتاحة — استخدم هذه الأسماء والمعاملات حرفياً فقط، ولا تخترع أداة أو معاملاً.
        كل استدعاء يحتاج workspaceId (معرّف مساحة العمل) وeditSessionId (سلسلة فارغة "").
        صيغ المعرفات (locator): كلاس "dex_class:com/foo/Bar;" — دالة "dex_method:com/foo/Bar;->name(Landroid/content/Context;)Z" — المانيفست "axml:AndroidManifest.xml".
        1) mt_apk_list_installed_apps — ابحث عن تطبيق مثبّت: {"query": "com.example.app", "appType": "user", "limit": 50}
        2) mt_apk_workspace_open — افتح مساحة عمل: {"path": "mt://installed-app/com.example.app", "temporary": true}
        3) mt_apk_search — ابحث في التطبيق: {"workspaceId": "...", "editSessionId": "", "target": "overview", "query": "premium", "queryType": "literal", "caseSensitive": false, "matchMode": "contains", "prefix": "", "includeMatchOffsets": false, "limit": 50, "snippetMaxChars": 240}
        4) mt_apk_dex_list_classes — اسرد كلاسات DEX: {"workspaceId": "...", "editSessionId": "", "prefix": "", "limit": 200}
        5) mt_apk_dex_read_class_outline — مخطط كلاس (حقوله ودوالُه): {"workspaceId": "...", "editSessionId": "", "locator": "dex_class:com/foo/Bar;", "limit": 200}
        6) mt_apk_dex_read_pseudocode — الكود الشبهي Java لدالة (ابدأ به): {"workspaceId": "...", "editSessionId": "", "locator": "dex_method:com/foo/Bar;->name(Landroid/content/Context;)Z", "style": "readable", "limit": 500, "maxChars": 49152, "startLine": 0, "startColumn": 0}
        7) mt_apk_read_text — اقرأ نصاً أو Smali أو المانيفست: {"workspaceId": "...", "editSessionId": "", "locator": "dex_class:com/foo/Bar;", "limit": 500, "maxChars": 49152, "startLine": 0, "startColumn": 0}
        8) mt_apk_dex_xref — من يستدعي هذه الدالة أو هذا الكلاس: {"workspaceId": "...", "editSessionId": "", "locator": "dex_method:com/foo/Bar;->name(Landroid/content/Context;)Z", "methodResolution": "dispatch", "limit": 50} — وللكلاس استخدم "not_applicable"
        9) mt_apk_list_entries — اسرد ملفات APK: {"workspaceId": "...", "editSessionId": "", "prefix": "", "limit": 200}
        10) mt_apk_resource_list — جدول الموارد: {"workspaceId": "...", "editSessionId": "", "view": "resource_table", "typeName": "*", "variant": "*", "limit": 200}
        11) mt_apk_read_signature — التوقيع والشهادة: {"workspaceId": "...", "editSessionId": ""}
        12) mt_apk_continue — الصفحة التالية عند hasMore: {"workspaceId": "...", "editSessionId": "", "nextCursor": "...", "limit": 200}
        13) mt_apk_workspace_delete — احذف مساحة العمل في النهاية فقط: {"workspaceId": "..."}
    """.trimIndent()
}
