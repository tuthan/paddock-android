package io.github.tuthan.paddock.files

import java.util.Locale

/**
 * What a fetched file is allowed to be handed to another app as (SEC-6, X5). Decided by the file's extension, since that is all a file from a machine
 * offers (the machine's claim is not a type, and Paddock does not sniff a file it will not open itself). **Open** goes only to images, PDFs and plain
 * text formats, which an app displays without running anything in them; everything else gets Share and Save only. SVG and HTML are not images and not
 * text here: both carry script a viewer may run. An APK is never opened by Paddock: it is Share and Save, and the user installs from Files, where the
 * system's installer asks (Paddock holds no install permission).
 */
object OpenableTypes {
    const val APK = "application/vnd.android.package-archive"
    const val OTHER = "application/octet-stream"

    private val IMAGES = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")
    private val TEXT = setOf("txt", "md", "markdown", "log", "json", "jsonl", "ndjson", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "diff", "patch", "csv", "tsv")

    sealed interface Kind {
        /** Open, Share and Save: [mime] is the type the receiving app is told. */
        data class Openable(val mime: String) : Kind

        /** Share and Save only, never Open; the sheet also shows [ApkInfo] and says where to install from. */
        data object Apk : Kind

        /** Share and Save only, as [mime] (a best guess from the extension, `application/octet-stream` when there is none). */
        data class ShareOnly(val mime: String) : Kind
    }

    /** The extension of [name] in lower case without its dot, or "" when it has none (a leading dot alone is not an extension). */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun kindOf(name: String): Kind {
        val ext = extensionOf(name)
        return when {
            ext == "apk" -> Kind.Apk
            ext in IMAGES -> Kind.Openable(IMAGES.getValue(ext))
            ext == "pdf" -> Kind.Openable("application/pdf")
            ext in TEXT -> Kind.Openable(if (ext == "csv") "text/csv" else "text/plain")
            else -> Kind.ShareOnly(shareMime(ext))
        }
    }

    /** The type Save's `CreateDocument` and Share are given for [ext]: a few common ones, else octet-stream. Never a type that makes a viewer run the file. */
    private fun shareMime(ext: String): String = when (ext) {
        "zip" -> "application/zip"
        "gz", "tgz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> OTHER
    }
}
