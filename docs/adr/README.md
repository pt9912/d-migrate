# Architecture Decision Records

This directory holds lightweight Architecture Decision Records
(ADRs) following Michael Nygard's format. ADRs capture significant
decisions whose reasoning is not obvious from the code or commit
message alone — typically severity choices, layering trade-offs,
or carve-outs that future readers would otherwise re-litigate.

## Conventions

- One file per decision, named `NNNN-short-slug.md` (zero-padded
  4-digit sequence number).
- Sections: `Status`, `Date`, `Context`, `Decision`,
  `Alternatives considered`, `Consequences`, `Implementation pointers`.
- `Status` values: `Proposed`, `Accepted`, `Superseded by ADR-XXXX`,
  `Deprecated`.
- When superseding, leave the original file in place and add a
  `Superseded by` header line so the history stays linear.

## Index

| # | Title | Status |
|---|---|---|
| 0001 | [MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC is WARNING, not BLOCKER](0001-mysql-routine-drop-create-non-atomic-warning.md) | Accepted |
| 0002 | [UNSAFE_DEPENDENCY_PAIR stays WARNING, not BLOCKER](0002-unsafe-dependency-pair-warning-not-blocker.md) | Accepted |
