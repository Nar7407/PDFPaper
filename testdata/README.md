# Test corpus (/testdata)

10 small self-made PDFs (no real-world content, no copyrighted material).
Regenerate deterministically with:

```
python testdata/generate.py
```

| File | Exercises |
| --- | --- |
| `valid-classic.pdf` | Classic xref table (§7.5.4), 1 page |
| `valid-multi-page.pdf` | 3 pages, classic xref |
| `valid-xref-stream.pdf` | Unfiltered xref stream (§7.5.8) |
| `incremental-update.pdf` | Appended update + `/Prev` chain (§7.5.6) |
| `rebuilt-bad-xref.pdf` | Wrong xref offsets → rebuild scan |
| `rebuilt-no-startxref.pdf` | Truncated tail, no `startxref` → rebuild scan |
| `gen-nonzero.pdf` | Non-zero generation number |
| `crlf-endings.pdf` | CRLF line endings throughout |
| `leading-comment.pdf` | Comment line before `%PDF-` header |
| `rebuilt-filtered-xref-stream.pdf` | Flate-compressed xref stream (undecodable until T5) → rebuild scan |

Run `:engine:corpusCheck` to open every `*.pdf` here and write
`engine/build/corpus-report.txt`. Only `*.pdf` files are opened, so this
README and `generate.py` are ignored by the harness.
