# native.mk — GraalVM-Native-Image lokal (Linux) im Container.
#
# Zweck: die Metadaten-Schleife von Phase F des GraalVM-Slices
# (docs/planning/in-progress/graalvm-native-image-distribution.md) lokal fahren statt ueber CI.
# Gemessen 270 s lokal gegen 468 s in CI; dazu entfaellt der Dispatch-/Wartezyklus.
#
# Die GraalVM-Version im Dockerfile ist auf 21.0.2 gepinnt — identisch zu `java-version` in
# .github/workflows/native-image.yml. Weicht das ab, sind lokale Befunde nicht auf CI uebertragbar.
#
# HERMETISCH: die Quellen werden ins Image kopiert, nicht gemountet. Ein Bind-Mount liesse Gradle
# `.gradle/`, `.kotlin/` und `build/` in den Arbeitsbaum schreiben — die Haupt-Dockerfile vermeidet
# das durchgaengig, und `make release-assets`/`docker-oci-build` machen es genauso: bauen, dann das
# Artefakt per stdout aus dem Container holen.

NATIVE_IMAGE_TAG ?= d-migrate:native-build
NATIVE_ENTRYPOINT ?= full
NATIVE_BIN ?= build/native/d-migrate
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

NATIVE_AGENT_OUT ?= build/native-agent

.PHONY: native-agent
native-agent: ## Native: Reachability-Metadaten per Tracing-Agent erheben (Phase F.2).
	$(DOCKER) build -f docker/native-image.Dockerfile --target native-agent \
	  -t $(NATIVE_IMAGE_TAG)-agent .
	rm -rf $(NATIVE_AGENT_OUT) && mkdir -p $(NATIVE_AGENT_OUT)
	$(DOCKER) run --rm $(NATIVE_IMAGE_TAG)-agent | tar xf - -C $(NATIVE_AGENT_OUT)
	@echo "--- Deckungsnachweis (Audit-Log): welche Operationen liefen wirklich? ---"
	@if [ -f $(NATIVE_AGENT_OUT)/audit.log ]; then \
	  cat $(NATIVE_AGENT_OUT)/audit.log; \
	else \
	  echo "KEIN Audit-Log erzeugt — Deckung unbelegt."; \
	fi
	@echo "--- erzeugte Metadaten ---"
	@wc -l $(NATIVE_AGENT_OUT)/config/*.json 2>/dev/null || true

.PHONY: native-binary
native-binary: native-build ## Native: gebautes Binary nach $(NATIVE_BIN) herausholen.
	mkdir -p $(dir $(NATIVE_BIN))
	$(DOCKER) run --rm $(NATIVE_IMAGE_TAG) > $(NATIVE_BIN)
	chmod +x $(NATIVE_BIN)
	@echo "Binary: $(NATIVE_BIN)"

.PHONY: native-probe
native-probe: native-build ## Native: F.0-Sonden im Container gegen das Binary fahren.
	$(DOCKER) run --rm --entrypoint /src/scripts/native-probe.sh $(NATIVE_IMAGE_TAG)
