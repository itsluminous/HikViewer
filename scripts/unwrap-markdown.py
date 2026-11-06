#!/usr/bin/env python3
"""Unwrap hard-wrapped Markdown: one paragraph or list item per line.

Joins the continuation lines of a paragraph or of a list item onto its first line. Untouched:
fenced code blocks (``` / ~~~), indented code (4+ spaces outside a list), tables, headings, block
quotes, HTML blocks, link-reference definitions, horizontal rules and blank lines. Lines ending in a
Markdown hard break (two trailing spaces or a backslash) keep their break.

    scripts/unwrap-markdown.py README.md docs/*.md        # rewrite in place
    scripts/unwrap-markdown.py --check README.md          # exit 1 if a file would change

The rewrite only ever joins lines: the sequence of non-whitespace characters in a file is unchanged
(the script asserts this before writing).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

FENCE = re.compile(r"^\s{0,3}(```|~~~)")
LIST_ITEM = re.compile(r"^(\s*)([-*+]|\d+[.)])\s+")
HEADING = re.compile(r"^\s{0,3}#{1,6}(\s|$)")
TABLE_ROW = re.compile(r"^\s*\|")
BLOCKQUOTE = re.compile(r"^\s{0,3}>")
HTML_BLOCK = re.compile(r"^\s{0,3}<")
HRULE = re.compile(r"^\s{0,3}([-*_])(\s*\1){2,}\s*$")
LINK_DEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s")
HARD_BREAK = re.compile(r"(  |\\)$")


def is_block_start(line: str) -> bool:
    """True for a line that begins a block of its own and so never continues a paragraph."""
    return bool(
        LIST_ITEM.match(line)
        or HEADING.match(line)
        or TABLE_ROW.match(line)
        or BLOCKQUOTE.match(line)
        or HTML_BLOCK.match(line)
        or HRULE.match(line)
        or LINK_DEF.match(line)
        or FENCE.match(line)
    )


def unwrap(text: str) -> str:
    out: list[str] = []
    lines = text.split("\n")
    fence: str | None = None
    # Indent of the list item whose text the current open line belongs to, or None for a paragraph.
    open_indent: int | None = None
    in_list = False

    for line in lines:
        if fence:
            out.append(line)
            if FENCE.match(line) and line.strip().startswith(fence):
                fence = None
            continue
        m = FENCE.match(line)
        if m:
            fence = m.group(1)
            out.append(line)
            open_indent = None
            continue

        stripped = line.strip()
        if not stripped:
            out.append(line)
            open_indent = None
            continue

        item = LIST_ITEM.match(line)
        if item:
            in_list = True
            out.append(line.rstrip())
            open_indent = len(item.group(0)) if not HARD_BREAK.search(line.rstrip("\n")) else None
            continue

        indent = len(line) - len(line.lstrip(" "))
        if is_block_start(line) or (indent >= 4 and not in_list):
            # Indented code (outside a list), table, heading, quote, HTML: keep verbatim.
            out.append(line)
            open_indent = None
            if TABLE_ROW.match(line) or HEADING.match(line) or HRULE.match(line):
                in_list = False
            continue

        # A plain text line: it continues the open paragraph / list item, or starts a paragraph.
        if open_indent is not None:
            prev = out[-1]
            if HARD_BREAK.search(prev):
                out.append(line.rstrip())  # the author asked for this break; keep it
            else:
                out[-1] = prev.rstrip() + " " + stripped
            continue

        if indent == 0:
            in_list = False
        out.append(line.rstrip())
        open_indent = -1  # a paragraph is open (-1: not a list item, still joinable)

    return "\n".join(out)


def main(argv: list[str]) -> int:
    check = "--check" in argv
    paths = [Path(a) for a in argv if not a.startswith("--")]
    if not paths:
        print(__doc__, file=sys.stderr)
        return 2
    changed = 0
    for path in paths:
        original = path.read_text(encoding="utf-8")
        result = unwrap(original)
        assert "".join(result.split()) == "".join(original.split()), f"{path}: unwrap changed text, not only line breaks"
        if result == original:
            continue
        changed += 1
        if check:
            print(f"{path}: has hard-wrapped paragraphs or list items")
        else:
            path.write_text(result, encoding="utf-8")
            print(f"unwrapped {path}")
    return 1 if (check and changed) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
