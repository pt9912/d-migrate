#!/usr/bin/env python3
"""Verify that every Gradle Kover exclude is listed in docs/coverage."""

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, order=True)
class ExcludeEntry:
    module: str
    selector: str
    pattern: str


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def gradle_files(root: Path) -> list[Path]:
    try:
        output = subprocess.check_output(
            ["git", "ls-files", "*build.gradle.kts"],
            cwd=root,
            text=True,
            stderr=subprocess.DEVNULL,
        )
        return [root / line for line in output.splitlines() if line]
    except (OSError, subprocess.CalledProcessError):
        return sorted(root.rglob("build.gradle.kts"))


def module_name(root: Path, build_file: Path) -> str:
    rel = build_file.relative_to(root)
    if len(rel.parts) == 1:
        return ":"
    return ":" + ":".join(rel.parts[:-1])


def strip_line_comments(text: str) -> str:
    out: list[str] = []
    i = 0
    in_string = False
    escaped = False
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if in_string:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and nxt == "/":
            while i < len(text) and text[i] != "\n":
                i += 1
            if i < len(text):
                out.append("\n")
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def parse_call_body(text: str, open_paren: int) -> tuple[str, int]:
    depth = 1
    i = open_paren + 1
    in_string = False
    escaped = False
    body: list[str] = []
    while i < len(text):
        ch = text[i]
        if in_string:
            body.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            body.append(ch)
        elif ch == "(":
            depth += 1
            body.append(ch)
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return "".join(body), i + 1
            body.append(ch)
        else:
            body.append(ch)
        i += 1
    raise ValueError("unclosed call body")


def kotlin_string_value(raw: str) -> str:
    out: list[str] = []
    i = 0
    while i < len(raw):
        ch = raw[i]
        if ch == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            replacements = {
                "n": "\n",
                "r": "\r",
                "t": "\t",
                '"': '"',
                "\\": "\\",
                "$": "$",
            }
            out.append(replacements.get(nxt, nxt))
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def extract_strings(body: str) -> list[str]:
    values: list[str] = []
    i = 0
    while i < len(body):
        if body[i] != '"':
            i += 1
            continue
        i += 1
        raw: list[str] = []
        escaped = False
        while i < len(body):
            ch = body[i]
            if escaped:
                raw.append("\\" + ch)
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                values.append(kotlin_string_value("".join(raw)))
                i += 1
                break
            else:
                raw.append(ch)
            i += 1
    return values


def extract_gradle_entries(root: Path) -> set[ExcludeEntry]:
    entries: set[ExcludeEntry] = set()
    for build_file in gradle_files(root):
        text = strip_line_comments(build_file.read_text())
        i = 0
        while i < len(text):
            match = re.search(r"\b(classes|packages)\s*\(", text[i:])
            if not match:
                break
            selector = match.group(1)
            open_paren = i + match.end() - 1
            try:
                body, next_index = parse_call_body(text, open_paren)
            except ValueError as exc:
                raise RuntimeError(f"{build_file}: {exc}") from exc
            for pattern in extract_strings(body):
                entries.add(ExcludeEntry(module_name(root, build_file), selector, pattern))
            i = next_index
    return entries


LEDGER_ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|")


def extract_ledger_entries(ledger: Path) -> set[ExcludeEntry]:
    entries: set[ExcludeEntry] = set()
    for line in ledger.read_text().splitlines():
        match = LEDGER_ROW.match(line)
        if not match:
            continue
        module, selector, pattern = match.groups()
        if selector in {"classes", "packages"}:
            entries.add(ExcludeEntry(module, selector, pattern))
    return entries


def main() -> int:
    root = repo_root()
    ledger = root / "docs" / "coverage" / "excludes-ledger.md"
    if not ledger.is_file():
        print(f"ERROR: missing {ledger.relative_to(root)}", file=sys.stderr)
        return 2

    gradle_entries = extract_gradle_entries(root)
    ledger_entries = extract_ledger_entries(ledger)
    missing = sorted(gradle_entries - ledger_entries)
    stale = sorted(ledger_entries - gradle_entries)

    if missing or stale:
        if missing:
            print("Missing Kover exclude ledger entries:")
            for entry in missing:
                print(f"  {entry.module} {entry.selector} {entry.pattern}")
        if stale:
            print("Stale Kover exclude ledger entries:")
            for entry in stale:
                print(f"  {entry.module} {entry.selector} {entry.pattern}")
        return 1

    print(f"All {len(gradle_entries)} Kover exclude entries are documented.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
