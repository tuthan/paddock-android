# The herdr plugin and how the app uses it (Phase 11)

The host side of enrollment is a herdr plugin kept in its own repository (`herdr-plugin-paddock`, id `paddock`, local only until it is published: see [Not published](#not-published)). It is optional. Everything it does has a path without it: the copy command, a pairing link written by hand, and the consented push install of the relay.

## What the plugin gives

| What | How |
| --- | --- |
| `authorize-phone` | A herdr action that opens a popup; paste the phone's key line, read with echo off. It writes `~/.ssh/authorized_keys` exactly as the app's copy command does, only for one valid `ecdsa-sha2-nistp256` line, and refuses a symlink or a group- or world-writable target. |
| `show-pairing` | Prints a `paddock://pair?...` link for this machine (host, port, user, the host keys' SHA-256 fingerprints, session), no key and no secret. |
| `host/paddock-relay.py`, `host/paddock-control.py` | Byte-identical copies of the two scripts the app pins in `host/SOURCE.json`. `herdr plugin install` puts them in a directory herdr knows, so nothing has to be pushed over SSH. |

## Discovery order (the app)

On every bring-up of a machine (`HostSessionController.bringUp`), before the relay is checked:

1. Find herdr (the usual places, or the profile's path).
2. `herdr plugin list --plugin paddock --json`: no tty, no server needed. One JSON line; `result.plugins` empty means no plugin (`PluginLocator`). Read `plugin_id`, `plugin_root`, `version` and `enabled` and nothing else: `source.kind` and the path pattern are never relied on (a GitHub install's directory was not observed). A directory that is not a plain absolute path (`PluginLocator.isSafe`: no spaces, quotes, `.` or `..`, at most 400 characters), a disabled plugin, another plugin id, or any failure of the command is "no plugin".
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
- `fixtures/herdr-0.9.1/plugin-list-paddock.json`: herdr 0.9.1's own listing of a linked plugin, captured under `env -i` with an isolated HOME; only the plugin path was replaced.
- `Phase11PluginLiveTest` (set `PADDOCK_TEST_PLUGIN_DIR` to a plugin checkout): the real herdr and the real plugin files through a wrapper that runs herdr with an isolated HOME. Finds the plugin and accepts its relay by hash; a copy of the plugin with an edited relay is found, reported and not run, and the push install still works.
- In the plugin repository: 70 Python tests, `tools/check_pins.py` (the plugin's copies against this repository's pins, which live in two places), and `tools/live_check.py` against a real herdr 0.9.1 in isolation.

## Not published

Nothing about the plugin is public: the repository has a local tag and no remote, no GitHub topic, no marketplace listing, and no licence is chosen. Publishing is the owner's decision (owner and repository name, licence, whether the id stays the bare `paddock`, and a look at the existing marketplace entry that is also called Paddock). The app looks for the id `paddock`; publishing under another id means changing `PluginLocator.PLUGIN_ID`. The install from GitHub (AC-11.7) and what `herdr plugin list` reports for it are therefore not observed yet.
