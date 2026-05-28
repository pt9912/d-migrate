---
status: accepted
date: 2026-05-28
decision-makers: pt9912
consulted: c-hsm-doc ADR-0001 (sister project's planning-structure ADR, 2026-05-26)
informed: future plan-doc authors; reviewers grading where a plan-doc sits in its lifecycle
---

# Planning Folder Lifecycle (`open/` → `next/` → `in-progress/` → `done/`)

## Context and Problem Statement

`docs/planning/` has grown from three folders (`open/`, `in-progress/`,
`done/`) carrying informal, undocumented semantics. By 2026-05 the
informality had produced concrete drift:

- `docs/planning/open/sqlite-sequence-emulation-plan.md` carried
  `Status: In Progress (2026-05-28)` after Phase A/B.0/B.1/B.2
  shipped (commits `48c7f01c`, `84ba7ab7`, `25f59f73`, `09068f79`)
  but stayed in `open/` because the folder name "open" was reading
  as "not closed yet" rather than "not started yet".
- `docs/planning/open/refactoring-cli-testability.md` similarly
  carried `Status: Teilweise umgesetzt (McpServeCommand)` while
  still sitting in `open/`.
- The remaining 12 `open/` entries were a mix of `Draft`,
  `Entwurf`, `Vorschlag / Entscheidungsbasis` and pure
  reference catalogues (`test-database-candidates.md`) without a
  documented split: which of them have a scope to activate next vs.
  which are pure trigger watches?
- `docs/planning/in-progress/` held only two files
  (`roadmap.md` and `diffresult-migration-plan-2.md`), suggesting
  the folder was reserved for top-level aggregators rather than
  active per-feature slice-plans — but no document said so.
- Per-slice closure plans go to `docs/planning/done/` as
  `ImpPlan-<version>-<slice>.md`; that pattern is established but
  also undocumented.

The c-hsm-doc sister project ran into the same question and
codified the convention in its own
[`ADR-0001`](https://github.com/pt9912/c-hsm-doc/blob/main/docs/plan/adr/0001-documentation-and-planning-structure.md)
§2.4: a four-stage lifecycle with per-folder READMEs. This ADR
adopts the same model for d-migrate, with d-migrate-specific
naming carve-outs.

## Decision Drivers

- A plan-doc's folder location must be a single, glanceable signal
  of where the work stands. "Open" reading as "not closed yet" vs.
  "not started yet" is exactly the kind of overloaded signal that
  rots silently.
- Long-lived umbrella plans (e.g. `sqlite-sequence-emulation-plan.md`,
  spanning Phases A through E) need a folder that admits both
  "planning continues" and "first sub-slices shipped".
- Cross-references between plans, code KDoc, CHANGELOG, ADRs and
  the roadmap must remain stable; the folder structure should not
  forcibly rename plans every time their status nudges.
- The convention has to coexist with the established
  `ImpPlan-<version>-<slice>.md` naming for per-slice closure
  records under `done/` — that pattern is already cited from 152
  files and must not be retconned.

## Considered Options

### Option A — Three-stage `open/` → `in-progress/` → `done/` (status quo)

Keep the current folders, document the informal convention.

- Pro: zero file moves, no path-reference updates.
- Pro: matches what the 152 `done/ImpPlan-*` references already say.
- Con: "open" stays overloaded — both "trigger watches without scope"
  and "scope-skizzed-but-not-activated" land in the same folder.
- Con: doesn't model "plans that are partially shipped but still
  have phases ahead" — they don't fit cleanly in any of the three.
  This is precisely how `sqlite-sequence-emulation-plan.md` drifted.

### Option B — Four-stage `open/` → `next/` → `in-progress/` → `done/` (c-hsm-doc model)

Introduce a new `docs/planning/next/` folder between `open/` and
`in-progress/`. Plans with sketched scope but no active slice-work
live there; plans without scope stay in `open/`; plans with active
slice-work move to `in-progress/`.

- Pro: each folder has one unambiguous meaning, glanceable.
- Pro: the partial-shipment case is no longer awkward —
  `sqlite-sequence-emulation-plan.md` in `in-progress/` reads as
  "active slice-work" without claiming everything is shipped.
- Pro: tested in the sister project (c-hsm-doc) since 2026-05-26.
- Con: one-time migration of 12 existing `open/` entries; ADR +
  four READMEs to write.
- Con: cross-references in CHANGELOG, ADRs, done-plans, and code
  KDoc need a one-time bulk-update.

### Option C — Status-only convention without folder moves

Keep all in `open/`, mandate a machine-checkable `Status:` header
that downstream tools key off.

- Pro: no file moves.
- Con: folders become decorative; readers must open every file to
  see status. Glanceable-via-folder-name is the explicit driver
  this ADR exists to satisfy.

## Decision Outcome

Chosen: **Option B** — adopt the c-hsm-doc four-stage lifecycle.

```
docs/planning/open/         — trigger watches, open follow-ups without scope
docs/planning/next/         — plans with sketched scope, not yet active
docs/planning/in-progress/  — roadmap aggregators + active per-feature umbrella plans
docs/planning/done/         — shipped per-slice closure plans (ImpPlan-*) + closed umbrellas
docs/archive/               — explicitly discarded or fully superseded plans
```

### Conventions per folder

Each folder carries a `README.md` listing its convention. Summary:

- **`open/`** — entries describe a trigger / observation / open
  follow-up that does not yet have a slice scope. They stay here
  until either activated (move to `next/`) or discarded
  (move to `docs/archive/`).
- **`next/`** — entries have a sketched scope (goal, rough work
  packages, acceptance criteria) but no active implementation
  commits. They stay here until slice-work starts (move to
  `in-progress/`) or get discarded.
- **`in-progress/`** — two kinds of documents:
  1. Top-level aggregators with sprechende names (`roadmap.md`,
     `diffresult-migration-plan-2.md`). These never move.
  2. Per-feature umbrella plans whose first slice has shipped or
     whose slice-work is actively underway. They move to `done/`
     once **every** phase has shipped.
- **`done/`** — two kinds:
  1. `ImpPlan-<version>-<slice>.md` per-slice closure plans
     (established d-migrate pattern, 150+ files).
  2. Umbrella plans whose every phase has shipped; carry a
     `## Closure` section at the bottom.

### Naming conventions

- ADRs: `NNNN-short-slug.md` (4-digit, MADR format) — unchanged
  from the existing convention.
- Per-slice closure plans: `ImpPlan-<version>-<slice>.md`
  (e.g. `ImpPlan-0.9.7-cross-dialect-sequencing.md`) — unchanged.
- Umbrella plans + Roadmap-Aggregatoren: sprechende lowercase-kebab
  names without numeric prefix (e.g. `roadmap.md`,
  `sqlite-sequence-emulation-plan.md`,
  `diffresult-migration-plan-2.md`).

Note: c-hsm-doc uses a `NNN-short-slug.md` (3-digit) numeric prefix
for all plan entries. d-migrate deliberately does **not** adopt
that — the established `ImpPlan-<version>-<slice>.md` per-slice
naming carries the version information that a 3-digit prefix would
lose, and renumbering 150+ existing `done/` files would be churn
without a payoff.

### Lifecycle transitions

A plan moves between folders by `git mv` accompanied by:
- a `> Status: ...` header update inside the file documenting the
  transition;
- a sweep over cross-references (CHANGELOG, ADRs, done-plans, code
  KDoc, roadmap) to point at the new path. Frozen historical
  records (closed ADRs, done-plans) are updated too — a broken
  path is worse than an updated frozen record.

A plan moves to `docs/archive/` only when explicitly discarded or
fully superseded. Closed plans (every phase shipped) move to
`done/`, not `archive/`.

## Consequences

- The 2026-05-28 sweep (commit `457a54d9`) moved
  `sqlite-sequence-emulation-plan.md` and
  `refactoring-cli-testability.md` from `open/` to `in-progress/`
  ahead of this ADR's acceptance. A follow-up commit (same date)
  introduces `next/`, moves 9 of the 12 remaining `open/` entries
  there, writes the four READMEs, and lands this ADR.
- ADR-0003 references to
  `docs/planning/open/sqlite-sequence-emulation-plan.md` were
  rewritten to the new in-progress path in commit `457a54d9`. This
  is a deliberate carve-out from the
  "ADRs are immutable after Accepted" rule for path-reference-only
  edits — the decision content is untouched, only the file
  location moved.
- The 12 existing `open/`-entries split as follows after the
  follow-up commit:
  - **Stay in `open/`** (no scope sketched):
    `beispiel-stored-procedure-migration.md` (worked example),
    `d-browser-integration-coupling-assessment.md` (coupling
    Vorabklärung), `test-database-candidates.md` (reference
    catalogue, arguably not a plan-doc at all).
  - **Move to `next/`** (scope sketched, not active):
    `bi-demo-compose.md`, `migrations-ef-core-10.md`,
    `object-storage-artifact-store.md`, `orchestrator-examples.md`,
    `parquet-export-import-evaluation.md`,
    `persistence-jdbc-mig.md`,
    `profiling-data-quality-export.md`,
    `telemetry-observability-port.md`, `trino.md`.

## Confirmation

A plan-doc's folder is now a glanceable single signal of where the
work stands. The convention is testable mechanically (e.g. a CI
check could assert that no file with `Status: In Progress` lives
in `open/` or `next/`); that test is not part of this ADR but is
listed as a follow-up in the `in-progress/` umbrella plan that
introduces it.

## More Information

- Sister project's ADR that this one is patterned on:
  [c-hsm-doc ADR-0001](https://github.com/pt9912/c-hsm-doc/blob/main/docs/plan/adr/0001-documentation-and-planning-structure.md)
  §2.4 (four-stage lifecycle), §2.2 (file naming).
- `docs/planning/{open,next,in-progress,done}/README.md` carry the
  per-folder operational conventions.
- The 2026-05-28 sweep is split across two commits: `457a54d9`
  (move the two in-progress umbrellas, this ADR's motivating drift)
  and the follow-up commit landing `next/` + the READMEs + this ADR.
