# Pair with the desktop (Phase 14)

Two additions to the three ways of `docs/enrollment.md`, for a machine you sit at. **Nothing here replaces the traditional steps** (decision D1): this phone's key with its authorize command, Copy, Share, Copy key only, Show as QR, an imported key and Paste a pairing link are on Add machine in the same place whether or not a link was used, and every one still works. Pairing only saves running the command by hand.

The host side is the herdr plugin's **Paddock: pair a phone** popup (`docs/host-plugin.md`; the plugin is `herdr-plugin-paddock`, version 0.2.0, local only). The wire is the plugin's `PROTOCOL.md`, summarised below.

## The flow

1. On the machine, run **Paddock: pair a phone** in herdr. The popup prints the pairing link and its QR (`qrencode`), opens a LAN listener on a random port, and waits 120 seconds for one key.
2. On the phone, **Scan the code on the desktop** (or **Paste a pairing link**). Add machine opens filled from the link, with the link's host-key fingerprints shown to compare. A link that names a listener (`pair` and `sid`) also shows **Send the key to {host}**.
3. Press **Send the key**. The phone asks for the local-network grant first when it is a LAN address, then sends **its own public key only**. The pairing page shows the key's SHA-256 fingerprint whole, with its first eight characters set apart (as the popup sets them off), the countdown, and **Cancel**.
4. The popup shows the same fingerprint and asks `[R]eject (default) / [a]pprove`. **Compare the two screens**: that comparison is the whole check, because the channel is plaintext and what crosses it is a public key and a session handle. SSH already pins the machine's identity (the link names its host-key fingerprints), and the one attack left, another key put in the phone's place, shows as a different fingerprint.
5. On approve the plugin appends the validated line to `~/.ssh/authorized_keys` (the same function `authorize-phone` uses), the phone is told `ok`, and Paddock connects by itself with the form as it was sent. Reject, a time-out or a different key writes nothing.

The webcam path is the other half: **Show as QR** on Add machine (and **Show the key to the desktop's camera** under Send the key, the same switch) draws the key as a QR with the screen at full brightness, and the popup reads it with `zbarcam` after you press Enter there. `check-key-qr.sh` reads the app's QR with `zbarimg` in both themes.

## What the phone says for each state (`PairingText`)

| State | Page | Way on |
| --- | --- | --- |
| Sending | Sending this phone's key to {host}… (compare the fingerprint) | Cancel |
| Waiting | Waiting for approval on the desktop. | Cancel |
| Cannot reach yet | Cannot reach {host}:{port} yet. Paddock keeps trying until the time runs out… and says to run the pair action with `--host` set to the desktop's LAN address when the host is a name the phone cannot resolve | Cancel |
| Approved | Approved. The key is authorized on the machine. Paddock connects now. | connects |
| Rejected | Rejected on the desktop. Nothing was written there. The desktop keeps that answer for the whole popup, so sending again changes nothing: run the pair action again for a new code, or use the command | Back |
| Time ran out | The desktop did not approve in time. Run the pair action again. Said only when the desktop's last word was still "waiting" (or it said `expired` itself) | Back |
| Code not recognized | The desktop did not recognize this code (another popup, or it was closed). | Back |
| Busy | Another key is being paired at this desktop. Wait for it or for that popup to close, then run the pair action again for a new code | Back |
| No answer before the time ran out | The desktop may have written the key anyway. Press Connect to find out; if the machine does not accept it, run the pair action again for a new code, or use the command. Also what a desktop that answered and then went quiet ends as (the owner may have approved and closed the popup) | Connect, Back |
| Could not reach | Check the same network and that the pair popup is still open. The command always works instead. | Back |

Two rules of the page: **Send the key** is offered only while the Host, Port and (when the link names one) User are what the link said, because the key is authorized for the user the popup runs as, and what the Send button sends and to whom is read at the tap (an Android local-network dialog that stays up while another link arrives cannot redirect the key). The system Back gesture is the header arrow's twin: it cancels a request that is still going, so a late approval cannot connect a phone that has left the page. Another machine coming up does not end a request made for a different desktop.

A lost reply is settled by asking, never by guessing: the key is public and the popup answers the same state for the same key, so after a restart or a dropped connection the phone asks `status` first. `PairingCoordinator` writes the request to disk before its first byte (`pending-pairing.json`, no secret in it: the key line is public), discards a reply for an older generation, and Cancel raises the generation first so a late `ok` stores nothing. The record is cleared by the first live connection, a Cancel, or the end of its window. `ok` is never trusted as the end of the story: Connect still pins the host key and asks.

## The wire, in short (`paddock-pair/1`, full text in the plugin's `PROTOCOL.md`)

- TCP over IPv4, one request line per connection (at most 4096 bytes, within 5 seconds), one reply word, then the connection closes. At most 8 simultaneous connections.
- `paddock-pair/1 key <sid> <keyline>` and `paddock-pair/1 status <sid>`. `sid` is 22 base64url characters, fresh for each popup, compared in constant time; it names the run and is not a credential. The key line must pass exactly the check `authorize-phone` applies (one `ecdsa-sha2-nistp256` line, no options, at most 1024 bytes).
- Replies: `pending`, `ok`, `rejected`, `expired`, `refused`, `busy`, `none`. A reply never contains any of the request, and nothing but a result word ever travels from the machine to the phone.
- One key is held for the popup's whole run. The same key gets its current state (so sending twice is safe); a different one gets `busy`. `ok` and `rejected` stay answerable while the listener is open, so a phone that lost the reply can ask.
- Rate: 12 counted requests per source address per minute. Counted: every `key`, every wrong `sid`, every malformed or oversize line. A `status` with the right `sid` is neither counted nor throttled, because the phone polls it every 2 seconds. The phone resends `key` only when `status` says `none`.
- The listener also allows at most 2 simultaneous connections from one source address and drops a connection that sends nothing within 2 seconds, so one silent host cannot take the slots; phones behind one NAT share an address.
- The listener binds one private, link-local, CGNAT or loopback address (never `0.0.0.0`, multicast or a public address) and is closed with the popup.

Things to know: the popup's 120-second window starts when it builds its link, the phone's countdown at its first send, so the popup can expire first (`expired` is final); the link's host must be an address the phone can use to reach the listener, which is why the popup says what to pass as `--host` when it is a name; closing the popup right after a decision can leave the phone waiting until its own window ends, which it then reports as "No answer before the time ran out" with Connect to find out.

## Checked

- Unit: `PairingTest` (every outcome, a throwing client, a late `ok` after Cancel, the record round trip), `PairingTextTest`, `PairingLinkTest` (`pair` and `sid`), `PairingLinkListenerTest`.
- UI: `PairWithDesktopTest` (every state at 100% and 200%, Send the key as an addition to every traditional control, the brightness raised for the QR and restored).
- Device, emulator: `run-pair-e2e.sh` runs the plugin's real `pair.py` in a pty against the app. Approve signs the phone in without the command (popup exit 0, one key line, mode 600, and the fingerprint on the phone equals the key file's), Reject and expire write nothing (exit 1). Found by it on API 26: `ByteArrayOutputStream.toString(Charset)` is API 33, so every reply read threw and the request sat on "Sending…"; fixed with a regression test.
- Plugin: 200 Python tests (all pass), `tools/live_check.py` against a real herdr 0.9.1 in isolation.
- Not run here (needs a phone on the author's Wi-Fi and the laptop's webcam): the webcam path five of five within 10 seconds, and the listener path from a real phone (spike S5). See the Phase 14 evidence report.
