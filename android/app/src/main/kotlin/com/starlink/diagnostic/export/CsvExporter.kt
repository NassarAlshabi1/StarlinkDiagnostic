package com.starlink.diagnostic.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * V2.2 — shares the CSV files the Python layer writes into the app-private
 * exports dir via the system share sheet. The file itself is produced by the
 * `export_csv` bridge op (content is generated inside Python, next to the
 * StarlinkDiagnostic.db it reads); this helper only offers it to the user.
 * No storage permission needed, works fully offline.
 */
object CsvExporter {

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Export CSV").addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }
}
