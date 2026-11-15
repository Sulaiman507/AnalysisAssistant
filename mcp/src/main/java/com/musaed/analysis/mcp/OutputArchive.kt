package com.musaed.analysis.mcp

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object OutputArchive {
    fun preserve(dir: File, fileName: String) {
        val file = File(dir, fileName)
        if (!file.exists() || file.length() == 0L) return
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            .format(Date(file.lastModified()))
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var dest = File(dir, "${base}_$stamp$ext")
        var n = 2
        while (dest.exists()) {
            dest = File(dir, "${base}_$stamp-$n$ext")
            n++
        }
        file.copyTo(dest)
    }
}
