# Architecture Decision Records

This directory holds Architecture Decision Records (ADRs) in
**MADR** (Markdown Any Decision Records) format. ADRs capture
significant decisions whose reasoning is not obvious from the
code or commit message alone — typically severity choices,
layering trade-offs, or carve-outs that future readers would
otherwise re-litigate.

The format follows the
[official MADR template](https://adr.github.io/madr/) hosted on
[adr.github.io](https://adr.github.io/), which catalogues
several legitimate ADR formats. We picked MADR over Michael
Nygard's classic form because of its YAML front matter (queryable
metadata: status, date, decision-makers) and its explicit
`Decision Drivers` / `Considered Options` / `Pros and Cons`
sections, which match how decisions tend to be argued in this
codebase's review cycles.

## Conventions

- One file per decision, named `NNNN-short-slug.md` (zero-padded
  4-digit sequence number, dash-separated lowercase slug).
- Template: copy
  [the official MADR template](https://github.com/adr/madr/blob/main/template/adr-template.md)
  and remove the sections that don't apply. Required sections:
  `Context and Problem Statement`, `Considered Options`,
  `Decision Outcome`. Optional but encouraged:
  `Decision Drivers`, `Consequences`, `Confirmation`,
  `Pros and Cons of the Options`, `More Information`.
- Status values follow the MADR convention:
  `proposed`, `accepted`, `rejected`, `deprecated`,
  `superseded by ADR-XXXX`.
- When superseding, leave the original file in place, update its
  `status` to `superseded by ADR-XXXX`, and reference the
  superseding ADR in `More Information`.

## Index

| # | Title | Status |
|---|---|---|
| 0001 | [MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC is WARNING, not BLOCKER](0001-mysql-routine-drop-create-non-atomic-warning.md) | accepted |
| 0002 | [UNSAFE_DEPENDENCY_PAIR stays WARNING, not BLOCKER](0002-unsafe-dependency-pair-warning-not-blocker.md) | accepted |
