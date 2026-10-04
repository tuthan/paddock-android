# Host setup

What you do on the machine that runs herdr so Paddock can watch and answer it. Written for someone who has not seen the project: every step says what it changes, how to see that it worked, and how to undo it. Paddock never runs any of this by itself; where the app offers to install a file it shows the file's SHA-256 first, asks, and checks the hash again before it runs the file.

**You need:** herdr 0.9.1 running a session you can name (`herdr session list`), an SSH server on the machine that the phone can reach (a LAN address, a VPN address or a tunnel), `python3` for the helpers (standard library only; 3.11 or newer for the alert relay and for the permission hook's configuration), and the phone with Paddock installed. Paddock opens one SSH connection to the machine and asks herdr over it; it has no server of its own and talks to nothing else.

## 1. Let the phone in

On the phone: **Add machine**. The screen shows this phone's key (a P-256 key made on the phone) and an **authorize command**.

1. Copy the command (or **Share** it to a message to yourself), and run it on the machine, as the user the phone will log in as. It creates `~/.ssh` and `authorized_keys` with the right modes if they are missing, and appends the phone's key line unless the exact line is already there. Every other line is left alone and running it twice changes nothing. To see it worked: `tail -n 2 ~/.ssh/authorized_keys` ends with `paddock@phone`.
2. Back on the phone, fill in the machine's name or address, the port (22) and the user, then **Connect**.
3. The phone shows the machine's host key fingerprint. Compare it with what the machine says: `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`. Only when they are equal, **Trust and connect**. A fingerprint that differs means the phone is not talking to your machine; press Cancel.
4. Optional: instead of typing the address, make a pairing link on the machine and open it on the phone (see `enrollment.md`).

To undo: delete the `paddock@phone` line from `~/.ssh/authorized_keys`.

## 2. The two helpers the app installs (needed for every connection and every control session)

The app asks before it installs each of these, and shows the hash. Both are single Python files with no dependencies; they are copied to `~/.local/share/paddock/` with owner-only permissions. Paddock refuses to run a file whose hash is not the one it was built with, so a file edited on the machine is never used (the app says so and offers to reinstall).

| File | What it does | SHA-256 (release 0.0.1 and any build whose `host/SOURCE.json` shows the same) |
| --- | --- | --- |
| `paddock-relay.py` | Opens the connection to herdr's local socket and passes herdr's own JSON through. It adds nothing herdr could not already answer. | `8effb4b5aa733fccf72b5ca31545bc24f05046d000d463d6939b3486b3339695` |
| `paddock-control.py` | Runs exactly one `herdr terminal session control` command for the Terminal tab and forwards only the four terminal message types; it releases control when the phone stops answering. | `d95e0a155a4e7a9addcc0018c3c267448a45e0611bfb24cad5b88df43a23ceff` |

On first connect the app shows the relay's install question: **Install the relay** copies the file; **Not now** closes the question and installs nothing, and Home offers it again as a recovery action. The Terminal tab asks for the control helper the first time you ask for control.

To verify by hand, on the machine: `sha256sum ~/.local/share/paddock/paddock-relay.py ~/.local/share/paddock/paddock-control.py` must print the two hashes above. To undo: `rm -r ~/.local/share/paddock`.

## 3. Alerts on a locked phone (optional)

Without this, Paddock only knows what is happening while it is open. With it, a small program on the machine posts a generic message ("an agent needs you") to a push path you choose; nothing about the agent is in the message. Read `alerts.md` for what it promises and what it does not (measured best effort, never always-on).

1. On the phone: **Settings > Locked-phone alerts**. It shows the alert relay's hash, `9fd18cadd27861cafa8a83a55acb8d975c2f367215178cedc3c8d1a603612285`, and installs only that file after you confirm.
2. Run the commands the screen shows, on the machine. They write `~/.config/systemd/user/paddock-alert-relay.service` (hash `227c624bc91e3a5c466936694ac58b1ccc4c804adb0a01f7b37e813fb74bc7ba`) and, only if you have none, `~/.config/paddock/alert-relay.toml` (mode 600).
3. Edit that file: the `url` of your ntfy server and a long random `topic`. The topic name is the only secret in this path; use a server you run if you can. Install the ntfy app on the phone and subscribe to the same topic.
4. Check it: `python3 ~/.local/share/paddock/paddock-alert-relay.py --check`. Exit 0 means the configuration parses and herdr answers; 2 means the configuration is wrong; 3 means herdr is not reachable.
5. Start it: `systemctl --user daemon-reload && systemctl --user enable --now paddock-alert-relay.service`, and `loginctl enable-linger "$USER"` so it runs without a login session and after a reboot.
6. On the phone, turn **Alerts** on (Android 13 and later asks for the notification permission here, in context). To test, make an agent block (or finish) and watch the phone.

To undo: `systemctl --user disable --now paddock-alert-relay.service`, delete the unit file and `~/.config/paddock/alert-relay.toml`, and turn Alerts off in Settings.

## 4. Answering permission requests from the phone (optional, Claude Code only)

Off until you set it up. When on, Claude Code's own dialog stays on the desktop and the phone can answer the same request while it waits; whoever answers first wins, and any failure leaves the desktop dialog alone. Read `guarded-answers.md` first.

1. On the phone: **Settings > Guarded answers**. It shows the hashes of the two files it installs, `paddock-claude-permission-hook.py` (`964ea1a48e605e15d0d10c187cee401edca69604cf4f19d7eddab674ec41e478`) and `paddock-decide.py` (`f039d8fb5b194fdea08db3c576225d6fb199b5774996b81e835d692698ae7747`), and installs them after you confirm.
2. Run the **configuration command** it shows. It writes `~/.config/paddock/hook.toml` with `window_seconds` (1 to 300; the default shown is 60) only if you have none, mode 600. No file, or `window_seconds = 0`, means the hook does nothing.
3. Register the hook yourself: merge the JSON the screen shows under `hooks.PermissionRequest` in `~/.claude/settings.json`. Paddock never edits that file. Claude Code's `timeout` there is the window plus five seconds.

To undo: remove that entry from `~/.claude/settings.json` and delete `~/.config/paddock/hook.toml`.

## Checking the whole thing

| Check | Command or screen | Good looks like |
| --- | --- | --- |
| The phone is in | Paddock Home | the machine's chip says live, and agents appear |
| Helpers are the pinned ones | `sha256sum ~/.local/share/paddock/*.py` | each hash equals the table above |
| herdr version | `herdr --version` | 0.9.1 (another version works only if its protocol matches; Settings shows what the app saw) |
| The alert relay runs | `systemctl --user status paddock-alert-relay.service` | active (running), and `--check` exits 0 |

## When something does not work

- **"The host's key changed. Nothing was signed in."** The machine presented another key than the one you trusted. If you reinstalled the machine's SSH server, compare the new fingerprint as in step 1 and choose **Replace with the new key**; otherwise keep the old key and find out why.
- **The phone cannot reach the machine.** On Android 17 a LAN address needs the Nearby devices permission; the app asks once and, after a refusal, shows a row that opens its settings page.
- **The install question never appears.** The relay file may already be there and current; Paddock only asks when the file is missing or different.
- **Alerts are late or missing.** Battery optimisation on the phone, the ntfy app being stopped, or the relay not running are the usual causes; `alerts.md` lists what was measured.

## Who has followed this

Nobody besides its author yet. The release criterion (AC-10.8) is a second person following this page on a clean user account without help; that run is not done, and its result belongs in the Phase 10 evidence report.
