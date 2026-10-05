package io.github.tuthan.paddock.ports

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** One outgoing TCP connection, used for a single short exchange. Every call is bounded by the timeouts it is given. */
interface TcpConnection : AutoCloseable {
    /** Connects within [timeoutMillis]; throws [IOException] on a refusal, a timeout or no route. */
    fun connect(address: InetAddress, port: Int, timeoutMillis: Int)

    fun write(bytes: ByteArray)

    /**
     * Reads up to the first `\n` (a preceding `\r` is dropped) or [max] bytes, waiting at most [timeoutMillis] in total. The text
     * read, or null when nothing at all arrived before the peer closed or the time ran out.
     */
    fun readLine(max: Int, timeoutMillis: Int): String?
}

/** A UDP sender bound to one network. [broadcast] is set for a LAN broadcast and clear for a unicast to a relay. */
interface UdpSender : AutoCloseable {
    fun send(payload: ByteArray, address: InetAddress, port: Int, broadcast: Boolean)
}

interface Sockets {
    fun tcp(): TcpConnection

    /** A sender whose packets leave through [path] when it is given. */
    fun udp(path: LanPath?): UdpSender
}

/**
 * [Sockets] over `java.net`. The app passes binders that pin a socket to one Android network; with none, the platform's own
 * routing is used. Nothing here knows Android, so the probe, the pairing client and the wake sender are tested against
 * loopback sockets.
 */
class JavaSockets(
    private val bindTcp: (Socket) -> Unit = {},
    private val bindUdp: (DatagramSocket, LanPath?) -> Unit = { _, _ -> },
) : Sockets {
    override fun tcp(): TcpConnection = JavaTcp(Socket().also(bindTcp))

    override fun udp(path: LanPath?): UdpSender {
        val socket = DatagramSocket(null)
        try {
            bindUdp(socket, path)
            if (!socket.isBound) socket.bind(InetSocketAddress(0))
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
        return JavaUdp(socket)
    }

    private class JavaTcp(private val socket: Socket) : TcpConnection {
        override fun connect(address: InetAddress, port: Int, timeoutMillis: Int) {
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
        }

        override fun write(bytes: ByteArray) {
            socket.getOutputStream().apply { write(bytes); flush() }
        }

        override fun readLine(max: Int, timeoutMillis: Int): String? {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            val input = socket.getInputStream()
            // ByteArrayOutputStream.toString(Charset) is API 33: build the String from the bytes, which every API level has.
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < max) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) break
                socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val b = try { input.read() } catch (_: SocketTimeoutException) { break } catch (_: IOException) { break }
                if (b < 0) break
                if (b == '\n'.code) return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
                out.write(b)
            }
            return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
        }

        override fun close() = socket.close()
    }

    private class JavaUdp(private val socket: DatagramSocket) : UdpSender {
        override fun send(payload: ByteArray, address: InetAddress, port: Int, broadcast: Boolean) {
            socket.broadcast = broadcast
            socket.send(DatagramPacket(payload, payload.size, address, port))
        }

        override fun close() = socket.close()
    }
}
