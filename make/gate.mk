# ── Quality-Gates ──────────────────────────────────────────────────
#
# Ausgelagert aus dem Haupt-Makefile (per `include make/gate.mk`): die Prüf-/Gate-
# Targets und ihre Aggregatoren (gates / docker-gates / docker-full-gates).
# Cross-File-Prereqs bleiben gültig — `docs-check` hängt an `doc-check`
# (d-check.mk); die Aggregatoren referenzieren docker-build/-check/-coverage-gate/
# -smoke + integration aus dem Haupt-Makefile. Reihenfolge egal: make liest alle
# includes vor dem ersten Build, Prerequisites werden erst dann aufgelöst.

.PHONY: docs-check coverage-excludes-check semgrep-rules-fetch semgrep solid-suppression-gate ports-jdbc-free-gate readme-parity-gate gates docker-gates docker-full-gates

# docs-check bleibt die Schirm-ID (gates/ci hängen daran): aggregiert d-checks
# doc-check (Docker-Befund-Gate) plus das projekt-lokale Kover-Excludes-Ledger.
docs-check: doc-check coverage-excludes-check

coverage-excludes-check:
	python3 ./scripts/verify-kover-excludes-ledger.py

# Statische Sicherheitsanalyse via semgrep — hermetisches Gate:
#  - gepinntes Regelset, on-demand gecacht (config/semgrep/, statt `--config auto`).
#    Upstream ist LGPL-2.1 + Commons Clause → NICHT vendored, sondern per
#    scripts/fetch-semgrep-rules.sh gepinnt+SHA256-verifiziert geholt (gitignored).
#  - Image per Digest gepinnt (SEMGREP_IMAGE),
#  - Scan offline (`--network none`, `--metrics off`) → reproduzierbar.
# Bewusst akzeptierte Befunde sind inline via `# nosemgrep: <rule-id>` annotiert
# (Begruendung am Fundort). Regel-/Image-Updates kommen als bewusster Pin-Bump,
# nie als spontaner Gate-Bruch. Image + Regeln werden beim Erstlauf gezogen (Pull/
# Fetch laufen ueber Host/Daemon, nicht den `--network none`-Scan-Container).
SEMGREP_IMAGE ?= semgrep/semgrep@sha256:c180f0c93a17b420c0af5006214a29d3c747c5459c732b740191adf657dd0068

semgrep-rules-fetch:
	./scripts/fetch-semgrep-rules.sh

semgrep: semgrep-rules-fetch
	$(DOCKER) run --rm --network none -v "$(CURDIR)":/src:ro $(SEMGREP_IMAGE) \
	  semgrep scan --config /src/config/semgrep --error --metrics off /src

solid-suppression-gate:
	./scripts/solid-suppression-gate.sh

# Architektur-Fitness-Function (ADR 0022): hexagon:ports* ist java.sql-frei.
ports-jdbc-free-gate:
	./scripts/ports-jdbc-free-gate.sh

# Sprachparitaet der beiden Root-READMEs. `docs-check` prueft Links, nicht Gleichstand —
# beim 1.0.0-RC2-Cut blieb README.de.md dadurch sechs Releases zurueck (releasing.md 3.6).
readme-parity-gate:
	./scripts/readme-parity-gate.sh

gates: docker-check docker-coverage-gate docs-check semgrep ports-jdbc-free-gate readme-parity-gate a-check

docker-gates: solid-suppression-gate docker-build docker-coverage-gate docker-smoke semgrep a-check

docker-full-gates: docker-gates integration
