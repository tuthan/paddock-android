// paddock-opencode-permission.js: lets the Paddock phone answer an opencode permission prompt.
//
// It is an opencode server plugin. Every `permission.asked` event is turned into the PermissionRequest that Claude Code's hook takes
// and handed to the same script, paddock-claude-permission-hook.py (run with `--agent opencode`), which publishes it for the phone and
// waits for a Yes or No. opencode's own dialog is on screen the whole time, so the desktop is never blocked: whoever answers first
// wins. When the hook prints a decision this plugin answers the request (`once` for Yes, `reject` for No; never `always`); when the
// desktop answers first (`permission.replied`) the hook is stopped, and when nobody answers in time nothing is sent and the dialog
// stays. Every failure leaves the dialog alone.
//
// Paddock installs this file next to the hook in ~/.local/share/paddock/ (pinned by hash). You register it by linking it into
// opencode's plugin directory yourself; Paddock never writes there:
//
//     mkdir -p ~/.config/opencode/plugins
//     ln -sf ~/.local/share/paddock/paddock-opencode-permission.js ~/.config/opencode/plugins/paddock-opencode-permission.js
//
// Tested with opencode 1.18.34 (the V1 server-plugin API: `event` receives {event} with the payload under `properties`).
// It does nothing outside a herdr pane and nothing until ~/.config/paddock/hook.toml turns the hook on.
import { spawn } from "node:child_process";

const HOOK = `${process.env.HOME}/.local/share/paddock/paddock-claude-permission-hook.py`;
const NAMES = { bash: "Bash", edit: "Edit" };           // the names Claude Code uses for the two common kinds; any other kind passes through
const MAX_DIFF = 8000;                                  // the hook keeps 256 KiB of tool input; an edit's diff can be far bigger

async function server({ client }) {
  if (!process.env.HOME || process.env.HERDR_ENV !== "1" || !process.env.HERDR_PANE_ID) return {};   // not in a herdr pane: nothing to publish to
  const waiting = new Map();                                                                         // permission id -> the hook's process

  // A 404 means the desktop answered first, which is fine.
  const answer = (id, reply, message) =>
    client._client.post({ url: `/permission/${encodeURIComponent(id)}/reply`, headers: { "Content-Type": "application/json" }, body: message ? { reply, message } : { reply } }).catch(() => {});

  function ask(p) {
    const m = p.metadata ?? {};
    const request = {
      hook_event_name: "PermissionRequest", session_id: p.sessionID, permission_mode: "default", tool_name: NAMES[p.permission] ?? p.permission,
      tool_input: { command: m.command, patterns: p.patterns, filepath: m.filepath, diff: typeof m.diff === "string" ? m.diff.slice(0, MAX_DIFF) : undefined },
    };
    const child = spawn("python3", [HOOK, "--agent", "opencode"], { env: process.env, stdio: ["pipe", "pipe", "ignore"] });
    waiting.set(p.id, child);
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.on("error", () => waiting.delete(p.id));                    // python3 or the hook is missing: the dialog stays
    child.on("close", () => {
      if (waiting.get(p.id) !== child) return;                        // stopped because the desktop answered
      waiting.delete(p.id);
      let behavior;
      try { behavior = JSON.parse(out)?.hookSpecificOutput?.decision?.behavior; } catch {}   // no output means nobody answered: leave the dialog
      if (behavior === "allow") answer(p.id, "once");
      else if (behavior === "deny") answer(p.id, "reject", "Denied from the Paddock phone");
    });
    child.stdin.on("error", () => {});
    child.stdin.end(JSON.stringify(request));
  }

  return {
    event: async ({ event }) => {
      const p = event?.properties;
      if (!p) return;
      if (event.type === "permission.asked") ask(p);
      else if (event.type === "permission.replied") {                // answered at the desktop (or by this plugin): stop waiting
        const child = waiting.get(p.requestID);
        waiting.delete(p.requestID);
        child?.kill("SIGTERM");                                       // the hook marks its request expired and exits
      }
    },
  };
}

export default { id: "paddock.permission", server };
