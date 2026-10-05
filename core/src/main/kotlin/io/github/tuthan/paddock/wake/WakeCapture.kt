package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import kotlin.time.Duration.Companion.seconds

/**
 * Reads, over the SSH connection already open, which network interface this phone reached the machine on and whether that interface
 * can be woken. Read-only: the command writes nothing, runs nothing as root and changes no setting; what would have to change is
 * shown to the user as commands ([WakeCommands]) and never run. The command is one constant with no interpolation, so nothing the
 * machine or the phone says can reach a shell.
 */
object WakeCapture {
    /**
     * One line of POSIX `sh`, because the session refuses an argument with a line break, a single quote or a backslash (so it can be
     * quoted for any login shell): double quotes only, fields read with positional parameters instead of `sed`, `set -f` so an
     * unquoted `*` in `iw`'s output is never a glob. `~` in the source below stands for `$` and is replaced before use.
     *
     * Extends `PATH` first: a non-login `sh` over SSH often lacks `/usr/sbin` and `/sbin`, where `ip`, `iw` and `ethtool` live on
     * Debian. Prints `client=`, `gateway=` and then one `dev=… end` block for the interface of the route to the phone and, when it is
     * another, for the default route's. Read-only: nothing is written, started or run with more rights than the SSH user has.
     */
    val COMMAND: String = listOf(
        "PATH=\"~PATH:/usr/sbin:/sbin\"",
        "export PATH",
        "set -f",
        "set -- ~SSH_CONNECTION",
        "c=~1",
        "echo \"client=~c\"",
        "set -- ~(ip -o route get \"~c\" 2>/dev/null)",
        "dev=",
        "while [ ~# -gt 0 ]; do if [ \"~1\" = dev ]; then dev=~2; fi; shift; done",
        "set -- ~(ip -o -4 route show default 2>/dev/null | head -n 1)",
        "defdev=",
        "gw=",
        "while [ ~# -gt 0 ]; do if [ \"~1\" = dev ]; then defdev=~2; fi; if [ \"~1\" = via ]; then gw=~2; fi; shift; done",
        "echo \"gateway=~gw\"",
        "emit() { d=~1; [ -n \"~d\" ] || return 0; echo \"dev=~d\"; " +
            "if [ -e \"/sys/class/net/~d/device\" ]; then echo physical=yes; else echo physical=no; fi; " +
            "echo \"mac=~(cat \"/sys/class/net/~d/address\" 2>/dev/null)\"; " +
            "set -- ~(ip -o -4 addr show dev \"~d\" 2>/dev/null); a=; " +
            "while [ ~# -gt 0 ]; do if [ \"~1\" = inet ]; then a=~2; break; fi; shift; done; " +
            "echo \"addr=~{a%/*}\"; " +
            "echo \"wakeup=~(cat \"/sys/class/net/~d/device/power/wakeup\" 2>/dev/null)\"; " +
            "p=~(cat \"/sys/class/net/~d/phy80211/name\" 2>/dev/null); echo \"phy=~p\"; " +
            "if [ -n \"~p\" ]; then " +
            "if command -v iw >/dev/null 2>&1; then x=~(iw phy \"~p\" wowlan show 2>/dev/null); echo \"wowlan=~(echo ~x)\"; else echo wowlan=missing; fi; " +
            "else " +
            "if command -v ethtool >/dev/null 2>&1; then set -- ~(ethtool \"~d\" 2>/dev/null | grep -E \"^[[:space:]]+Wake-on:\" | head -n 1); echo \"wol=~2\"; else echo wol=missing; fi; " +
            "fi; echo end; }",
        "emit \"~dev\"",
        "if [ \"~defdev\" != \"~dev\" ]; then emit \"~defdev\"; fi",
    ).joinToString("; ").replace('~', '$')

    /** Runs [COMMAND] and reads its answer. Never throws for a machine that answers badly: that is "not available", with the reason. */
    suspend fun capture(session: SshSession, clock: Clock, previousRelay: WakeRelay?): WakeTarget {
        val r = try {
            session.exec(listOf("sh", "-c", COMMAND), limits = ExecLimits(stdoutMax = 16 shl 10, stderrMax = 4 shl 10, deadline = 10.seconds))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return WakeTarget.unavailable("The machine could not be asked about its network interface.", clock.nowMillis(), previousRelay)
        }
        return WakeCaptureParser.parse(String(r.stdout, Charsets.UTF_8), clock.nowMillis(), previousRelay)
    }
}

object WakeCaptureParser {
    private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    private val IFACE = Regex("[A-Za-z0-9._-]{1,15}")
    private val PHY = Regex("[a-z0-9]{1,16}")

    private class Block(val dev: String, val fields: Map<String, String>)

    fun parse(output: String, nowMillis: Long, previousRelay: WakeRelay?): WakeTarget {
        var gateway: String? = null
        val blocks = ArrayList<Block>()
        var dev: String? = null
        var fields = LinkedHashMap<String, String>()
        for (raw in output.lineSequence().take(200)) {
            val line = raw.trimEnd('\r')
            val eq = line.indexOf('=')
            if (line == "end") { dev?.let { blocks += Block(it, fields) }; dev = null; fields = LinkedHashMap(); continue }
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1).trim().take(300)
            when {
                key == "gateway" && dev == null -> gateway = value.takeIf { Ipv4Subnet.literal(it) != null }
                key == "dev" -> { dev = value.takeIf { IFACE.matches(it) }; fields = LinkedHashMap() }
                dev != null -> fields[key] = value
            }
        }
        if (blocks.isEmpty()) {
            return WakeTarget.unavailable("The machine did not say which network interface this phone reached it on.", nowMillis, previousRelay, gateway)
        }
        val chosen = blocks.firstOrNull { it.fields["physical"] == "yes" && MAC.matches(it.fields["mac"].orEmpty()) && it.fields["mac"] != "00:00:00:00:00:00" }
            ?: return WakeTarget.unavailable(
                if (blocks.any { it.fields["physical"] == "yes" }) "The network interface has no usable hardware address."
                else "This phone reached the machine over a VPN or a virtual network, and the machine has no physical interface on that route.",
                nowMillis, previousRelay, gateway,
            )
        val f = chosen.fields
        val wifi = f["phy"].orEmpty().isNotEmpty() && PHY.matches(f["phy"].orEmpty())
        return WakeTarget(
            available = true, mac = f.getValue("mac"), iface = chosen.dev,
            sourceAddress = f["addr"]?.takeIf { Ipv4Subnet.literal(it) != null },
            capturedAtMillis = nowMillis, gateway = gateway, relay = previousRelay,
            readiness = WakeReadiness(
                wakeup = f["wakeup"]?.takeIf { it == "enabled" || it == "disabled" },
                wowlan = if (wifi) wowlanWords(f["wowlan"]) else null,
                ethtool = if (wifi) null else ethtoolWords(f["wol"]),
                wifi = wifi, phy = if (wifi) f["phy"] else null,
            ),
        )
    }

    private fun wowlanWords(raw: String?): String? = when {
        raw == null -> null
        raw == "missing" -> "unknown: iw is not installed"
        raw.isEmpty() -> "unknown: WoWLAN could not be read"
        "is disabled" in raw -> "disabled"
        "is enabled" in raw -> if ("magic packet" in raw) "enabled: magic packet" else "enabled"
        else -> "unknown: " + raw.take(80)
    }

    private fun ethtoolWords(raw: String?): String? = when {
        raw == null -> null
        raw == "missing" -> "unknown: ethtool is not installed"
        raw.isEmpty() -> "unknown: Wake-on could not be read"
        Regex("[a-z]{1,10}").matches(raw) -> raw
        else -> "unknown: " + raw.take(40)
    }
}
