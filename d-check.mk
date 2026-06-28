# d-check.mk — erzeugt von: d-check --print-mk (DC-FA-CLI-010).
#
# Einbinden: "include d-check.mk" im eigenen Makefile; eine eigene
# .d-check.yml danebenlegen. Keine Recipe-/Skript-Kopie — der Image-Pin
# lebt in d-check.
#
# Für strikte Reproduzierbarkeit den Digest aus den Release-Notes pinnen —
# direkt über DCHECK_IMAGE oder bequemer über DCHECK_DIGEST (sticht den Tag):
#   DCHECK_DIGEST = sha256:<digest>
DCHECK_IMAGE ?= ghcr.io/pt9912/d-check:v0.30.0
DCHECK_DIGEST ?=
# TRACE_FLAGS: optionale Flags für die RTM-Targets (z. B. --json).
TRACE_FLAGS ?=

# Ein gesetzter DCHECK_DIGEST sticht den Tag von DCHECK_IMAGE.
ifeq ($(strip $(DCHECK_DIGEST)),)
DCHECK_REF := $(DCHECK_IMAGE)
else
DCHECK_REF := ghcr.io/pt9912/d-check@$(DCHECK_DIGEST)
endif

.PHONY: doc-check
doc-check: ## Doku-Referenzen prüfen (Befund-Gate)
	docker run --rm --network none -v "$(CURDIR):/repo:ro" $(DCHECK_REF)

.PHONY: doc-trace
doc-trace: ## Requirements Traceability Matrix auf stdout (advisory, DC-FA-CLI-009)
	docker run --rm --network none -v "$(CURDIR):/repo:ro" $(DCHECK_REF) --trace $(TRACE_FLAGS)

.PHONY: doc-complete
doc-complete: ## Vollständigkeits-Gate: Requirements-Waise ⇒ Exit 1 (DC-FA-CLI-011)
	docker run --rm --network none -v "$(CURDIR):/repo:ro" $(DCHECK_REF) --trace --require-complete $(TRACE_FLAGS)

.PHONY: doc-doctor
doc-doctor: ## erklärende Diagnose mit Fix-Kandidaten (DC-FA-CLI-007)
	docker run --rm --network none -v "$(CURDIR):/repo:ro" $(DCHECK_REF) --doctor

.PHONY: doc-repair
doc-repair: ## Reparatur-Patch (unified diff) auf stdout, git-apply-rein (DC-FA-CLI-008)
	@docker run --rm --network none -v "$(CURDIR):/repo:ro" $(DCHECK_REF) --repair

.PHONY: doc-help
doc-help: ## diese Liste der doc-*-Targets
	@grep -hE '^doc-[a-z-]+:.*## ' $(MAKEFILE_LIST) | sort | sed -E 's/:.*## /  /'
