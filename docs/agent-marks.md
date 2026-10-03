# Agent marks (Phase 12)

The 32 dp tile on every agent row (the herd, the expanded blocked card) shows which agent it is: an original one-colour glyph for the ten common agents, otherwise a fixed two-letter code. Settings > Appearance > **Agent icons** (default on) switches between that and two letters for every agent. It changes only what is drawn: no setting, state or stored record depends on it, and widgets and notifications keep letters.

## Where it lives

| What | Where |
| --- | --- |
| The table (kind to code, and glyph for ten kinds), aliases, fallback | `core/.../attention/AgentMarks.kt` |
| The glyph paths (24-unit grid, 1.8 stroke, round caps, no fill; same builder as `PaddockIcons`) | `app/.../ui/theme/PaddockAgentGlyphs.kt` |
| The tile, the `LocalAgentGlyphs` switch, the row's spoken text | `app/.../ui/components/Components.kt` (`AgentMarkTile`, `rowDescription`) |
| The setting | `AppSettings.agentGlyphs`, `AppGraph.setAgentGlyphs`, provided once in `PaddockRoot` |
| What herdr 0.9.1 calls each kind | `fixtures/herdr-0.9.1/agent-start-kinds.txt` and `agent-kind-strings.tsv`, written by `tools/capture-agent-kinds.sh` |

## Rules

- The mark is decorative and the row says the agent in words (`Blocked, claude, approve edit, api, observed 40 s ago`): the tile clears its own semantics, so TalkBack reads the name once and never the code.
- Colour means state, so a mark is never tinted by kind or state: the glyph and the code use the title colour on the tile's field colour.
- No vendor logos or lookalikes. Each glyph is an idea drawn here: claude an open C with a cursor dot, codex code brackets and a slash, opencode a hexagon open on one side, gemini two rings, copilot a compass, cursor the pointer arrow, pi the letter pi, amp level bars, hermes a winged staff, shell a prompt (its tile is dashed).
- `AgentMarks.of` never throws and never returns an empty code: a kind that is not listed shows its first two letters until a row is added, no kind shows `··`. Spelling and case do not matter (`Antigravity-CLI` is `agy`, `GEMINI` is `gemini`).
- The 26 listed codes are unique (`AgentMarksTest`), which the first-two-letters rule it replaced was not: `cline` read as claude's `cl`, `kimi`, `kilo` and `kiro` were all `ki`, `maki` and `mastracode` both `ma`.

## Adding a kind

1. Capture its `agent` string with `tools/capture-agent-kinds.sh` against a disposable `paddock-test-*` session (it only runs a stand-in under a throw-away PATH), or from `herdr agent list` when a real one is running.
2. Add one row to `AgentMarks.table` with a code no other listed kind uses; `AgentMarksTest` fails if it collides.
3. Only for a common agent: add an `AgentGlyph` case and its path in `PaddockAgentGlyphs`, and a row in the vault's `assets/icon/agent-marks.html`. Draw an idea, not the vendor's mark.

## What the capture showed (herdr 0.9.1, 2026-10-03)

For 21 of the 24 `agent start --kind` values `agent.get` returns exactly the kind name in lower case (`agy` stays `agy`). `cline` is detected as `cline` but herdr never calls the stand-in ready, so `agent start` times out. `kiro` and `letta` are not detected for a stand-in of that name and are not captured. A start that times out leaves the agent name taken in that session (`agent_name_taken` on the next start of the same name), so the script refuses a session that already holds an agent.
