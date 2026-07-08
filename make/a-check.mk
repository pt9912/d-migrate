# a-check.mk — Architektur-Gate via a-check, zum `include` in das
# Makefile des konsumierenden Repos. Erzeugt von `a-check --print-mk`.
#
# A_CHECK_IMAGE ist auf den v0.12.0-Release digest-gepinnt (a-check version.md#aktuell).
# Pin-Hebung ist ein bewusster Commit.
A_CHECK_IMAGE ?= ghcr.io/pt9912/a-check@sha256:203df7ab02ec68db5f77f77660fe12523dad9fd48a6c84b95aabb080ec30de24

.PHONY: a-check
a-check: ## Architektur: Hexagon-Regeln via a-check (netzlos, read-only).
	docker run --rm --network none -v "$(CURDIR)":/src:ro $(A_CHECK_IMAGE) /src
