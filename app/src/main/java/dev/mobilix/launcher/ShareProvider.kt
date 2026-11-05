package dev.mobilix.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Отдаёт другим приложениям (Telegram и т.п.) только файлы из cache/share: так сборку можно отправить в один клик. */
class ShareProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        val dir = File(context!!.cacheDir, "share").canonicalFile
        val f = File(dir, uri.lastPathSegment ?: "").canonicalFile
        if (f.parentFile != dir || !f.isFile) throw FileNotFoundException()
        return f
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }
    override fun getType(uri: Uri) = "application/zip"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
