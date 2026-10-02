# Events under load (herdr 0.9.1)

What herdr 0.9.1 does with an event subscriber that falls behind, observed on 2026-10-02. The plan and the code assumed an
`events_lost` marker that closes the stream; it was never in the schema and no run produced one. These are observations of
one version on one machine over the loopback unix socket, not a contract.

## Observed

All runs: a subscriber on `events.subscribe` that stops reading, or reads slowly, while another process generates a burst
(`pane.report_agent` for status events, `pane.split` and `pane.close` for lifecycle events) in the disposable session
`paddock-test-evlost`.

1. **`events_lost` was never sent**, in any run, as an event, an error or text.
2. **Silent gaps, connection kept.** A status subscriber with 5000 reports in 3 s (stalled 10 s, then reading) received 285
   events: a sparse sample of the early reports (1, 2, 4, 158, 318, 405 …) and then the last 256 reports, contiguous, ending at
   the final one. Delivery to the client was spaced about 100 ms apart. The retained window was 256 status events when the
   agent label changed on each report and about 514 when it did not (a label change costs two messages; inferred: one shared
   channel of about 512). A slow reader (128 bytes per 0.2 s) on lifecycle events also lost events (644 of about 2400) and was
   not closed. No marker, no error, and later events arrived on the same connection.
3. **Cut off with no marker when the reader stops entirely.** The server's write to the subscriber's socket failed with
   `EAGAIN` and it closed the connection after about 114 lifecycle events (33 to 36 KB) or 139 status events (18 KB), 9 to 19 s
   after the subscription. The limit is the server's send buffer, so the client's `SO_RCVBUF` did not matter (2304 and the
   default of 212992 both delivered exactly 114 events). `herdr-server.log` says `api request failed … method="events.subscribe"
   err="Resource temporarily unavailable (os error 11)"`. The last bytes before the end of stream were a complete JSON object
   **without its newline**. Through `host/paddock-relay.py` with a stalled stdout consumer the relay's pipe took about 64 KB more
   (99,881 bytes in all), then the stream ended and the relay exited 0.
4. **Publishers are never slowed.** 5000 reports finished in 3 s while the subscriber was stalled.
5. **Recovery works.** After every run a `session.snapshot` on a new connection matched the last report (agent label, status)
   and, for lifecycle runs, the single remaining pane.

The string `channel lagged by` in the herdr binary is the text of tokio's broadcast lag error: herdr appears to swallow the lag
instead of reporting it to the subscriber (inferred, not observed).

## What the app does about it

- Events are invalidations, not data: any event sets the dirty flag and one authoritative read installs the model, so dropped
  events cost nothing as long as a later one arrives. The newest events were the ones retained in every run.
- A heartbeat read every 30 s covers a window where none arrives.
- A stream that ends (item 3) raises `RelayUnavailable`, and the monitor reconnects and reconciles. `lines()` does not emit an
  unterminated last line, so the half-delivered event is dropped, which is harmless for the same reason.
- Nothing waits for `events_lost`; the code still accepts it if a later herdr sends it (`Events.EVENTS_LOST`).

## Not shown

Another herdr version, a remote machine, the subscription for a single pane's status under real agents, and a reader that
stalls for less than a few seconds. The 10 per second, 256 and 512 figures describe this synthetic burst.

## Reproduce

```sh
tools/probe-event-loss/reproduce.sh     # about 5 minutes; captures and logs under build/event-loss-<time>/
```

It creates, uses and stops only `paddock-test-evlost` (`tools/setup-session.sh` refuses any other name pattern), so the default
session and `paddock-test` are never touched. The five runs it makes are A1 (silent gap), A2b (end of stream, no marker, lifecycle),
A3 (the same with the real CLI as publisher), A4 (slow reader) and A5 (through the relay). `analyze.py` prints the gap analysis
for A1.
