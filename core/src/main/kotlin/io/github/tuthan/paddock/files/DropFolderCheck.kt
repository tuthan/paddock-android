package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.output.SafeText

/**
 * What a machine says about a drop folder before it is saved and when its page shows what is in it (Phase 15, slice 11, X10): the value the person typed
 * (`~/Paddock`) is resolved on the machine, which judges it (not home itself, not hidden, not a link, inside home) and answers its real path; then that path
 * is listed, which tells whether the folder is there yet and what is in it. Nothing is created and nothing is written by either question.
 */
object DropFolderCheck {
    sealed interface Result {
        /** [exists] is false for a folder that is not there yet: the first drop makes it, owner-only. [files] are the newest first, at most [FileTransfers.MAX_LIST]. */
        data class Ok(val value: String, val real: String, val exists: Boolean, val files: List<RemoteFile>) : Result
        data class Problem(val sentence: String) : Result
    }

    suspend fun check(transfers: FileTransfers, value: String): Result {
        val real = when (val r = transfers.resolveDrop(value)) {
            is TransferReply.Ok -> r.value
            is TransferReply.Refused -> return Result.Problem(r.refusal.sentence)
            is TransferReply.Failed -> return Result.Problem(couldNotAsk(r.message))
        }
        return when (val l = transfers.list(real, ListWhich.Drop)) {
            is TransferReply.Ok -> Result.Ok(value, real, exists = true, files = l.value)
            is TransferReply.Refused -> if (l.refusal == TransferRefusal.NotFound) Result.Ok(value, real, exists = false, files = emptyList()) else Result.Problem(l.refusal.sentence)
            is TransferReply.Failed -> Result.Problem(couldNotAsk(l.message))
        }
    }

    private fun couldNotAsk(why: String) = "Could not ask the machine: ${SafeText.clean(why).take(MAX_WHY)}."

    /** The sentence under the field: the value, the path it is, and whether it exists. Both go through [SafeText]: the path is the machine's word. */
    fun sentence(ok: Result.Ok): String {
        val value = SafeText.clean(ok.value)
        val real = SafeText.clean(ok.real)
        return if (ok.exists) "$value is $real on this machine." else "$value is $real on this machine and will be created when the first file arrives."
    }

    private const val MAX_WHY = 120
}
