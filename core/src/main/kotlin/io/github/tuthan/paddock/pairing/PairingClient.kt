package io.github.tuthan.paddock.pairing

import io.github.tuthan.paddock.ports.Sockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress

/** What one exchange with the desktop came to. [NoReply] is the lost-reply case: the line may or may not have arrived. */
sealed interface ClientResult {
    data class Reply(val reply: PairingReply) : ClientResult
    /** Could not connect: nothing was sent, so nothing can have arrived. */
    data class Unreachable(val detail: String?) : ClientResult
    /** Connected and sent, but no usable answer came back. */
    data object NoReply : ClientResult
}

/** The two requests of [PairingWire], each one short TCP exchange with a hard timeout. */
open class PairingClient(private val sockets: Sockets, private val connectTimeoutMillis: Int = 3_000, private val replyTimeoutMillis: Int = 3_000) {
    open suspend fun key(host: String, port: Int, sid: String, keyLine: String): ClientResult = exchange(host, port, PairingWire.keyRequest(sid, keyLine))

    open suspend fun status(host: String, port: Int, sid: String): ClientResult = exchange(host, port, PairingWire.statusRequest(sid))

    private suspend fun exchange(host: String, port: Int, request: ByteArray): ClientResult = withContext(Dispatchers.IO) {
        val address = try { InetAddress.getByName(host) } catch (e: IOException) { return@withContext ClientResult.Unreachable("unknown host") }
        try {
            sockets.tcp().use { c ->
                try { c.connect(address, port, connectTimeoutMillis) } catch (e: IOException) { return@withContext ClientResult.Unreachable(e.javaClass.simpleName) }
                try { c.write(request) } catch (e: IOException) { return@withContext ClientResult.NoReply }
                val reply = PairingWire.decodeReply(c.readLine(64, replyTimeoutMillis))
                if (reply == PairingReply.Malformed) ClientResult.NoReply else ClientResult.Reply(reply)
            }
        } catch (e: SecurityException) {
            ClientResult.Unreachable("not allowed")
        }
    }
}
