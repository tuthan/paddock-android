# Wake a sleeping machine (Phase 14)

Paddock can send a Wake-on-LAN magic packet to the machine it watches, from Home's banner when the machine cannot be reached and from Settings. It is Free. This page says what is read from the machine, what is sent, how a Wake that failed is told apart from one that did not wake the machine, and what the phone never does.

**It wakes a machine from sleep (suspend, `s2idle`), not from shutdown.** The Settings row says so every time it shows what was read.

## What is read, and when

On every connection the app opens, after the host key is verified and the session is up, it runs one **read-only** command over the same SSH session (`WakeCapture`; a single line of POSIX `sh` with no interpolation: nothing the machine or the phone says reaches a shell). It prints the SSH client address, the default gateway, and for the interface that carries the route back to the phone (and the default route's, when that is another): whether it is a physical device, its MAC, its IPv4 address, `device/power/wakeup`, and for Wi-Fi `iw phy <phy> wowlan show`, for Ethernet the `Wake-on:` line of `ethtool`. It writes nothing, starts nothing, runs nothing with more rights than the SSH user has, and is limited to 16 KiB of output and 10 seconds. A machine that answers badly gives "not available" with a reason, never a failed connection.

What is stored with the machine's profile (`HostProfile.wake`, validated when it is loaded, so a hand-edited file fails instead of half-loading): the MAC and interface of a **physical** interface only (a tunnel, bridge or virtual device never counts; when the route to the phone is a VPN address the default route's physical interface is used and the LAN address read is shown so the reason is visible), the readiness it found, the gateway as a relay suggestion, the time it was read, and your relay if you saved one. Connect to the machine once while it is awake before relying on Wake.

## Settings > the machine's row

It says what was read in words, with when: *Ready · 3c:… on wlp0s20f3 · wakes from sleep, not from shutdown · as of 18:04*, or *Not ready: Wi-Fi wake (WoWLAN) is disabled*, or *Not known: …*, or *Not available: …*. When something has to change on the machine, the row shows the commands and a **Copy** for each. **Paddock never runs them**; the machine needs `sudo` and the decision is yours:

| What the reading says | Command shown |
| --- | --- |
| the interface is not allowed to wake the machine | `echo enabled \| sudo tee /sys/class/net/<iface>/device/power/wakeup` |
| Wi-Fi wake is off | `sudo iw phy <phy> wowlan enable magic-packet` (until the next reboot) |
| Ethernet Wake-on is not `g` | `sudo ethtool -s <iface> wol g` (until the next reboot) |
| to keep it across reboots | `nmcli connection modify "<connection name>" 802-11-wireless.wake-on-wlan magic` (or `802-3-ethernet.wake-on-lan magic`), the connection name from `nmcli -t -f NAME,DEVICE connection show --active` |

Whether a given laptop wakes from `s2idle` on a magic packet over Wi-Fi depends on the hardware and firmware; "ready" means the settings say so, not that it has been proven (the spike S2 is a device check, see the evidence report).

## What Wake sends

From Home's degraded banner (offered for *cannot reach the host*, *did not answer in time*, *did not answer in time on the local network* and *disconnected*, when wake is available and the phone is on a LAN holding the host or a relay is saved) or Settings:

- **On the machine's LAN** (an IPv4 address of Wi-Fi or Ethernet whose subnet holds the host): the 102-byte magic packet (6 × `FF`, then the MAC 16 times) as UDP to port 9, to the subnet's **directed broadcast** and to `255.255.255.255`, **three times, 100 ms apart** (one UDP datagram is easily lost on Wi-Fi). The sender is bound to that network, so the packet leaves through the Wi-Fi even while a VPN holds the default route. Nothing is sent when the phone holds no IPv4 LAN address: Wake is then off with its sentence.
- **From away** (over a VPN such as Tailscale, where no LAN subnet holds the machine): the same packet as a **unicast to the relay you saved**, an IPv4 literal and a port (default 9). The relay is a device on the machine's network that re-broadcasts it: a router with a forwarder, or UpSnap or a similar service on another machine. Without a saved relay nothing is sent and the field is offered; a suggestion (the machine's gateway) is never used unsaved; a name, an IPv6 literal, loopback, multicast, broadcast and a wildcard are refused.
- Android 17 asks for the local-network grant first, as it does for Connect; without it nothing is sent and the sentence says so.

## Three facts, never one

A Wake tap is reported as three separate facts, and "sent" is never read as "awake":

1. **The packet was sent** (to which addresses; or to the relay, with "The relay must re-broadcast it; nothing reached the machine's network from here"), or why nothing was.
2. **The machine answered**: the SSH connect, with the time, or *not yet*.
3. **herdr reachable**: the first read, with the time, or *not yet*.

A second tap is offered after 30 seconds (a second magic packet says nothing the first has not). After a Wake the connection is followed for three minutes. A packet that was sent and followed by nothing means a machine that did not wake, a relay that dropped it, or a different network; the three lines make that visible without claiming which.

## What it does not do

No wake from shutdown, no wake over the internet without your relay, nothing run on the machine, no `sudo`, no WakeLock, no background service: a Wake is one foreground tap. No packet is sent without a tap. The relay is yours: Paddock does not install or configure one.

## Checked

- Unit (`:core`): the packet bytes against a known vector and the destinations and counts (`WakeTest`), the capture command's quoting for `argvToCommand` and a run through a real `sh` with fake tools, the parser over a real Wi-Fi capture and the hostile cases (`WakeCaptureTest`), the relay parser and the path order (`WakeTest`), the three facts and the guard (`WakeFactsTest`), profile validation, the Home row and its words (`HomeUiMapperTest`, `WakeWordsMapperTest`), and the capture being best effort (`HostSessionControllerTest`).
- The capture command was run for real on the author's laptop and matched the stored Wi-Fi fixture byte for byte.
- Not run here, and said so in the evidence report: S2 (a magic packet waking this laptop from `s2idle` over Wi-Fi: it would suspend the machine the work runs on), AC-14.9 (a real phone waking it three times with `tcpdump` on the host), and the relay run over Tailscale from cellular.
