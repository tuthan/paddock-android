# First run: Welcome, Find on this network, Scan the code (Phase 14)

What a phone with no machine shows, how Connect reports a failure, and the two ways to find the machine without typing its address. None of it connects, trusts or stores anything by itself, and none of it replaces the traditional steps: **this phone's key with its authorize command, Copy, Share, Copy key only, Show as QR, an imported key and its import screen, and Paste a pairing link are always on Add machine, in the same place, with or without a link** (decision D1; `WelcomeAndConnectTest`, `PairWithDesktopTest`, `FindOnNetworkTest`, `ScanLinkTest` assert it).

## Welcome

A phone with no saved machine opens on Welcome. It says what Paddock is, what must be true on the machine (herdr running, an SSH server the phone can reach, Python 3.11 or newer) with the command that checks each, and the ways in:

| Button | Kind | Goes to |
| --- | --- | --- |
| Enter the address | Primary | Add machine |
| Find on this network | Ghost | the finder, below |
| Paste a pairing link | Ghost | reads the clipboard once, then Add machine filled from the link |
| Scan the code on the desktop | Ghost | the scanner, below |

Back from Add machine returns to Welcome while there is still no machine. Settings > Add machine opens the form directly.

## Connect stays on the form

Connect saves the machine, starts the connection and stays on Add machine until the connection answers. The button says **Connecting…**. Then:

- **live:** the form is left for Home;
- **the host key has to be trusted:** the usual fingerprint dialog appears over the form; nothing is trusted or pinned without the tap;
- **a failure:** the sentence for that failure shows pinned above Connect with the one fix that fits, and the form keeps what was typed.

| Failure | Sentence (`DownReasonText`) | Fix |
| --- | --- | --- |
| The host refused this phone's key | The host did not accept this phone's key. Authorize it on the host, then try again. | the screen scrolls to this phone's authorize command, with the sentence directly above it |
| Local-network access is off | Local-network access is off, so Paddock cannot reach this address. | Open settings |
| The host's key changed | The host's key changed. Nothing was signed in. | the host-key review |
| The stored key cannot be read | The key stored on this phone can't be read. … | Set up the key |
| Timeout, network error, refused, closed | the reason, with "Trying again in N s." when Paddock will | Connect again |

Home says the same sentences (one source), and its authorization failure has **Show the command**, which opens Add machine for that machine with the command first ("Authorize this phone"). If Connect never decides, the form says it is still waiting after 45 seconds and offers Connect again.

**The same machine stays one profile.** Adding a machine whose host, port and user match a saved one (or, when fixing a machine's key, the same host and port) keeps its id, so its pinned host key, history and what was read about waking it carry over; a name from the finder or a link replaces the saved name only when one was given.

## Find on this network

Opened from Welcome or from the button under the Host field of Add machine (not offered while setting up the key of an existing machine, or under a pairing link: the host is already decided there).

Before anything runs the page says what it will do: *Paddock asks every address on 192.168.42.0/24 whether an SSH server answers on port 22. It connects to none of them beyond reading the server's first line, and trusts and stores nothing. On a managed network this can show up in an intrusion-detection system.* Nothing runs until **Start**.

What it does, exactly (`FinderController`, `SshProbe`, `NsdBrowser`):

- **Where:** the phone's own Wi-Fi or Ethernet network, its IPv4 subnet of /24 or smaller, minus the phone's own address. It never enumerates a wider prefix (the page says "larger than Paddock will probe (a /21). Type the machine's address instead."), a phone off Wi-Fi (cellular, a VPN alone, offline), or an IPv6-only network; each says so and offers nothing to start.
- **Ports:** 22, and one more if you type it in **Port to look at besides 22** (1 to 65535; anything else is an error and Start stays off).
- **How:** a TCP connect (600 ms) then at most one line read (800 ms, 255 bytes), 64 addresses at a time, pinned to the network being scanned so a VPN holding the default route cannot swallow it. The line must start with `SSH-` and be printable ASCII; the software shown (`OpenSSH 9.9`) is read from it. There is no key exchange: the machine sees one connection that sent nothing. About 5 seconds on a quiet /24. **Cancel** stops everything at once and keeps what was found.
- **mDNS** in the same time: it browses `_ssh._tcp.`, `_sftp-ssh._tcp.` and `_workstation._tcp.` through `NsdManager` and merges an announced name into the row of the same address. No multicast permission is needed (measured on API 26 and 36, `NsdBrowserTest`: no `SecurityException`). A browse that cannot start is said on the result ("Names announced on the network could not be listened for…") and the probe's rows still stand. Listening continues about 1.5 seconds after the last address, because a quiet network finishes the probe before an announcement could arrive.
- **Android 17** gates LAN sockets and discovery behind the local-network grant. The page asks for it first, in context (**Allow local-network access**); nothing is probed before it, and a refusal says where to turn it on (**Open settings**).

A row is `address · name · software` (and `· port N` when it is not 22). **Tapping a row fills the Host and the Port, and the machine's announced name as the profile's display name, and nothing else**: the user stays empty, no key is chosen, nothing is connected to. Typing another host afterwards drops the name. Finding a machine is not trusting it: the first connection still shows its fingerprint.

Measured: on the API 36 emulator the first plan values for the probe (300 ms connect, 500 ms banner, 32 at a time) found nothing on `10.0.2.0/24`, because the emulator's NAT answers a connect to the host late while many are in flight; 600/800/64 find `10.0.2.2` every run (`ProbeOnDeviceTest`, `check-discovery.py`). A real LAN answers a live host in milliseconds, so the margin is for Wi-Fi power saving. The API 26 emulator's Wi-Fi is a /21, which the finder refuses, and `check-discovery.py` checks that sentence there.

## Scan the code on the desktop

Opened from Welcome or Add machine. On the machine, run **Paddock: pair a phone** in herdr; it shows a code (the pairing link as a QR). The scanner reads it on the phone and Add machine opens filled from the link, exactly as Paste a pairing link does (`docs/enrollment.md`, `docs/pairing.md`).

- **The camera is asked for when the scanner opens**, never at launch. A refusal says why (*Paddock needs the camera only to read that code. Nothing is recorded, saved or sent. Without it, paste the pairing link instead.*) and keeps **Allow the camera**, **Open settings** and **Paste a pairing link** (which reads the clipboard right there). A camera that cannot start says so and offers **Try again**.
- The decoder is ZXing core 3.5.4 (`docs/dependency-reviews.md`, the Phase 14 row), fed only the camera's luminance plane; what it returns is text, which `PairingLinks` validates like pasted text. A code that is not a pairing link says so and the scan starts again. Frames are decoded in memory and never kept; nothing leaves the phone.
- `CAMERA` is the fifth permission and the camera is declared an optional feature, so a phone without one still installs the app (`tools/check-release-apk.py` checks both).

Checked on emulators whose back camera is the virtual scene with a QR on the wall poster (`check-scanner.py`): the permission dialog in context, a real Camera2 read that fills Add machine, a refusal that keeps paste (API 26 and 36).
