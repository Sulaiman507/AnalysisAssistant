package com.musaed.analysis.mcp

import com.google.gson.JsonObject

/**
 * نتيجة اختبار الإجهاد (المرحلة ٢): ٢٠ استدعاء أداة متتالياً على تطبيق
 * كبير متعدد ملفات DEX، دون انهيار أو فقدان أي مخرج من السجل.
 */
data class StressTestResult(
    val success: Boolean,
    val summary: String,
    val report: String,
    val callsMade: Int,
    val callsOk: Int,
    val dexCount: Int,
    val logComplete: Boolean,
    val workspaceId: String?,
    val targetPackage: String,
)

/**
 * اختبار إجهاد طبقة الأدوات (المرحلة ٢): سكربت ثابت من ٢٠ استدعاء أداة
 * بالترتيب ضد تطبيق هدف (الافتراضي MT Manager نفسه). إن رفضت الخدمة فتح
 * الحزمة بـ APK_OPEN_BLOCKED يُفتح تطبيق مستخدم آخر تلقائياً. كل خطوة عبر
 * المغلّف (مهلة + إعادة محاولة واحدة)؛ فشل خطوة يُسجَّل ويُتابَع دون
 * انهيار. بعد الـ٢٠ تُبنى حزمة كلاس واحدة للكلاس الأول عبر بنّاء السياق
 * كدليل إضافي خارج العدّ.
 *
 * النجاح = ٢٠/٢٠ خطوة ناجحة واكتمال السجل (الخطوات ١–٢٠ حاضرة).
 */
class StressTester(
    private val runner: ToolRunner,
    private val log: SessionLog,
    private val targetPackage: String = "bin.mt.plus",
) {
    companion object {
        /** حزمة التطبيق الهدف الافتراضية: MT Manager نفسه. */
        const val DEFAULT_TARGET = "bin.mt.plus"

        private const val TOOL_LIST_INSTALLED = "mt_apk_list_installed_apps"
        private const val TOOL_WORKSPACE_OPEN = "mt_apk_workspace_open"
        private const val TOOL_LIST_ENTRIES = "mt_apk_list_entries"
        private const val TOOL_READ_SIGNATURE = "mt_apk_read_signature"
        private const val TOOL_SEARCH = "mt_apk_search"
        private const val TOOL_DEX_LIST_CLASSES = "mt_apk_dex_list_classes"
        private const val TOOL_CONTINUE = "mt_apk_continue"
        private const val TOOL_DEX_READ_OUTLINE = "mt_apk_dex_read_class_outline"
        private const val TOOL_DEX_READ_PSEUDOCODE = "mt_apk_dex_read_pseudocode"
        private const val TOOL_DEX_XREF = "mt_apk_dex_xref"
        private const val TOOL_RESOURCE_LIST = "mt_apk_resource_list"
        private const val TOOL_READ_TEXT = "mt_apk_read_text"
    }

    fun run(onStep: (String) -> Unit = {}): StressTestResult {
        val stepLines = mutableListOf<String>()
        var callsMade = 0
        var callsOk = 0
        var dexCount = 0
        var workspaceId: String? = null
        var targetPath: String? = null
        var resolvedPackage = targetPackage
        var firstClass: String? = null
        var secondClass: String? = null
        var firstMethod: String? = null
        var nextCursor: String? = null
        val candidates = mutableListOf<Pair<String, String>>()

        fun markOk(n: Int, tool: String) {
            callsOk++
            stepLines += "  $n — $tool: ناجحة ✓"
        }

        fun markFail(n: Int, tool: String, why: String) {
            stepLines += "  $n — $tool: فاشلة ✗ — $why"
        }

        fun markSkip(n: Int, tool: String, why: String) {
            stepLines += "  $n — $tool: تخطى — $why"
        }

        /** ينفذ خطوة عبر المغلّف؛ يعيد النتيجة أو null عند الفشل. */
        fun doStep(n: Int, tool: String, args: JsonObject): JsonObject? {
            callsMade++
            onStep("خطوة $n/٢٠: $tool")
            return try {
                val result = runner.call(tool, args, step = n)
                markOk(n, tool)
                result
            } catch (e: McpException) {
                markFail(n, tool, e.message ?: e.toString())
                null
            }
        }

        // الخطوة ١ — البحث عن التطبيق الهدف بين المثبّتة
        callsMade++
        onStep("خطوة ١/٢٠: $TOOL_LIST_INSTALLED")
            val found = try {
                val result = runner.call(TOOL_LIST_INSTALLED, JsonObject().apply {
                    addProperty("query", targetPackage)
                    addProperty("appType", "user")
                    addProperty("limit", 50)
                }, step = 1)
                candidates += installedApps(result)
                findInstalled(result, targetPackage)
            } catch (e: McpException) {
                null
            }
            if (found != null) {
                callsOk++
                targetPath = found.first
                resolvedPackage = found.second
                stepLines += "  ١ — $TOOL_LIST_INSTALLED: ناجحة ✓ (الهدف: $resolvedPackage)"
            } else {
                // بديل بنفس الخطوة: أول تطبيق يحمل «MT» في اسمه/حزمته
                val alt = try {
                    val result = runner.call(TOOL_LIST_INSTALLED, JsonObject().apply {
                        addProperty("query", "MT")
                        addProperty("appType", "user")
                        addProperty("limit", 50)
                    }, step = 1)
                    candidates += installedApps(result)
                    findMtApp(result)
                } catch (e: McpException) {
                    null
                }
                if (alt != null) {
                    callsOk++
                    targetPath = alt.first
                    resolvedPackage = alt.second
                    stepLines += "  ١ — $TOOL_LIST_INSTALLED: ناجحة ✓ (بديل MT: $resolvedPackage)"
                } else {
                    val msg = "لم يُعثر على التطبيق الهدف $targetPackage ولا على أي تطبيق يحمل «MT». اكتب حزمة الهدف يدوياً في حقل الحزمة ثم أعد الاختبار."
                    stepLines += "  ١ — $TOOL_LIST_INSTALLED: فاشلة ✗ — $msg"
                    return finish(
                        success = false, summary = msg, stepLines, callsMade, callsOk,
                        dexCount = 0, workspaceId = null, resolvedPackage = resolvedPackage,
                        packageNote = null,
                    )
                }
            }

        // الخطوة ٢ — فتح مساحة عمل مؤقتة؛ عند APK_OPEN_BLOCKED يُجرَّب تطبيق مستخدم آخر
        val opened = openWorkspace(targetPath, resolvedPackage, candidates, stepLines, onStep)
        callsMade++
        if (opened != null) {
            callsOk++
            resolvedPackage = opened.pkg
            targetPath = opened.path
        }
        workspaceId = opened?.result?.let {
            McpResults.firstString(McpResults.dataOf(it), "workspaceId", "workspace_id", "id")
        }
        if (workspaceId == null) {
            for (n in 3..20) markSkip(n, stepToolName(n), "لا توجد مساحة عمل")
            return finish(
                success = false,
                summary = "فشل فتح مساحة العمل للتطبيق $resolvedPackage — توقف الاختبار عند الخطوة ٢.",
                stepLines, callsMade, callsOk, dexCount = 0,
                workspaceId = null, resolvedPackage = resolvedPackage, packageNote = null,
            )
        }
        val ws = workspaceId
        dexCount = opened?.result?.let { McpResults.dexEntriesCount(McpResults.dataOf(it)) } ?: 0

        // الخطوة ٣ — سرد إدخالات ZIP وعدّ ملفات DEX
        val entries = doStep(3, TOOL_LIST_ENTRIES, JsonObject().apply {
            addProperty("workspaceId", ws)
            addProperty("editSessionId", "")
            addProperty("prefix", "")
            addProperty("limit", 200)
        })
        if (entries != null) {
            val listed = McpResults.countDexZipEntries(McpResults.dataOf(entries))
            if (listed > dexCount) dexCount = listed
        }

        // الخطوة ٤ — قراءة التوقيع
        doStep(4, TOOL_READ_SIGNATURE, JsonObject().apply {
            addProperty("workspaceId", ws)
        })

        // الخطوات ٥–٧ — بحث
        doStep(5, TOOL_SEARCH, searchArgs(ws, "overview", "premium"))
        doStep(6, TOOL_SEARCH, searchArgs(ws, "overview", "isPremium"))
        doStep(7, TOOL_SEARCH, searchArgs(ws, "dex_names", "BillingClient"))

        // الخطوة ٨ — سرد الكلاسات (الصفحة ١)
        val page1 = doStep(8, TOOL_DEX_LIST_CLASSES, JsonObject().apply {
            addProperty("workspaceId", ws)
            addProperty("editSessionId", "")
            addProperty("prefix", "")
            addProperty("limit", 200)
        })
        val page1Data = page1?.let { McpResults.dataOf(it) }
        val page1Locators = page1Data?.let { classLocatorsOf(it) } ?: emptyList()
        firstClass = page1Locators.getOrNull(0)
        secondClass = page1Locators.getOrNull(1)
        nextCursor = page1Data?.let { McpResults.nextCursorOf(it) }

        // الخطوة ٩ — الصفحة التالية (أو سرد ببادئة حزمة الكلاس الأول)
        if (nextCursor != null) {
            doStep(9, TOOL_CONTINUE, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("nextCursor", nextCursor)
                addProperty("limit", 200)
            })
        } else if (firstClass != null) {
            doStep(9, TOOL_DEX_LIST_CLASSES, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("prefix", packagePrefixOf(firstClass))
                addProperty("limit", 200)
            })
        } else {
            markSkip(9, TOOL_CONTINUE, "لا مؤشر ترقيم ولا كلاس أول")
        }

        // الخطوة ١٠ — مخطط الكلاس الأول + استخراج أول دالة
        val outline1 = if (firstClass != null) {
            doStep(10, TOOL_DEX_READ_OUTLINE, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("locator", firstClass)
                addProperty("limit", 200)
            })
        } else {
            markSkip(10, TOOL_DEX_READ_OUTLINE, "لا كلاس أول")
            null
        }
        firstMethod = outline1?.let { firstMethodLocator(McpResults.dataOf(it), firstClass!!) }

        // الخطوة ١١ — الكود الشبهي للكلاس الأول
        if (firstClass != null) {
            doStep(11, TOOL_DEX_READ_PSEUDOCODE, pseudocodeArgs(ws, firstClass, "readable"))
        } else {
            markSkip(11, TOOL_DEX_READ_PSEUDOCODE, "لا كلاس أول")
        }

        // الخطوة ١٢ — الكود الشبهي لأول دالة (أو نمط instruction على الكلاس)
        if (firstMethod != null) {
            doStep(12, TOOL_DEX_READ_PSEUDOCODE, pseudocodeArgs(ws, firstMethod, "readable"))
        } else if (firstClass != null) {
            doStep(12, TOOL_DEX_READ_PSEUDOCODE, pseudocodeArgs(ws, firstClass, "instruction"))
        } else {
            markSkip(12, TOOL_DEX_READ_PSEUDOCODE, "لا كلاس أول ولا دالة")
        }

        // الخطوة ١٣ — مراجع أول دالة
        if (firstMethod != null) {
            doStep(13, TOOL_DEX_XREF, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("locator", firstMethod)
                addProperty("methodResolution", "dispatch")
                addProperty("limit", 50)
            })
        } else {
            markSkip(13, TOOL_DEX_XREF, "لا دالة أولى")
        }

        // الخطوات ١٤–١٦ — الكلاس الثاني
        if (secondClass != null) {
            doStep(14, TOOL_DEX_READ_OUTLINE, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("locator", secondClass)
                addProperty("limit", 200)
            })
            doStep(15, TOOL_DEX_READ_PSEUDOCODE, pseudocodeArgs(ws, secondClass, "readable"))
            doStep(16, TOOL_DEX_XREF, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("locator", secondClass)
                addProperty("methodResolution", "not_applicable")
                addProperty("limit", 50)
            })
        } else {
            markSkip(14, TOOL_DEX_READ_OUTLINE, "لا كلاس ثانٍ")
            markSkip(15, TOOL_DEX_READ_PSEUDOCODE, "لا كلاس ثانٍ")
            markSkip(16, TOOL_DEX_XREF, "لا كلاس ثانٍ")
        }

        // الخطوة ١٧ — قائمة الموارد
        doStep(17, TOOL_RESOURCE_LIST, JsonObject().apply {
            addProperty("workspaceId", ws)
            addProperty("editSessionId", "")
            addProperty("view", "resource_table")
            addProperty("typeName", "*")
            addProperty("variant", "*")
            addProperty("limit", 200)
        })

        // الخطوة ١٨ — بحث في سلاسل DEX
        doStep(18, TOOL_SEARCH, searchArgs(ws, "dex_strings", "premium"))

        // الخطوة ١٩ — Smali لأول دالة (أو الكلاس إن لم توجد دالة)
        val smaliLocator = firstMethod ?: firstClass
        if (smaliLocator != null) {
            doStep(19, TOOL_READ_TEXT, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("locator", smaliLocator)
                addProperty("limit", 500)
                addProperty("maxChars", 49152)
                addProperty("startLine", 0)
                addProperty("startColumn", 0)
            })
        } else {
            markSkip(19, TOOL_READ_TEXT, "لا كلاس أول ولا دالة")
        }

        // الخطوة ٢٠ — سرد الكلاسات ببادئة حزمة الكلاس الأول
        if (firstClass != null) {
            doStep(20, TOOL_DEX_LIST_CLASSES, JsonObject().apply {
                addProperty("workspaceId", ws)
                addProperty("editSessionId", "")
                addProperty("prefix", packagePrefixOf(firstClass))
                addProperty("limit", 200)
            })
        } else {
            markSkip(20, TOOL_DEX_LIST_CLASSES, "لا كلاس أول")
        }

        // دليل إضافي خارج العدّ: حزمة كلاس واحدة للكلاس الأول
        var packageNote: String? = null
        if (firstClass != null) {
            try {
                val builder = ContextBuilder { n, a -> runner.call(n, a) }
                val pkg = builder.build(ws, firstClass)
                packageNote = "حزمة الكلاس الأول: ${pkg.classLocator} — ${pkg.methods.size} دالة، ${pkg.xrefs.size} مرجع، مقتطعة: ${if (pkg.truncated) "نعم" else "لا"}"
            } catch (e: Exception) {
                packageNote = "تعذّر بناء حزمة الكلاس الأول كدليل إضافي: ${e.message ?: e.toString()}"
            }
        }

        val logComplete = (1..20).all { it in log.stepsPresent() }
        val success = callsOk == 20 && callsMade == 20 && logComplete
        val summary = if (success) {
            "نجح اختبار الإجهاد ✓ — ٢٠/٢٠ استدعاءً على $resolvedPackage ($dexCount ملف DEX) والسجل مكتمل"
        } else {
            "فشل اختبار الإجهاد ✗ — $callsOk/٢٠ خطوة ناجحة على $resolvedPackage (السجل مكتمل: ${if (logComplete) "نعم" else "لا"})"
        }
        return finish(
            success = success, summary = summary, stepLines, callsMade, callsOk,
            dexCount = dexCount, workspaceId = ws, resolvedPackage = resolvedPackage,
            packageNote = packageNote,
        )
    }

    private fun finish(
        success: Boolean,
        summary: String,
        stepLines: List<String>,
        callsMade: Int,
        callsOk: Int,
        dexCount: Int,
        workspaceId: String?,
        resolvedPackage: String,
        packageNote: String?,
    ): StressTestResult {
        val logComplete = (1..20).all { it in log.stepsPresent() }
        val report = buildString {
            appendLine("اختبار إجهاد طبقة الأدوات — مساعد التحليل (المرحلة ٢)")
            appendLine("التطبيق الهدف: $resolvedPackage")
            appendLine("مساحة العمل: ${workspaceId ?: "—"}")
            appendLine("عدد ملفات DEX: $dexCount")
            appendLine("الاستدعاءات: $callsOk/$callsMade ناجحة (المطلوب ٢٠/٢٠)")
            appendLine("اكتمال السجل (الخطوات ١–٢٠): ${if (logComplete) "مكتمل ✓" else "غير مكتمل ✗"}")
            if (packageNote != null) appendLine(packageNote)
            appendLine("النتيجة: ${if (success) "ناجح ✓ — مرّت البوابة" else "فاشل ✗ — لم تمر البوابة"}")
            appendLine()
            appendLine("الخطوات:")
            stepLines.forEach { appendLine(it) }
        }
        return StressTestResult(
            success = success,
            summary = summary,
            report = report,
            callsMade = callsMade,
            callsOk = callsOk,
            dexCount = dexCount,
            logComplete = logComplete,
            workspaceId = workspaceId,
            targetPackage = resolvedPackage,
        )
    }

    private fun searchArgs(workspaceId: String, target: String, query: String): JsonObject {
        return JsonObject().apply {
            addProperty("workspaceId", workspaceId)
            addProperty("editSessionId", "")
            addProperty("target", target)
            addProperty("query", query)
            addProperty("queryType", "literal")
            addProperty("caseSensitive", false)
            addProperty("matchMode", "contains")
            addProperty("prefix", "")
            addProperty("includeMatchOffsets", false)
            addProperty("limit", 50)
            addProperty("snippetMaxChars", 240)
        }
    }

    private fun pseudocodeArgs(workspaceId: String, locator: String, style: String): JsonObject {
        return JsonObject().apply {
            addProperty("workspaceId", workspaceId)
            addProperty("editSessionId", "")
            addProperty("locator", locator)
            addProperty("limit", 500)
            addProperty("maxChars", 49152)
            addProperty("startLine", 0)
            addProperty("startColumn", 0)
            addProperty("style", style)
        }
    }

    private fun stepToolName(n: Int): String = when (n) {
        3 -> TOOL_LIST_ENTRIES
        4 -> TOOL_READ_SIGNATURE
        5, 6, 7, 18 -> TOOL_SEARCH
        8, 20 -> TOOL_DEX_LIST_CLASSES
        9 -> TOOL_CONTINUE
        10, 14 -> TOOL_DEX_READ_OUTLINE
        11, 12, 15 -> TOOL_DEX_READ_PSEUDOCODE
        13, 16 -> TOOL_DEX_XREF
        17 -> TOOL_RESOURCE_LIST
        19 -> TOOL_READ_TEXT
        else -> TOOL_DEX_LIST_CLASSES
    }

    private data class OpenedWorkspace(
        val result: JsonObject,
        val path: String,
        val pkg: String,
    )

    private fun openWorkspace(
        firstPath: String,
        firstPkg: String,
        known: List<Pair<String, String>>,
        stepLines: MutableList<String>,
        onStep: (String) -> Unit,
    ): OpenedWorkspace? {
        val tried = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue += firstPath to firstPkg
        for (c in known) {
            if (c.first != firstPath) queue += c
        }

        fun tryOpen(path: String, pkg: String): Pair<JsonObject?, String?> {
            onStep("خطوة ٢/٢٠: $TOOL_WORKSPACE_OPEN ($pkg)")
            return try {
                val result = runner.call(TOOL_WORKSPACE_OPEN, JsonObject().apply {
                    addProperty("path", path)
                    addProperty("temporary", true)
                }, step = 2)
                result to null
            } catch (e: McpException) {
                null to (e.message ?: e.toString())
            }
        }

        var lastError: String? = null
        while (queue.isNotEmpty()) {
            val (path, pkg) = queue.removeFirst()
            if (!tried.add(path)) continue
            val (result, error) = tryOpen(path, pkg)
            if (result != null) {
                val note = if (pkg != firstPkg) {
                    " (بديل بعد حظر $firstPkg)"
                } else {
                    ""
                }
                stepLines += "  2 — $TOOL_WORKSPACE_OPEN: ناجحة ✓$note — الهدف: $pkg"
                return OpenedWorkspace(result, path, pkg)
            }
            lastError = error
            if (error != null && error.contains("APK_OPEN_BLOCKED")) {
                stepLines += "  2 — $TOOL_WORKSPACE_OPEN: محظور على $pkg — البحث عن تطبيق مستخدم آخر…"
                if (queue.isEmpty()) {
                    val extras = listUserApps(exclude = tried)
                    queue.addAll(extras)
                }
                continue
            }
            stepLines += "  2 — $TOOL_WORKSPACE_OPEN: فاشلة ✗ — ${error ?: "فشل غير معروف"}"
            return null
        }
        stepLines += "  2 — $TOOL_WORKSPACE_OPEN: فاشلة ✗ — ${lastError ?: "لا تطبيق بديل قابل للفتح"}"
        return null
    }

    private fun listUserApps(exclude: Set<String>): List<Pair<String, String>> {
        return try {
            val result = runner.call(TOOL_LIST_INSTALLED, JsonObject().apply {
                addProperty("query", "com")
                addProperty("appType", "user")
                addProperty("limit", 50)
            }, step = 2)
            installedApps(result).filter { it.first !in exclude }
        } catch (_: McpException) {
            emptyList()
        }
    }

    private fun installedApps(result: JsonObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (item in McpResults.itemsOf(McpResults.dataOf(result))) {
            val path = McpResults.firstString(item, "path") ?: continue
            val pkg = McpResults.firstString(item, "packageName", "package", "name") ?: ""
            out += path to pkg.ifBlank { path.substringAfterLast('/') }
        }
        return out
    }

    /** يبحث في نتيجة سرد التطبيقات عن الحزمة المطلوبة ويعيد (المسار، الحزمة). */
    private fun findInstalled(result: JsonObject, want: String): Pair<String, String>? {
        for (item in McpResults.itemsOf(McpResults.dataOf(result))) {
            val path = McpResults.firstString(item, "path") ?: continue
            val pkg = McpResults.firstString(item, "packageName", "package", "name") ?: ""
            if (pkg == want || path.contains(want)) return path to (pkg.ifBlank { want })
        }
        return null
    }

    /** أول تطبيق يحمل «MT» في اسمه أو حزمته أو مساره. */
    private fun findMtApp(result: JsonObject): Pair<String, String>? {
        for (item in McpResults.itemsOf(McpResults.dataOf(result))) {
            val path = McpResults.firstString(item, "path") ?: continue
            val pkg = McpResults.firstString(item, "packageName", "package", "name") ?: ""
            val name = McpResults.firstString(item, "name") ?: ""
            if (pkg.contains("MT", ignoreCase = true) ||
                name.contains("MT", ignoreCase = true) ||
                path.contains("MT", ignoreCase = true)
            ) {
                return path to (pkg.ifBlank { name.ifBlank { "bin.mt.plus" } })
            }
        }
        return null
    }

    /** محددات الكلاسات من نتيجة السرد: المحدد الصريح أو المشتق من الاسم. */
    private fun classLocatorsOf(data: JsonObject): List<String> {
        val out = mutableListOf<String>()
        for (item in McpResults.itemsOf(data)) {
            val locator = McpResults.firstString(item, "locator")
            if (locator != null) {
                out += locator
                continue
            }
            val name = McpResults.firstString(item, "name", "className", "descriptor", "class")
            if (name != null) {
                val slash = name.removePrefix("L").replace('.', '/')
                out += "dex_class:$slash;"
            }
        }
        return out
    }

    /** أول دالة من مخطط الكلاس، بمحددها المشتق. */
    private fun firstMethodLocator(outlineData: JsonObject, classLocator: String): String? {
        if (!outlineData.has("methods") || !outlineData.get("methods").isJsonArray) return null
        val classDesc = classLocator.removePrefix("dex_class:")
        for (el in outlineData.getAsJsonArray("methods")) {
            if (!el.isJsonObject) continue
            val entry = el.asJsonObject
            val locator = McpResults.firstString(entry, "locator")
            if (locator != null && locator.startsWith("dex_method:")) return locator
            val name = McpResults.firstString(entry, "name") ?: continue
            val descriptor = McpResults.firstString(entry, "descriptor") ?: continue
            return "dex_method:$classDesc->$name$descriptor"
        }
        return null
    }

    /** بادئة حزمة الكلاس: من dex_class:Lcom/foo/Bar; أو dex_class:com/foo/Bar; إلى Lcom/foo/ . */
    private fun packagePrefixOf(classLocator: String): String {
        val desc = classLocator.removePrefix("dex_class:")
        val body = desc.removePrefix("L").removeSuffix(";")
        val pkg = body.substringBeforeLast("/", missingDelimiterValue = "")
        return if (pkg.isEmpty()) "" else "L$pkg/"
    }
}
