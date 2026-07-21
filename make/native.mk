# native.mk — GraalVM-Native-Image lokal (Linux) im Container.
#
# Zweck: die Metadaten-Schleife von Phase F des GraalVM-Slices
# (docs/planning/in-progress/graalvm-native-image-distribution.md) lokal fahren statt ueber CI.
# Gemessen 270 s lokal gegen 468 s in CI; dazu entfaellt der Dispatch-/Wartezyklus.
#
# Die GraalVM-Version im Dockerfile (25) muss zu `java-version` in
# .github/workflows/native-image.yml passen. Weicht das ab, sind lokale Befunde nicht auf CI
# uebertragbar — und die erhobenen Metadaten haetten ein anderes Format (21 kennt
# `reachability-metadata.json` nicht und ignoriert es STILL: Bau gruen, Binary kaputt).
#
# HERMETISCH: die Quellen werden ins Image kopiert, nicht gemountet. Ein Bind-Mount liesse Gradle
# `.gradle/`, `.kotlin/` und `build/` in den Arbeitsbaum schreiben — die Haupt-Dockerfile vermeidet
# das durchgaengig, und `make release-assets`/`docker-oci-build` machen es genauso: bauen, dann das
# Artefakt per stdout aus dem Container holen.

NATIVE_IMAGE_TAG ?= d-migrate:native-build
NATIVE_ENTRYPOINT ?= full
# Leer = GraalVM-Default `Throw`. `Warn` ist der F.0-Diagnosemodus: das Binary meldet fehlende
# Registrierungen und laeuft weiter, statt an der ersten zu sterben — eine Messrunde erhebt damit
# alle Luecken statt einer Schicht. NIE fuer ein ausgeliefertes Binary.
NATIVE_MISSING_REG_MODE ?=

.PHONY: native-build
native-build: ## Native: Binary im Container bauen (NATIVE_ENTRYPOINT=core|full).
	# --target ist PFLICHT: ohne ihn baut docker die LETZTE Stage der Datei. Als native-agent
	# angehaengt wurde, lief `make native-probe` dadurch gegen das Agent-Image (mit dessen
	# .d-migrate.yaml) statt gegen das Build-Image — der Messlauf war unbrauchbar.
	$(DOCKER) build -f docker/native-image.Dockerfile --target native-build \
	  --build-arg NATIVE_ENTRYPOINT=$(NATIVE_ENTRYPOINT) \
	  --build-arg NATIVE_MISSING_REG_MODE=$(NATIVE_MISSING_REG_MODE) \
	  -t $(NATIVE_IMAGE_TAG) .

.PHONY: native-diagnose
native-diagnose: ## Native: F.0-Diagnoselauf — alle fehlenden Registrierungen auf einmal erheben.
	$(MAKE) native-probe NATIVE_MISSING_REG_MODE=Warn

# Tag des lauffaehigen Native-Runtime-Images. Drop-in fuer d-migrate:dev im Sample-DB-Compose:
#   make native-runtime-build && SAMPLE_DB_DMIGRATE_IMAGE=$(NATIVE_RUNTIME_TAG) make sample-db-smoke
NATIVE_RUNTIME_TAG ?= d-migrate:native-dev

.PHONY: native-runtime-build
native-runtime-build: ## Native: lauffaehiges Runtime-Image bauen (Entrypoint = natives Binary; Drop-in fuer d-migrate:dev).
	# --target native-runtime baut die lauffaehige Stage (Entrypoint = Binary), NICHT die
	# cat-basierte native-build-Stage. NATIVE_ENTRYPOINT bleibt full (ARG-Default) = volle CLI.
	$(DOCKER) build -f docker/native-image.Dockerfile --target native-runtime \
	  --build-arg NATIVE_ENTRYPOINT=$(NATIVE_ENTRYPOINT) \
	  -t $(NATIVE_RUNTIME_TAG) .

# Direkt an den Bestimmungsort im QUELLBAUM — kein Zwischenlager.
#
# Die Agent-Ausgabe ist keine Build-Ausgabe, sondern committeter Quellcode: native-image liest sie
# aus META-INF/native-image/. Ein Zwischenverzeichnis (weder `build/` im Repo noch ~/.cache) waere
# nur unsichtbarer Zwischenzustand. Direkt hierher zu schreiben macht jede Aenderung als `git diff`
# sichtbar — die beste verfuegbare Kontrolle, nicht die schlechteste.
#
# NICHT beruehrt wird das Nachbarverzeichnis `dev.dmigrate/cli-manual/`: dort liegt handgepflegte
# Konfiguration, die ein Agent-Lauf sonst ueberschriebe.
NATIVE_AGENT_OUT ?= adapters/driving/cli/src/main/resources/META-INF/native-image/dev.dmigrate/cli

.PHONY: native-agent
native-agent: ## Native: Reachability-Metadaten per Tracing-Agent erheben (Phase F.2).
	$(DOCKER) build -f docker/native-image.Dockerfile --target native-agent \
	  -t $(NATIVE_IMAGE_TAG)-agent .
	@# Einmal-Shell (Backslash-Fortsetzung): make faehrt sonst jede Zeile in einer eigenen Shell,
	@# und das Temp-Verzeichnis waere in der naechsten Zeile schon vergessen.
	@set -eu; \
	tmp="$$(mktemp -d)"; trap 'rm -rf "$$tmp"' EXIT; \
	$(DOCKER) run --rm $(NATIVE_IMAGE_TAG)-agent | tar xf - -C "$$tmp"; \
	echo "--- Deckungsnachweis (Audit-Log): welche Operationen liefen wirklich? ---"; \
	if [ -s "$$tmp/audit.log" ]; then cat "$$tmp/audit.log"; \
	else echo "KEIN Audit-Log erzeugt — Deckung UNBELEGT."; fi; \
	echo "--- Plausibilitaetspruefung vor der Uebernahme ---"; \
	lines="$$(cat "$$tmp"/config/*.json 2>/dev/null | wc -l)"; \
	if [ "$$lines" -lt 50 ]; then \
	  echo "ABBRUCH: der Agent-Lauf hat praktisch nichts erhoben ($$lines Zeilen)."; \
	  echo "Die committete Konfiguration bleibt UNVERAENDERT."; \
	  echo "Typische Ursache: die Sonden liefen gar nicht — Sondenausgabe im Build-Log pruefen."; \
	  exit 1; \
	fi; \
	echo "  $$lines Zeilen erhoben — plausibel, wird uebernommen."; \
	mkdir -p $(NATIVE_AGENT_OUT); \
	cp "$$tmp"/config/*.json $(NATIVE_AGENT_OUT)/; \
	wc -l $(NATIVE_AGENT_OUT)/*.json; \
	echo "--- Aenderung pruefen: git diff $(NATIVE_AGENT_OUT) ---"

.PHONY: native-e2e
native-e2e: ## Native: die Subprozess-E2Es gegen das Native-Binary fahren (Slice native-e2e-regression-gate).
	# Kein Host-Extract: DMIGRATE_NATIVE_E2E=1 laesst die Harness das Binary per COPY --from aus
	# dem native-build-Image in die integration-test-native-Stage holen. Das Binary bleibt im
	# Docker-Fluss. NATIVE_E2E_TESTS grenzt auf die Subprozess-Klassen ein (die einzigen, die
	# tatsaechlich einen Kind-Prozess starten — die Szenario-Tests laufen in-process).
	DMIGRATE_NATIVE_E2E=1 ./scripts/test-integration-docker.sh \
	  ":test:e2e-cli:test $(NATIVE_E2E_TESTS) -PintegrationTests"

NATIVE_E2E_TESTS ?= --tests '*McpRealCliSubprocessTest*' --tests '*McpS3SubprocessE2ETest*'

.PHONY: native-probe
native-probe: native-build ## Native: F.0-Sonden im Container gegen das Binary fahren.
	$(DOCKER) run --rm --entrypoint /src/scripts/native-probe.sh $(NATIVE_IMAGE_TAG)

# GRMR-Abfrage: liefert eine Bibliothek gepflegte Reachability-Metadaten, oder muessen wir sie von
# Hand registrieren? Beantwortet die Frage in Sekunden, die sonst als Handsuche im Repository-Index
# endet — und ordnet vorhandene Handeintraege ein (unvermeidbar vs. Workaround).
#
# Auf einen Commit gepinnt, NICHT auf master: `curl | bash` fuehrt Fremdcode aus, und das Repo pinnt
# Actions per SHA und semgrep-Regeln per SHA256 — ein `master`-Pipe waere dort ein Stilbruch.
# Pin-Hebung ist ein bewusster Commit.
GRMR_CHECK_REF ?= 4412a87988ded779c48714462be34eec5c27f057

.PHONY: native-check-lib
native-check-lib: ## Native: prueft, ob GRMR eine Bibliothek unterstuetzt (LIB=group:artifact:version).
	@test -n "$(LIB)" || { echo "Aufruf: make native-check-lib LIB=com.zaxxer:HikariCP:6.2.1"; exit 2; }
	@curl -sSL "https://raw.githubusercontent.com/oracle/graalvm-reachability-metadata/$(GRMR_CHECK_REF)/check-library-support.sh" \
	  | bash -s "$(LIB)"
