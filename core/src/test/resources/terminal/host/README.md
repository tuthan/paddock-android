Answers captured from the real host on 2026-10-02, from a 60x20 pane of the throwaway `paddock-test` session (herdr 0.9.1, Linux).
They are test inputs for `PtyProbe`, not part of the pinned herdr corpus under `fixtures/`.

- `pane-process-info.json`: `herdr --session paddock-test pane process-info --pane <pane>`
- `stty-size.txt`: `stty -F /proc/<shell_pid>/fd/0 size` for the `shell_pid` in that answer (rows, then columns)
