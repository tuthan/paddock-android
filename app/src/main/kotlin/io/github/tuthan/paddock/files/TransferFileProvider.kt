package io.github.tuthan.paddock.files

import androidx.core.content.FileProvider

/**
 * The one content provider that hands a file to another app: the camera writes a capture through it, and Open or Share of a fetched file reads through it
 * (Phase 15, SEC-5). It is a subclass of the platform-support provider, as that provider's own guidance asks, so the manifest names Paddock's class, says
 * outright that it is not exported and grants nothing but the URI a caller is given (`grantUriPermissions`). The paths it can name are in
 * `res/xml/transfer_paths.xml`: two folders in the cache, with random names.
 */
class TransferFileProvider : FileProvider() {
    companion object {
        /** The authority the manifest declares: `${applicationId}.files`. */
        fun authority(packageName: String): String = "$packageName.files"
    }
}
