#!/usr/bin/env python3
"""Summarize per-module Kover XML reports.

Reads `<module>.xml` files from a directory (produced by the Dockerfile
`coverage-modules` stage / `make docker-coverage-modules`) and prints:

- One-line per-module summary (sorted by coverage ascending)
- Top uncovered classes per module below 90% (configurable threshold)

Usage:
    python3 scripts/kover-modules-summary.py <dir> [--threshold 90] [--top 10]
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET


def cov(missed: int, covered: int) -> float:
    total = missed + covered
    return 100.0 if total == 0 else (covered / total) * 100.0


def parse_module(path: str):
    tree = ET.parse(path)
    root = tree.getroot()
    classes = []
    total_m = total_c = 0
    for pkg in root.findall("package"):
        pkg_name = pkg.attrib["name"]
        for cl in pkg.findall("class"):
            line_counter = next(
                (c for c in cl.findall("counter") if c.attrib["type"] == "LINE"),
                None,
            )
            if line_counter is None:
                continue
            m = int(line_counter.attrib["missed"])
            c = int(line_counter.attrib["covered"])
            classes.append({
                "package": pkg_name,
                "class": cl.attrib["name"],
                "missed": m,
                "covered": c,
                "total": m + c,
                "pct": cov(m, c),
            })
            total_m += m
            total_c += c
    return {
        "missed": total_m,
        "covered": total_c,
        "total": total_m + total_c,
        "pct": cov(total_m, total_c),
        "classes": classes,
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("directory", help="directory containing <module>.xml files")
    p.add_argument("--threshold", type=float, default=90.0,
                   help="threshold percentage (default: 90)")
    p.add_argument("--top", type=int, default=10,
                   help="top N uncovered classes per below-threshold module (default: 10)")
    args = p.parse_args()

    if not os.path.isdir(args.directory):
        print(f"error: not a directory: {args.directory}", file=sys.stderr)
        return 1

    modules = []
    for entry in sorted(os.listdir(args.directory)):
        if not entry.endswith(".xml"):
            continue
        path = os.path.join(args.directory, entry)
        name = entry[:-4]
        try:
            r = parse_module(path)
        except ET.ParseError as e:
            print(f"warn: skip {entry}: {e}", file=sys.stderr)
            continue
        modules.append({"name": name, **r})

    if not modules:
        print(f"error: no XML reports found in {args.directory}", file=sys.stderr)
        return 1

    print(f"Module coverage (sorted ascending, threshold={args.threshold}%)\n")
    print(f"{'COV%':>6}  {'COVERED':>7}  {'MISSED':>6}  {'TOTAL':>6}  MODULE")
    for m in sorted(modules, key=lambda x: x["pct"]):
        marker = " " if m["pct"] >= args.threshold else "!"
        print(f"{marker} {m['pct']:5.1f}%  {m['covered']:7}  {m['missed']:6}  "
              f"{m['total']:6}  {m['name']}")

    failing = [m for m in modules if m["pct"] < args.threshold and m["total"] > 0]
    if failing:
        print(f"\n--- Modules below {args.threshold}%: top {args.top} uncovered classes each ---")
        for m in sorted(failing, key=lambda x: x["pct"]):
            print(f"\n[{m['name']}]  {m['pct']:.2f}%  ({m['missed']} missed of {m['total']})")
            ranked = sorted(
                [c for c in m["classes"] if c["missed"] > 0],
                key=lambda x: -x["missed"],
            )
            for c in ranked[:args.top]:
                short = c["class"].split("/")[-1]
                print(f"  {c['pct']:5.1f}%  missed={c['missed']:4}  total={c['total']:4}  "
                      f"{c['package']}/{short}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
