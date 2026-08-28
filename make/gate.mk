# ── Quality-Gates ──────────────────────────────────────────────────
#
# Ausgelagert aus dem Haupt-Makefile (per `include make/gate.mk`): die Prüf-/Gate-
# Targets und ihre Aggregatoren (gates / docker-gates / docker-full-gates).
# Cross-File-Prereqs bleiben gültig — `docs-check` hängt an `doc-check`
# (d-check.mk); die Aggregatoren referenzieren docker-build/-check/-coverage-gate/
# -smoke + integration aus dem Haupt-Makefile. Reihenfolge egal: make liest alle
# includes vor dem ersten Build, Prerequisites werden erst dann aufgelöst.

.PHONY: docs-check coverage-excludes-check semgrep-rules-fetch semgrep solid-suppression-gate mssql-fts-image ports-jdbc-free-gate readme-parity-gate gates docker-gates docker-full-gates

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

# Slice 8a: SQL Server MIT Full-Text Search. Das gepinnte Basis-Image kann es
# nicht (`IsFullTextInstalled` = 0, `mssql-server-fts` nicht aufloesbar), also
# baut sich der Volltext-Slice seine Testumgebung selbst.
#
# Bewusst NICHT in `gates`: der Bau braucht Netz und hat keinen
# Upstream-Digest, an dem die Harness sonst pinnt (ADR 0014). Gepinnt bleibt
# der Basis-Digest im Dockerfile.
MSSQL_FTS_TAG ?= d-migrate-mssql-fts:local

.PHONY: mssql-fts-image
mssql-fts-image: ## SQL-Server-Testimage mit Full-Text Search bauen (braucht Netz).
	$(DOCKER) build -t $(MSSQL_FTS_TAG) test/integration-mssql/fts

# Architektur-Fitness-Function (ADR 0022): hexagon:ports* ist java.sql-frei.
ports-jdbc-free-gate:
	./scripts/ports-jdbc-free-gate.sh

# Sprachparitaet der beiden Root-READMEs. `docs-check` prueft Links, nicht Gleichstand —
# beim 1.0.0-RC2-Cut blieb README.de.md dadurch sechs Releases zurueck (releasing.md 3.6).
readme-parity-gate:
	./scripts/readme-parity-gate.sh

# Trivy gegen die PUBLIZIERTEN Images. Digest-gepinnt statt Tag-gepinnt: ein
# Scanner, der sich unter der Hand aendert, macht Befund-Vergleiche ueber die Zeit
# wertlos. Pin-Hebung ist ein bewusster Commit. Details und Policy in
# scripts/image-scan.sh.
TRIVY_IMAGE ?= aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969
IMAGE_SCAN_REFS ?= ghcr.io/pt9912/d-migrate:latest ghcr.io/pt9912/d-migrate:native

image-scan:
	TRIVY_IMAGE="$(TRIVY_IMAGE)" IMAGE_SCAN_REFS="$(IMAGE_SCAN_REFS)" ./scripts/image-scan.sh

# `image-scan` steht bewusst NICHT in `gates`: es prueft das publizierte Image,
# nicht den Arbeitsbaum, und braucht Netz fuer die Vuln-DB. Sein Ort ist der
# Nightly (.github/workflows/image-scan.yml).
gates: docker-check docker-coverage-gate docs-check semgrep ports-jdbc-free-gate readme-parity-gate a-check

docker-gates: solid-suppression-gate docker-build docker-coverage-gate docker-smoke semgrep a-check

docker-full-gates: docker-gates integration
