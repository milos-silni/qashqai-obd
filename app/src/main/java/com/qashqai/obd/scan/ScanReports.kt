package com.qashqai.obd.scan

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Scan/capture reports as text files in Android/data/com.qashqai.obd/files/scans (reachable over USB). */
class ScanReports(private val context: Context) {
    val dir: File = (context.getExternalFilesDir("scans") ?: File(context.filesDir, "scans")).apply { mkdirs() }

    fun save(kind: String, text: String): File {
        val f = File(dir, "${kind}_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        f.writeText(text)
        return f
    }

    fun list(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray()).sortedByDescending { it.lastModified() }

    fun shareIntent(f: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            clipData = ClipData.newRawUri(f.name, uri)   // lets the share sheet preview it too
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send ${f.name}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
