package com.exfiles.app

import android.webkit.MimeTypeMap
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*

object Fs {
    val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val VID = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "ts", "m4v", "wmv", "mpg", "mpeg")
    val AUD = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "amr", "wma", "mid")
    val TXT = setOf("txt", "log", "json", "xml", "md", "html", "htm", "css", "js", "kt", "java", "gradle",
        "properties", "csv", "ini", "conf", "sh", "yml", "yaml", "smali", "cfg")
    val DOC = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "odt", "ods", "rtf", "csv", "epub")
    val ZIP = setOf("zip", "jar")
    val APKS = setOf("apk", "xapk", "apks", "apkm")

    fun ext(f: File): String = f.extension.lowercase(Locale.ROOT)

    // 0 folder, 1 image, 2 video, 3 audio, 4 apk, 5 zip, 6 text, 7 other
    fun kind(f: File): Int {
        if (f.isDirectory) return 0
        val e = ext(f)
        return when {
            e in IMG -> 1
            e in VID -> 2
            e in AUD -> 3
            e in APKS -> 4
            e in ZIP -> 5
            e in TXT -> 6
            else -> 7
        }
    }

    fun mime(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f)) ?: "*/*"

    fun date(t: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(t))

    fun fmt(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun size(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { size(it) } ?: 0L) else f.length()

    fun unique(f: File): File {
        if (!f.exists()) return f
        val n = f.nameWithoutExtension
        val e = if (f.extension.isEmpty()) "" else "." + f.extension
        var i = 1
        var c: File
        do { c = File(f.parentFile, "$n ($i)$e"); i++ } while (c.exists())
        return c
    }

    fun copy(s: File, d: File) {
        if (s.isDirectory) {
            d.mkdirs()
            s.listFiles()?.forEach { copy(it, File(d, it.name)) }
        } else {
            s.inputStream().use { i -> d.outputStream().use { o -> i.copyTo(o) } }
        }
    }

    fun delete(f: File) { f.deleteRecursively() }

    fun move(s: File, d: File) {
        if (!s.renameTo(d)) { copy(s, d); delete(s) }
    }

    fun zip(files: List<File>, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { z ->
            fun add(f: File, base: String) {
                val n = base + f.name
                if (f.isDirectory) {
                    z.putNextEntry(ZipEntry("$n/")); z.closeEntry()
                    f.listFiles()?.forEach { add(it, "$n/") }
                } else {
                    z.putNextEntry(ZipEntry(n))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            files.forEach { add(it, "") }
        }
    }

    fun unzip(zf: File, dir: File) {
        dir.mkdirs()
        val base = dir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zf))).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val o = File(dir, e.name)
                if (o.canonicalPath.startsWith(base)) {
                    if (e.isDirectory) o.mkdirs() else {
                        o.parentFile?.mkdirs()
                        o.outputStream().use { z.copyTo(it) }
                    }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
    }

    fun walk(d: File, out: MutableList<File>, depth: Int, pred: (File) -> Boolean) {
        if (depth > 12) return
        val fs = d.listFiles() ?: return
        for (f in fs) {
            if (out.size >= 5000) return
            if (f.isDirectory) {
                if (f.name.startsWith(".") || f.name == "Android") continue
                if (pred(f)) out.add(f)
                walk(f, out, depth + 1, pred)
            } else if (pred(f)) out.add(f)
        }
    }
}
