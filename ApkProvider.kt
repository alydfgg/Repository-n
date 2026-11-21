package com.exfiles.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

// Serves files from external storage as content:// URIs so the system installer can read APKs.
class ApkProvider : ContentProvider() {
    private fun file(u: Uri): File {
        val f = File(u.path ?: throw FileNotFoundException())
        val base = Environment.getExternalStorageDirectory().canonicalPath
        val c = f.canonicalPath
        if (!(c == base || c.startsWith(base + "/"))) throw SecurityException("outside storage")
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri)
        val cols: Array<String> = if (projection == null) arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            else Array(projection.size) { projection[it] }
        val c = MatrixCursor(cols)
        c.addRow(Array<Any?>(cols.size) { i -> if (cols[i] == OpenableColumns.SIZE) f.length() else f.name })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
