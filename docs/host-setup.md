# Host setup

What you do on the machine that runs herdr so Paddock can watch and answer it. Written for someone who has not seen the project: every step says what it changes, how to see that it worked, and how to undo it. Paddock never runs any of this by itself; where the app offers to install a file it shows the file's SHA-256 first, asks, and checks the hash again before it runs the file.

**You need:** herdr 0.9.1 running a session you can name (`herdr session list`), an SSH server on the machine that the phone can reach (a LAN address, a VPN address or a tunnel), `python3` for the helpers (standard library only; 3.11 or newer for the alert relay and for the permission hook's configuration), and the phone with Paddock installed. Paddock opens one SSH connection to the machine and asks herdr over it; it has no server of its own and talks to nothing else.

## 1. Let the phone in

On the phone: **Add machine**. The screen shows this phone's key (a P-256 key made on the phone) and an **authorize command**.

1. Copy the command (or **Share** it to a message to yourself), and run it on the machine, as the user the phone will log in as. It creates `~/.ssh` and `authorized_keys` with the right modes if they are missing, and appends the phone's key line unless the exact line is already there. Every other line is left alone and running it twice changes nothing. To see it worked: `tail -n 2 ~/.ssh/authorized_keys` ends with `paddock@phone`.
2. Back on the phone, fill in the machine's name or address, the port (22) and the user, then **Connect**.
3. The phone shows the machine's host key fingerprint. Compare it with what the machine says: `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`. Only when they are equal, **Trust and connect**. A fingerprint that differs means the phone is not talking to your machine; press Cancel.
4. Optional: instead of typing the address, make a pairing link on the machine and open it on the phone (see `enrollment.md`), or let the phone find the machine on the same network (**Find on this network**, `onboarding.md`).

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

1. On the phone: **Settings > Locked-phone alerts**. Choose how alerts reach you (*Paddock shows the alert*, needs the ntfy app or another UnifiedPush app installed; or *The ntfy app shows the alert*, which also works on an iPhone) and, for the second, which server: the public `ntfy.sh` or your own (its `https://` address and, if it needs one, an access token). Use a server you run if the machine touches work you care about.
2. Tap **Turn on alerts…** and read the confirmation: it lists what Paddock writes on the machine (the relay script, hash `9fd18cadd27861cafa8a83a55acb8d975c2f367215178cedc3c8d1a603612285`; `~/.config/paddock/alert-relay.toml`, mode 600; the systemd user unit, hash `227c624bc91e3a5c466936694ac58b1ccc4c804adb0a01f7b37e813fb74bc7ba` (on a Mac a launchd LaunchAgent instead: `~/Library/LaunchAgents/io.github.tuthan.paddock-alert-relay.plist`, and a Python 3.11 or newer such as `brew install python`; see `alerts.md`, "On a Mac"); and, in the Paddock way, the address file). Confirm. Paddock does it over the SSH connection already open, checks the configuration with the relay's own `--check`, and only then enables and starts the unit and runs `loginctl enable-linger`. The steps and the first failure are shown on the screen. Needs systemd and Python 3.11 or newer on the machine.
3. In the ntfy-app way, **Open in the ntfy app** (or copy the link) to subscribe the ntfy app to the topic Paddock made. On an iPhone, add the topic by hand: the screen shows the server and the topic.
4. **Send a test alert** from the same screen: the machine posts one test message the way the relay would. If it does not show, the ntfy app is not subscribed, or (iPhone, your own server) `upstream-base-url` is not set.
5. **Several machines:** repeat steps 1 to 4 on each machine you want alerts from (the setup runs over the live connection, so watch the machine once; its page says so until then). Each machine has its own address or topic, so every one alerts whichever machine Paddock is watching; on an iPhone, subscribe the ntfy app to each topic by hand. While Paddock is open only the watched machine's alerts are held back, because its herd is on screen; another machine's still raises a notification. An agent raises an alert when herdr shows it `blocked` or `done`: a Claude Code or opencode prompt or question does, a Codex permission request does not (`alerts.md`).
6. On the phone, turn **Alerts** on too if you want alerts while Paddock is open (Android 13 and later asks for the notification permission here, in context). To test for real, make an agent block (or finish) and watch the phone.

The text that Paddock runs is under **Details and manual setup** on the same screen, so you can read it first or paste it into a shell yourself.

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

To undo: **Turn off alerts…** on the same screen (stops and disables the unit and removes the address file; the configuration stays, so turning them on again keeps your topic), or by hand: `systemctl --user disable --now paddock-alert-relay.service`, delete the unit file, `~/.config/paddock/alert-relay.toml` and `push-endpoint.json`, and turn Alerts off in Settings.

## 4. Answering permission requests from the phone (optional: Claude Code, Codex, opencode)

Off until you set it up. When on, the phone can answer an agent's permission request. Claude Code and opencode keep their own dialog on the desktop while it waits, so whoever answers first wins; Codex shows nothing on the desktop until the window ends (that is how its hooks work), so it has a short window of its own, and there is no "needs you" alert for a Codex request (herdr keeps the agent `working` until Codex's own prompt appears; open the agent in Paddock to see it). Any failure leaves the desktop prompt alone. Read `guarded-answers.md` first.

1. On the phone: **Settings > Machines > the machine > Guarded answers** (the machine must be the watched one). It shows the hashes of the three files it installs into `~/.local/share/paddock/`, `paddock-claude-permission-hook.py` (`dd0351a38c08cc5ab1f9d213d6cbdb586ebcdd9780112ba85cd2c2ed2f99175a`), `paddock-decide.py` (`f039d8fb5b194fdea08db3c576225d6fb199b5774996b81e835d692698ae7747`) and `paddock-opencode-permission.js` (`cb9e1ad3931551e177ba7e929188759d9267a3a6f7774f5b8f5feaaa0e10fe3b`), and installs them after you confirm.
2. Run the **configuration command** it shows. It writes `~/.config/paddock/hook.toml` with `window_seconds` (1 to 300; the default shown is 60; Claude Code and opencode) and `codex_window_seconds` (the default shown is 20; Codex) only if you have none, mode 600. No file, or `window_seconds = 0`, means the hook does nothing for any agent.
3. Register the hook for each agent you use. Paddock never edits these files; the screen has one chip per agent and shows the exact text:
   - **Claude Code:** merge the JSON under `hooks.PermissionRequest` in `~/.claude/settings.json`; the `timeout` there is the window plus five seconds. Start a new session.
   - **Codex:** merge the JSON under `hooks.PermissionRequest` in `~/.codex/hooks.json` (the command has `--agent codex`; the `timeout` is Codex's window plus ten). Start Codex and choose **Trust** when it asks about the new hook (or `/hooks`, then `t`). On a Mac start Codex with `codex --no-daemon` (Codex's shared daemon runs hooks with the first terminal's environment; Paddock finds the right pane on Linux only, and only when one Codex agent runs in that folder).
   - **opencode:** run `mkdir -p ~/.config/opencode/plugins` and `ln -sf ~/.local/share/paddock/paddock-opencode-permission.js ~/.config/opencode/plugins/paddock-opencode-permission.js`, then restart opencode.

To undo: remove the entry from `~/.claude/settings.json` and `~/.codex/hooks.json`, delete `~/.config/opencode/plugins/paddock-opencode-permission.js`, and delete `~/.config/paddock/hook.toml`.

## 5. Pair from the desktop, and wake the machine (optional)

Neither is needed: section 1 always works. Both are additions (Phase 14).

**Pair from the desktop** saves running the authorize command by hand. It needs the herdr plugin (`docs/host-plugin.md`), which is optional (github.com/tuthan/herdr-plugin-paddock; installing it from GitHub has not been run yet).

1. On the machine, run **Paddock: pair a phone** in herdr (`herdr plugin action invoke pair --plugin tuthan.paddock`, or a key bound to `tuthan.paddock.pair`). The popup prints a pairing link and a QR, opens a LAN listener on this machine's address, and waits 120 seconds.
2. On the phone: **Scan the code on the desktop** (or **Paste a pairing link**), then **Send the key**. The popup shows the key's fingerprint; compare it with the phone's and press `a`.
3. Only then is the key line appended to `~/.ssh/authorized_keys`; the phone connects by itself. Anything else (Reject is the default, a time-out, a different key) writes nothing. To undo, delete the `paddock@phone` line, as in section 1.

The listener is plaintext and LAN-only, and exists only while the popup is open; what crosses it is the phone's public key, a session handle and one result word. If the machine has several addresses, the link's host has to be the one the listener is bound to (`--listen-ip` or `--host` on the plugin's `bin/pair.py`). `docs/pairing.md` has the whole flow and the wire.

**Wake the machine** needs the machine's network interface to accept a magic packet while the machine sleeps. Paddock reads the state over SSH (read-only) and shows the commands to run, when something has to change, on the machine's page (**Machines**, from the chip on Home or from Settings, then the machine); it never runs them. Typical on a laptop with Wi-Fi:

```sh
echo enabled | sudo tee /sys/class/net/<iface>/device/power/wakeup       # allow the interface to wake the machine
sudo iw phy <phy> wowlan enable magic-packet                              # until the next reboot
nmcli connection modify "<connection name>" 802-11-wireless.wake-on-wlan magic   # keep it across reboots
```

Waking from away (over a VPN) needs a relay on the machine's network that re-broadcasts the packet; save its IPv4 address in Settings. `docs/wake.md` says what is read, what is sent, and what a failed wake looks like.

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
- **"herdr was not found on the host".** An SSH command has a short PATH (on macOS only `/usr/bin:/bin:/usr/sbin:/sbin`), so Paddock looks for herdr by absolute path in `~/.local/bin`, `~/.cargo/bin`, `/opt/homebrew/bin` (Homebrew on Apple silicon), `/usr/local/bin` (Homebrew on Intel Macs), `/home/linuxbrew/.linuxbrew/bin` and `/usr/bin`, then asks your login shell (`$SHELL -lc "command -v herdr"`, for bash, zsh, fish, sh, dash and ksh), which reads the profile that puts herdr on your terminal's PATH (`brew shellenv` goes in `~/.zprofile`). If herdr is installed anywhere else, link it into a folder from the list: `ln -s "$(command -v herdr)" ~/.local/bin/herdr`. Checked against stand-in scripts and a scripted session; **not yet run against a real Mac.**
- **The install question never appears.** The relay file may already be there and current; Paddock only asks when the file is missing or different.
- **Alerts are late or missing.** Battery optimisation on the phone, the ntfy app being stopped, or the relay not running are the usual causes; `alerts.md` lists what was measured.

## Who has followed this

Nobody besides its author yet. The release criterion (AC-10.8) is a second person following this page on a clean user account without help; that run is not done, and its result belongs in the Phase 10 evidence report.
