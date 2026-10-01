# Expected grids for the Phase 00 frame fixtures

Written by hand from the fixture sources (`tools/capture-fixtures.sh`, which prints the generators, and the frames in
`fixtures/herdr-0.9.1/frames-*.jsonl` read record by record), never produced by running an engine. An engine passes when
its grid after the named frame, rendered by `GridDump`, equals the file here.

Format, one fact per line (`#` starts a comment; blank lines are ignored):

```
size <cols> <rows>
cursor <row> <col> visible|hidden        1-based, as herdr's CUP addresses them
screen primary|alternate
text <row> |<the row's text>             only rows that are not blank; trailing spaces trimmed; a wide character is one character
style <row> <col>[-<col>] <tokens>       a run of adjacent non-blank cells with the same non-default style
wide <row> <col> <col> ...               first column of every double-width character on that row
```

Style tokens: `bold`, `dim`, `italic`, `underline`, `blink`, `reverse`, `hidden`, `strike`, `fg=<0-255>`, `fg=#rrggbb`,
`bg=<0-255>`, `bg=#rrggbb`. Blank cells carry no style line. A fixture whose frames never carry `?1049` (herdr repaints the
screen instead, see the Phase 00 report) expects `screen primary` throughout.
