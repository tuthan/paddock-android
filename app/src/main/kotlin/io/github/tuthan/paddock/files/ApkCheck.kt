package io.github.tuthan.paddock.files

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import io.github.tuthan.paddock.output.SafeText
import java.io.File
import java.security.MessageDigest

/**
 * What an APK says about itself, read from the file and never from the machine's word for it (X5). [signers] are the SHA-256 of each signing
 * certificate in lower-case hex; empty means the package carries no signature the platform could read. [packageName] and [versionName] are the
 * file's own text: they are shown only after [SafeText.clean] and at most [SHOWN] characters.
 */
data class ApkInfo(val packageName: String, val versionName: String?, val versionCode: Long, val signers: List<String>) {
    companion object { const val SHOWN = 120 }
}

/**
 * The sentences under an APK on the Get sheet (X5, AC-15.29). Paddock never installs it (no install permission on either flavor): the sheet tells the
 * facts a person needs before taking it to Files, and says so when it is Paddock's own package signed by another key, since the system will refuse to
 * replace it and installing it means uninstalling Paddock first, which forgets this phone's key.
 */
object ApkNotes {
    const val UNREADABLE = "Paddock could not read this as an app package."
    const val UNSIGNED = "This package is not signed, so the phone's installer will refuse it."
    const val WHERE = "Paddock does not install apps. Share or Save it, then open it from Files, where the system's installer asks."
    const val OTHER_SIGNER = "This is Paddock's own package, signed by another key. Installing this means uninstalling Paddock first, which forgets this phone's key."
    const val SAME_SIGNER = "This is Paddock's own package with the signer of the Paddock installed here."

    /** The lines under the file's name, in the order they are shown. [own] is the signers of the Paddock installed here (empty when it could not be read). */
    fun lines(info: ApkInfo?, ownPackage: String, own: List<String>): List<String> {
        if (info == null) return listOf(UNREADABLE, WHERE)
        val name = SafeText.clean(info.packageName).take(ApkInfo.SHOWN)
        val version = info.versionName?.let { SafeText.clean(it).take(ApkInfo.SHOWN) }
        return buildList {
            add("Package $name")
            add("Version ${listOfNotNull(version, "(${info.versionCode})").joinToString(" ")}")
            if (info.signers.isEmpty()) add(UNSIGNED) else info.signers.forEach { add("Signer SHA-256 $it") }
            if (info.packageName == ownPackage && info.signers.isNotEmpty() && own.isNotEmpty()) {
                add(if (info.signers.sorted() == own.sorted()) SAME_SIGNER else OTHER_SIGNER)
            }
            add(WHERE)
        }
    }
}

/**
 * The platform side of [ApkNotes]: `getPackageArchiveInfo` with the signing flag the API level has (`GET_SIGNING_CERTIFICATES` from 28, `GET_SIGNATURES`
 * below), and `getPackageInfo` of Paddock's own package with the same. The file is only read by the platform's parser; nothing in it runs.
 */
object ApkCheck {
    @Suppress("DEPRECATION")
    private val FLAGS = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /**
     * What [file] says about itself. The platform refuses to parse a package it finds no signature on when it is asked for the signers (spike S6: `null` on API 26, 36
     * and 37), so a package that parses without them is the unsigned one: the facts come back with no signer, and the sheet says it is unsigned. A file that parses
     * neither way is not a package at all.
     */
    fun read(context: Context, file: File): ApkInfo? = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        (pm.getPackageArchiveInfo(file.path, FLAGS) ?: pm.getPackageArchiveInfo(file.path, 0))?.let(::infoOf)
    } catch (_: RuntimeException) { null }

    /** The signers of the Paddock installed here. */
    fun ownSigners(context: Context): List<String> = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, FLAGS).let(::signersOf)
    } catch (_: RuntimeException) { emptyList() } catch (_: PackageManager.NameNotFoundException) { emptyList() }

    private fun infoOf(p: PackageInfo): ApkInfo? {
        val name = p.packageName ?: return null
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        return ApkInfo(name, p.versionName, code, signersOf(p))
    }

    private fun signersOf(p: PackageInfo): List<String> {
        @Suppress("DEPRECATION")
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.distinct().sorted()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
