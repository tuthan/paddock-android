Answers captured from the real host on 2026-10-02, from a 60x20 pane of the throwaway `paddock-test` session (herdr 0.9.1, Linux).
They are test inputs for `PtyProbe`, not part of the pinned herdr corpus under `fixtures/`.

- `pane-process-info.json`: `herdr --session paddock-test pane process-info --pane <pane>`
- `proc-stat.txt`: `/proc/<shell_pid>/stat` of that pane's shell, whose controlling terminal was `/dev/pts/18`
- `stty-size.txt`: `stty -F /dev/pts/18 size` (rows, then columns)
