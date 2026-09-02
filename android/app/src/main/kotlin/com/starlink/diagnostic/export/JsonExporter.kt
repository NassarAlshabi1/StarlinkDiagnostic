package com.starlink.diagnostic.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Saves raw JSON exports into the app-private exports dir and offers them
 * via the system share sheet — no storage permission needed, works offline.
 */
object JsonExporter {

    fun save(context: Context, name: String, json: String): File {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Export JSON").addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }
}
