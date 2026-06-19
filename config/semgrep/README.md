# semgrep ruleset (cached + pinned, not vendored)

Rules for the `make semgrep` gate. They are **not** committed to this repo:
`github.com/semgrep/semgrep-rules` is licensed **LGPL-2.1 + Commons Clause**
(GitHub reports `spdx: NOASSERTION`), and committing Commons-Clause content into
this MIT repository would contaminate its license. Instead the rules are
**fetched on demand, pinned, and SHA256-verified** into this directory (the
`*.yml` here are gitignored), the same pattern as the sample-db dumps (ADR 0014).

## How it works

- `scripts/fetch-semgrep-rules.sh` downloads the pinned rule files into
  `config/semgrep/*.yml` (commit-pinned + SHA256-verified; idempotent).
- `make semgrep` fetches (if missing) then runs the scanner **offline**
  (`--network none`, `--metrics off`) against `config/semgrep/` — a deterministic,
  registry-independent gate. Wired into `make gates` and `make docker-gates`.
- The scanner image is pinned by digest via `SEMGREP_IMAGE` in the root `Makefile`.

## Cached rules

| Cached file | Rule id (as reported) | Upstream (`semgrep-rules`) |
| ----------- | --------------------- | -------------------------- |
| `dockerfile-security.yml` | `config.semgrep.missing-user` | `dockerfile/security/missing-user.yaml` |
| `python-security.yml` | `config.semgrep.use-defused-xml` | `python/lang/security/use-defused-xml.yaml` |

Pin: `semgrep-rules` commit `311ca4e9ba59d700624539bf658e3d29b134ee77`.
Deliberately accepted findings are annotated at the source with
`# nosemgrep: <rule-id>` plus a justification (see the ephemeral CI-helper stages
in the root `Dockerfile`).

## Refresh / extend

Bump `PIN_SHA` + the expected SHA256 in `scripts/fetch-semgrep-rules.sh` (and add
rows for new rule files), delete the cache, then re-run `make semgrep`.
