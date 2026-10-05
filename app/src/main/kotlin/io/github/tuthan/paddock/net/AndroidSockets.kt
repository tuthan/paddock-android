package io.github.tuthan.paddock.net

import io.github.tuthan.paddock.ports.JavaSockets
import io.github.tuthan.paddock.ports.Sockets

/**
 * [Sockets] for the app: `java.net` sockets pinned to the Android network of the path they are for. A UDP sender is bound to its path's
 * network, so a broadcast leaves through the Wi-Fi even while a VPN holds the default route. TCP connections follow [tcpPathId] when it
 * is set (the finder pins its probe to the network it is scanning) and the platform's routing otherwise.
 */
class AndroidSockets(private val paths: AndroidLanPaths) : Sockets by JavaSockets(
    bindTcp = { },
    bindUdp = { _, _ -> },
) {
    @Volatile var tcpPathId: String? = null

    private val java = JavaSockets(
        bindTcp = { socket -> tcpPathId?.let { paths.network(it) }?.bindSocket(socket) },
        bindUdp = { socket, path -> path?.let { paths.network(it.id) }?.bindSocket(socket) },
    )

    override fun tcp() = java.tcp()

    override fun udp(path: io.github.tuthan.paddock.ports.LanPath?) = java.udp(path)
}
