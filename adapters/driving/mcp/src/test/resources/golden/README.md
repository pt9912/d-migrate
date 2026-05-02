# Phase B golden test fixtures

This directory holds golden snapshots used by tests in
`adapters/driving/mcp` to pin contract-stable artefacts against
unintentional drift. The full contract lives in
`docs/planning/ImpPlan-0.9.6-B.md` §12.18.

## `phase-b-tool-schemas.json`

Serialised JSON-Schemas (`inputSchema` + `outputSchema`) for every
0.9.6 tool registered by `PhaseBToolSchemas`. Pinned by
`PhaseBToolSchemasGoldenTest`.

### When the test fails

A test failure means the in-code schemas drifted from this file.
That's intentional iff you actually meant to change a tool schema —
otherwise something was renamed / restructured by accident.

### Regenerating the golden

After an intentional schema change, regenerate the file:

```sh
# inside Docker (preferred per the project's build policy)
docker run --rm -v "$PWD":/src -w /src -e UPDATE_GOLDEN=true \
  gradle:8.12-jdk21 \
  gradle --no-daemon :adapters:driving:mcp:test --tests '*PhaseBToolSchemasGoldenTest*'

# or via -D system property
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driving:mcp:test --tests *PhaseBToolSchemasGoldenTest* -DUPDATE_GOLDEN=true" \
  -t d-migrate:golden-update .
```

The test writes the regenerated content to this file in-tree; review
the `git diff` and commit alongside the schema change.

CI runs without the env / system-property and fails the build on
drift.
