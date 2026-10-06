# Adding a machine without typing the key (Phase 11)

Phase 14 adds more ways in on top of these three, none replacing them: **Find on this network** and **Scan the code on the desktop** (`onboarding.md`), **Send the key** to the desktop's `pair` popup (`pairing.md`), and **Wake** (`wake.md`).

Three ways to get the phone's key onto a machine and the machine into the phone. None of them connects, trusts or writes anything by itself.

## Copy and Share: the authorize command

Add machine > This phone's key shows a command, in full, before it is copied. **Copy** puts exactly that text on the clipboard and **Share** offers exactly that text to the Android share sheet (`ACTION_SEND`, `text/plain`, `EXTRA_TEXT` and nothing else: `ShareIntentTest`). **Copy key only** and **Show as QR** still give the bare key line.

```sh
umask 077; mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && { grep -qxF '<key line>' ~/.ssh/authorized_keys || { [ -z "$(tail -c1 ~/.ssh/authorized_keys)" ] || echo >> ~/.ssh/authorized_keys; printf '%s\n' '<key line>' >> ~/.ssh/authorized_keys; }; }
```

What it does, on the machine you run it on: creates `~/.ssh` (mode 700) and `authorized_keys` (600) if they are missing and sets those modes if they are not; adds a newline first only when the file does not end with one; appends the key line only when no line of the file equals it exactly. Every other line is untouched, and a second run changes nothing. A key line behind an option prefix (`command="…" ecdsa-…`) is a different line, so the plain one is added next to it: the command never edits or trusts what is already there.

The command is built only from a key line `AuthorizedKey.parse` accepts: one line, `ecdsa-sha2-nistp256`, a base64 body that is a real OpenSSH P-256 public-key blob, and a comment of letters, digits and `@ . _ -`. Nothing in that alphabet needs quoting inside single quotes, so there is nothing to escape, and no command exists for any other input (`AuthorizeCommandTest`, `AuthorizedKeyTest`). The fingerprint is shown too, in the form `ssh-keygen -l` prints.

Checked: `AuthorizeCommandTest` runs it in `sh` and `bash --posix` against a temporary HOME (with a space and a shell metacharacter in its name): twice gives one line and the same bytes, 700 and 600, other lines untouched with and without a trailing newline, an empty file, a loose existing directory and file tightened. `paddock-harness/run-pairing-e2e.sh` runs the text the app copied against a throwaway sshd's authorized_keys and then connects with it.

Not covered: a symlinked `authorized_keys` is followed like any shell redirect would; a read-only one makes the command fail loudly (`&&` chain) and change nothing.

## Pairing link

```
paddock://pair?v=1&host=<host>&port=<port>&user=<user>&fp=<SHA256:…[,SHA256:…]>[&session=<name>]
```

Produced on the machine and opened on the phone: tap it in a message (the manifest admits a VIEW intent for `paddock://pair` and, unlike a web link, no `BROWSABLE` category, so a web page cannot open it), or **Paste a pairing link** on Add machine, which reads the clipboard once, when tapped, and looks only at a token starting with `paddock://pair?`.

- It pre-fills host, port, user and session, and chooses nothing else (the key is still the phone's key). It carries no key and no secret.
- `fp` lists one to four SHA-256 fingerprints of the machine's SSH host keys (a machine can hold several, and the phone offers them in its own order, so the link names all it has). The plan's single-`fp` form is the one-element case.
- A link protects against a wrong or swapped host key, not against the person who made the link: whoever makes it chooses the machine and the fingerprint it will match. The screen says to use only a link you made yourself, and opening a link still connects nothing.
- Every field is checked against the alphabet its owner uses (`PairingLinks.parse`): printable ASCII only, at most 1,024 characters and 16 parameters, strict percent-decoding, a repeated known key refused, an unknown key ignored, nothing echoed in a refusal. Another version (`v`) is refused with "needs a newer Paddock".
- Connect registers the link's fingerprints for that machine. When the machine presents a key that is **none of them**, the connect is refused before any question: nothing is trusted or stored, and a dialog shows both fingerprints with only a Close button. When it presents one of them, the usual trust dialog adds "Same as the fingerprint in the pairing link." and **Trust and connect is still the user's tap**. Changing the host or port in the form after the link filled it in drops the comparison, and the screen says so.

Without the host plugin's `show-pairing` action, a link can be made by hand on the machine (edit the host to the name or address the phone reaches it by):

```sh
fp=$(for k in ed25519 ecdsa rsa; do f=/etc/ssh/ssh_host_${k}_key.pub; [ -r "$f" ] && ssh-keygen -lf "$f" | awk '{print $2}'; done | paste -sd, -)
echo "paddock://pair?v=1&host=$(hostname)&port=22&user=$USER&fp=$fp"
```

The inbound share sheet (Paddock listed as a target for shared text) is not built: it would put Paddock in every text share, and the tap and the paste cover the same ground. A QR form of the link needs a scanner library, which needs its own Socket review; it is not built.

## The herdr plugin

An optional herdr plugin does on the machine what the phone otherwise does by hand: `authorize-phone` takes a pasted key line and does what the command above does, `show-pairing` prints the link, and the plugin ships the pinned relay and control helper so the app can use them from the plugin directory when their hashes match. How the app finds and checks them, what happens when they differ, and what is and is not published yet are in [host-plugin.md](host-plugin.md).
