# The herdr plugin and how the app uses it (Phase 11, with the `pair` action of Phase 14)

The host side of enrollment is a herdr plugin kept in its own repository (`herdr-plugin-paddock`, id `tuthan.paddock`, public at github.com/tuthan/herdr-plugin-paddock under Apache-2.0, not yet tagged or listed: see [Publication](#publication)). It is optional. Everything it does has a path without it: the copy command, a pairing link written by hand, and the consented push install of the relay.

**Platforms.** The plugin declares `linux` and `macos`. Linux is what was run for real (the plugin's live check against a disposable herdr session, and a phone). The macOS paths (LAN address from `route` and `ipconfig`, `pbcopy`, the application-firewall note, no camera intake because that needs a V4L2 camera and `zbarcam`) are unit-tested against stand-ins that print what the macOS programs print, and **have not been run on a Mac**. The two host scripts the plugin ships use only POSIX calls, so the app's pins are unchanged. What the app does with a macOS host (relay, control, alerts) was not exercised either.

## What the plugin gives

| What | How |
| --- | --- |
| `authorize-phone` | A herdr action that opens a popup; paste the phone's key line, read with echo off. It writes `~/.ssh/authorized_keys` exactly as the app's copy command does, only for one valid `ecdsa-sha2-nistp256` line, and refuses a symlink or a group- or world-writable target. |
| `pair` | One popup for the whole enrollment: the pairing link and its QR, then **one** public key from the phone's LAN listener (Send the key), the desktop webcam reading the app's key QR, or paste; the key's fingerprint is shown and `authorized_keys` is written only after you approve (Reject is the default). Nothing received is run or written anywhere else. See `pairing.md`. |
| `show-pairing` | Prints a `paddock://pair?...` link for this machine (host, port, user, the host keys' SHA-256 fingerprints, session), no key and no secret. |
| `host/paddock-relay.py`, `host/paddock-control.py` | Byte-identical copies of the two scripts the app pins in `host/SOURCE.json`. `herdr plugin install` puts them in a directory herdr knows, so nothing has to be pushed over SSH. |

## Discovery order (the app)

On every bring-up of a machine (`HostSessionController.bringUp`), before the relay is checked:

1. Find herdr (the usual places, or the profile's path).
2. `herdr plugin list --plugin tuthan.paddock --json`: no tty, no server needed. One JSON line; `result.plugins` empty means no plugin (`PluginLocator`). Read `plugin_id`, `plugin_root`, `version` and `enabled` and nothing else: `source.kind` and the path pattern are never relied on (a GitHub install's directory was not observed). A directory that is not a plain absolute path (`PluginLocator.isSafe`: no spaces, quotes, `.` or `..`, at most 400 characters), a disabled plugin, another plugin id, or any failure of the command is "no plugin".
3. Hash `<plugin_root>/host/paddock-relay.py` on the host (`sha256sum`) and compare it with the app's own pin:
   - **equal:** the relay runs from there. Nothing is asked and nothing is written to the host.
   - **different:** that file is never run. If the pushed copy (`~/.local/share/paddock/paddock-relay.py`) is the pin, it runs from there. Otherwise the usual install screen is shown with a note naming the plugin's version and saying to reinstall the plugin from the release that matches the app (herdr has no plugin update command), or to install the relay here. Install is still the user's tap.
   - **absent:** the push install as before.
4. The check is repeated before every reconnect (`beforeReconnect`), so a plugin reinstalled with another file while the phone is connected is refused, not run.

The control helper (`paddock-control.py`) follows the same order. The alert relay, the decision writer and the Claude hook are not looked for in the plugin: the first runs under a user unit and the other two are referenced by absolute path from the host's Claude settings, so they stay where the app puts them.

The hash pin stays the trust anchor. The plugin directory is a place to look, not a thing that is trusted; this protects against a stale or edited copy, not against someone who already controls the host account.

The app never runs `herdr plugin install` on the host. It shows the user what to run.

## Checks

- `PluginLocatorTest`, `RelayInstallerPluginTest`, `HostSessionControllerTest` (plugin cases), `RelayInstallTest` (the note): the table of hostile directories, the order above, nothing written for a pinned plugin copy, a mismatched copy never run, a change after connecting refused.
- `fixtures/herdr-0.9.1/plugin-list-paddock.json`: herdr 0.9.1's own listing of the linked plugin (id `tuthan.paddock`, version 0.2.0), captured under `env -i` with an isolated HOME; only the plugin path was replaced.
- `Phase11PluginLiveTest` (set `PADDOCK_TEST_PLUGIN_DIR` to a plugin checkout): the real herdr and the real plugin files through a wrapper that runs herdr with an isolated HOME. Finds the plugin and accepts its relay by hash; a copy of the plugin with an edited relay is found, reported and not run, and the push install still works.
- In the plugin repository: 200 Python tests (the 70 above plus the `pair` popup, its listener and the open-pane wrapper), `tools/check_pins.py` (the plugin's copies against this repository's pins, which live in two places), and `tools/live_check.py` against a real herdr 0.9.1 in isolation.

## The `pair` action (Phase 14)

Plugin version 0.2.0 adds the `pair` action (`tuthan.paddock.pair`) and a pane for it (`bin/pair.py`, `PairListener` in `lib/paddock_plugin.py`, `PROTOCOL.md`). The app side is the pairing page and its coordinator (`docs/pairing.md`). The app learns two optional link parameters, `pair=<port>` and `sid=<22 characters>`; an older app ignores both and pairs the old way, and a link without them is exactly what `show-pairing` always printed. Nothing about the discovery order above changes: the pair popup does not install or replace any script.

## Publication

The plugin repository is public at github.com/tuthan/herdr-plugin-paddock, under Apache-2.0 like this app. Still the owner's steps: a release tag (the README's install line names `v0.2.0`, which does not exist yet), the `herdr-plugin` GitHub topic that puts it in herdr's marketplace, and a look at the other marketplace entry that is also called Paddock (`neyham.paddock`). The id is `tuthan.paddock`, owner-qualified like herdr's own examples, so the two cannot be confused; the app looks for that id (`PluginLocator.PLUGIN_ID`), and an id change means changing it. The install from GitHub (AC-11.7) and what `herdr plugin list` reports for it are therefore not observed yet.
