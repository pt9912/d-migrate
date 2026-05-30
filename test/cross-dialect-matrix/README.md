# test:cross-dialect-matrix

Cross-dialect regression sweep that pins per-workstream file-mode
migrate behaviour across PostgreSQL, MySQL and SQLite in a single
table-driven test.

Plan-Doc:
[quality-coverage-expansion-plan.md](../../docs/planning/in-progress/quality-coverage-expansion-plan.md)
§5.2, §6 (Sub-Slice B + B-Vervollständigung).

Cross-criteria source:
[diffresult-migration-plan-2.md](../../docs/planning/in-progress/diffresult-migration-plan-2.md)
§11.2.

## Why this module exists

Each workstream in the diff-migration plan already has dedicated unit
or integration tests for its dialect-specific behaviour. What was
missing was a single executable surface that says **"green/red for
every (workstream × dialect × kind) cell"** without sifting through
dozens of test suites. This module is that surface.

The sweep:

1. Iterates `MatrixWorkstreams.ALL × dialects × kinds`.
2. For each cell, classifies it as **PINNED** (fixture pair present),
   **CARVE_OUT** (registered in [`fixtures/carve-outs.yaml`](src/test/resources/fixtures/carve-outs.yaml)
   with `reason` + `planRef`), or **MATRIX_GAP**.
3. Fails fast on `MATRIX_GAP` so a new workstream cannot land without
   either pinning a fixture or registering a justified carve-out.
4. Runs the pinned, non-carved cells through `SchemaMigrateRunner`
   in file-mode with the real dialect renderers and asserts the
   exit code.

## Layout

```
test/cross-dialect-matrix/
├── build.gradle.kts                       # minBound(0); no aggregate
├── README.md
└── src/test/
    ├── kotlin/dev/dmigrate/test/matrix/
    │   ├── MatrixCell.kt                 # (workstream, dialect, kind) tuple
    │   ├── MatrixWorkstreams.kt          # PINNED + ALL catalogues
    │   ├── MatrixFixtures.kt             # classpath fixture loader
    │   ├── MatrixSweepRunner.kt          # SchemaMigrateRunner wiring
    │   ├── CarveOutRegistry.kt           # YAML carve-out loader + wildcard match
    │   └── MatrixSweepTest.kt            # the sweep itself
    └── resources/
        └── fixtures/
            ├── carve-outs.yaml
            ├── G.1/positive/{current,desired}.yaml
            ├── G.2/positive/{current,desired}.yaml
            ├── F.5/positive/{current,desired}.yaml
            ├── D.3/positive/{current,desired}.yaml
            └── E.2/positive/{current,desired}.yaml
```

Fixture pairs live under `<workstream>/<kind>/` — they are
dialect-independent neutral YAML. The dialect dimension is supplied
by the runner (`SchemaMigrateRequest.dialect`), and any dialect-
specific blocker (e.g. MV on MySQL/SQLite) is registered as a
per-dialect carve-out rather than as a separate fixture.

## Sub-Slice B pinning

Five workstreams are pinned with POSITIVE fixtures:

| Workstream | What it covers |
| ---------- | -------------- |
| G.1        | `transactionScope` happy path (empty → CREATE TABLE) |
| G.2        | Rollback-artefact emission (separate fixture so renderer divergence surfaces) |
| F.5        | CHECK constraint diffbarkeit (positive add) |
| D.3        | Materialized View positive (PG-only; MySQL/SQLite carved out) |
| E.2        | Trigger create-event |

BLOCKER cells across all five workstreams are provisional carve-outs
in `fixtures/carve-outs.yaml`; B-Vervollständigung promotes them to
real fixture pairs. The remaining 17 workstreams from the
diff-migration plan-doc are full-wildcard carve-outs with `reason` +
`planRef` so any future maintainer who removes the carve-out without
adding a fixture lands a `MATRIX_GAP` diagnostic immediately.

## Running

The sweep is part of the default `:test` pass:

```
make docker-test MODULES=":test:cross-dialect-matrix"

# or, equivalently
./gradlew :test:cross-dialect-matrix:test
```

It is **not** part of the perf-tag opt-in (`make docker-perf`) — the
sweep runs file-mode only, no DB or Testcontainers, and is cheap
enough to live on every PR run.

## Adding a workstream

1. Add the workstream slug to `MatrixWorkstreams.ALL`.
2. Either pin fixtures under `src/test/resources/fixtures/<slug>/<kind>/`
   OR add a carve-out entry to `fixtures/carve-outs.yaml`. A
   wildcard entry (`dialect: "*"`, `kind: "*"`) covers all six cells
   in one line.
3. The sweep's `MATRIX_GAP` test will fail until either action is
   taken; that is the intended forcing function.

For a pinned cell with a non-default expected exit code (e.g. a
POSITIVE that intentionally exits 8 because the workstream is
dialect-blocked), today's runner uses the convention
`POSITIVE → 0, BLOCKER → 8`. A future expansion may add an explicit
expected-exit override file per workstream.
