#!/usr/bin/env python3
"""Write one HTML page that shows the review.png of each case from a base and a head run side by side.

Usage: pair_sheet.py BASE_DIR HEAD_DIR OUT.html [CASE...]
Without CASE, it lists every case directory that has a review.png in either run.
"""
import html
import os
import sys
from pathlib import Path


def cell(run, case, out_dir):
    png = Path(run) / case / "review.png"
    if not png.exists():
        return "<td class=missing>missing</td>"
    return f'<td><img src="{html.escape(os.path.relpath(png.resolve(), out_dir))}"></td>'


def main(argv):
    if len(argv) < 3:
        sys.exit(__doc__)
    base, head, out = argv[:3]
    cases = argv[3:] or sorted({p.parent.name for r in (base, head) for p in Path(r).glob("*/review.png")})
    out_dir = Path(out).resolve().parent
    rows = "".join(
        f"<tr><th colspan=2>{html.escape(c)}</th></tr><tr>{cell(base, c, out_dir)}{cell(head, c, out_dir)}</tr>"
        for c in cases)
    Path(out).write_text(
        "<!doctype html><meta charset=utf-8><title>Base and head</title>"
        "<style>img{max-width:100%;max-height:640px}td{vertical-align:top;text-align:center;width:50%}"
        ".missing{color:#a00}</style>"
        f"<table><tr><th>base {html.escape(str(base))}</th><th>head {html.escape(str(head))}</th></tr>{rows}</table>")


if __name__ == "__main__":
    main(sys.argv[1:])
