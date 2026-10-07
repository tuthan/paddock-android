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
    private val SHARED = listOf(
        "PATH=\"~PATH:/usr/sbin:/sbin\"",
        "export PATH",
        "set -f",
        "set -- ~SSH_CONNECTION",
        "c=~1",
        "echo \"client=~c\"",
    )

    /** `ip`, `/sys/class/net`, `iw` and `ethtool`: what a Linux host has. */
    private val LINUX = listOf(
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
    )

    /**
     * `route`, `ifconfig` and `pmset`: what macOS has. The interface of a route is the word after `interface:`, the gateway the word
     * after `gateway:`; the hardware address is the word after `ether` and the address the word after the first `inet`. An interface
     * is physical when it is named `en` and a number (a tunnel is `utun`, a bridge `bridge`, Apple's peer-to-peer links `awdl` and
     * `llw`). Wake for network access is the `womp` line of `pmset -g`: 1 on, 0 off, nothing printed when it cannot be read.
     */
    private val MACOS = listOf(
        "set -- ~(route -n get \"~c\" 2>/dev/null)",
        "dev=",
        "while [ ~# -gt 0 ]; do if [ \"~1\" = interface: ]; then dev=~2; fi; shift; done",
        "set -- ~(route -n get default 2>/dev/null)",
        "defdev=",
        "gw=",
        "while [ ~# -gt 0 ]; do if [ \"~1\" = interface: ]; then defdev=~2; fi; if [ \"~1\" = gateway: ]; then gw=~2; fi; shift; done",
        "echo \"gateway=~gw\"",
        "emit() { d=~1; [ -n \"~d\" ] || return 0; echo \"dev=~d\"; " +
            "case \"~d\" in en[0-9]|en[0-9][0-9]) echo physical=yes;; *) echo physical=no;; esac; " +
            "set -- ~(ifconfig \"~d\" 2>/dev/null); m=; a=; " +
            "while [ ~# -gt 0 ]; do if [ \"~1\" = ether ] && [ -z \"~m\" ]; then m=~2; fi; if [ \"~1\" = inet ] && [ -z \"~a\" ]; then a=~2; fi; shift; done; " +
            "echo \"mac=~m\"; echo \"addr=~a\"; " +
            "set -- ~(pmset -g 2>/dev/null | grep -E \"^[[:space:]]+womp[[:space:]]\" | head -n 1); echo \"womp=~2\"; echo end; }",
        "emit \"~dev\"",
        "if [ \"~defdev\" != \"~dev\" ]; then emit \"~defdev\"; fi",
    )

    /**
     * One line of POSIX `sh`, because the session refuses an argument with a line break, a single quote or a backslash (so it can be
     * quoted for any login shell): double quotes only, fields read with positional parameters instead of `sed`, `set -f` so an
     * unquoted `*` in `iw`'s output is never a glob. `~` in the source below stands for `$` and is replaced before use.
     *
     * Extends `PATH` first: a non-login `sh` over SSH often lacks `/usr/sbin` and `/sbin`, where `ip`, `iw` and `ethtool` live on
     * Debian. Prints `client=`, `gateway=` and then one `dev=… end` block for the interface of the route to the phone and, when it is
     * another, for the default route's. Read-only: nothing is written, started or run with more rights than the SSH user has.
     *
     * Two readers, chosen by `uname -s`: [MACOS] when it says `Darwin`, [LINUX] for anything else (so a host with no `uname` is read
     * as before). Both print the same blocks, so [WakeCaptureParser] has one format to read.
     */
    val COMMAND: String = (
        SHARED.joinToString("; ") +
            "; if [ \"~(uname -s 2>/dev/null)\" = Darwin ]; then " + MACOS.joinToString("; ") +
            "; else " + LINUX.joinToString("; ") + "; fi"
        ).replace('~', '$')

    /**
     * Runs [COMMAND] and reads its answer. Never throws for a machine that answers badly: that is "not available", with the reason,
     * and [WakeReading.answered] says whether the machine actually said so or the run failed.
     */
    suspend fun capture(session: SshSession, clock: Clock, previousRelay: WakeRelay?): WakeReading {
        val r = try {
            session.exec(listOf("sh", "-c", COMMAND), limits = ExecLimits(stdoutMax = 16 shl 10, stderrMax = 4 shl 10, deadline = 10.seconds))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return WakeReading(WakeTarget.unavailable("The machine could not be asked about its network interface.", clock.nowMillis(), previousRelay), answered = false)
        }
        return WakeCaptureParser.read(String(r.stdout, Charsets.UTF_8), clock.nowMillis(), previousRelay)
    }
}

/**
 * What one capture came to. [target] is what to show, available or not. [answered] is true when the command ran and its output
 * said what the machine has, so a "not available" [target] is a finding (a VPN-only route, no usable hardware address); false when
 * the run failed, was cut off or printed nothing that can be read, so [target] only carries the reason and says nothing new about
 * the machine: it must never replace what an earlier run found.
 */
data class WakeReading(val target: WakeTarget, val answered: Boolean)

object WakeCaptureParser {
    private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    private val IFACE = Regex("[A-Za-z0-9._-]{1,15}")
    private val PHY = Regex("[a-z0-9]{1,16}")

    private class Block(val dev: String, val fields: Map<String, String>)

    fun parse(output: String, nowMillis: Long, previousRelay: WakeRelay?): WakeTarget = read(output, nowMillis, previousRelay).target

    /**
     * The command's output as a [WakeReading]. The command always starts by printing `client=`, so output without it is not this
     * command's (empty, garbage, a shell error), and output with no complete interface block was cut off or came from a machine
     * where `ip` said nothing: neither is the machine saying it has no wake-capable interface, so neither counts as [answered].
     */
    fun read(output: String, nowMillis: Long, previousRelay: WakeRelay?): WakeReading {
        var ran = false
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
                key == "client" && dev == null -> ran = true
                key == "gateway" && dev == null -> gateway = value.takeIf { Ipv4Subnet.literal(it) != null }
                key == "dev" -> { dev = value.takeIf { IFACE.matches(it) }; fields = LinkedHashMap() }
                dev != null -> fields[key] = value
            }
        }
        if (!ran || blocks.isEmpty()) {
            val said = WakeTarget.unavailable("The machine did not say which network interface this phone reached it on.", nowMillis, previousRelay, gateway)
            return WakeReading(said, answered = false)
        }
        val chosen = blocks.firstOrNull { it.fields["physical"] == "yes" && MAC.matches(it.fields["mac"].orEmpty()) && it.fields["mac"] != "00:00:00:00:00:00" }
            ?: return WakeReading(
                WakeTarget.unavailable(
                    if (blocks.any { it.fields["physical"] == "yes" }) "The network interface has no usable hardware address."
                    else "This phone reached the machine over a VPN or a virtual network, and the machine has no physical interface on that route.",
                    nowMillis, previousRelay, gateway,
                ),
                answered = true,
            )
        val f = chosen.fields
        val wifi = f["phy"].orEmpty().isNotEmpty() && PHY.matches(f["phy"].orEmpty())
        val target = WakeTarget(
            available = true, mac = f.getValue("mac"), iface = chosen.dev,
            sourceAddress = f["addr"]?.takeIf { Ipv4Subnet.literal(it) != null },
            capturedAtMillis = nowMillis, gateway = gateway, relay = previousRelay,
            readiness = WakeReadiness(
                wakeup = f["wakeup"]?.takeIf { it == "enabled" || it == "disabled" },
                wowlan = if (wifi) wowlanWords(f["wowlan"]) else null,
                ethtool = if (wifi) null else ethtoolWords(f["wol"]),
                wifi = wifi, phy = if (wifi) f["phy"] else null,
                womp = if (f.containsKey("womp")) wompWords(f["womp"]) else null,
            ),
        )
        return WakeReading(target, answered = true)
    }

    private fun wowlanWords(raw: String?): String? = when {
        raw == null -> null
        raw == "missing" -> "unknown: iw is not installed"
        raw.isEmpty() -> "unknown: WoWLAN could not be read"
        "is disabled" in raw -> "disabled"
        "is enabled" in raw -> if ("magic packet" in raw) "enabled: magic packet" else "enabled"
        else -> "unknown: " + raw.take(80)
    }

    /** macOS's `womp` from `pmset -g`: `1` is on, `0` is off, and anything else (nothing printed, a word) is not known. */
    private fun wompWords(raw: String?): String = when (raw) {
        "1" -> "enabled"
        "0" -> "disabled"
        else -> "unknown: Wake for network access could not be read"
    }

    private fun ethtoolWords(raw: String?): String? = when {
        raw == null -> null
        raw == "missing" -> "unknown: ethtool is not installed"
        raw.isEmpty() -> "unknown: Wake-on could not be read"
        Regex("[a-z]{1,10}").matches(raw) -> raw
        else -> "unknown: " + raw.take(40)
    }
}
