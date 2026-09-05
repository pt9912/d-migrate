# a-check.mk — Architektur-Gate via a-check, zum `include` in das
# Makefile des konsumierenden Repos. Erzeugt von `a-check --print-mk`.
#
# Benutzerhandbuch (aufgabenorientiert, deutsch):
#   https://github.com/pt9912/a-check/blob/main/docs/user/benutzerhandbuch.md
#
# PFLICHT VOR DEM ERSTEN LAUF: A_CHECK_IMAGE auf den Release-Digest setzen.
# Der Platzhalter unten ist KEIN gueltiger Verweis — `make a-check` bricht
# damit ab. Das ist Absicht: a-check kann den Digest seines eigenen Image nicht
# kennen (er entsteht erst beim Push), und ein eingebackener Wert naehme immer
# den des VORGAENGER-Release — gueltig aussehend und falsch (ADR-0030).
#
# Den Digest des Release, aus dem dieses Fragment stammt, liefert:
#   - die Release-Notes auf GitHub, oder
#   - `docker image inspect --format '{{index .RepoDigests 0}}' <image>:<tag>`
#     auf dem Host, der das Image gezogen hat.
# Die Pin-Hebung ist ein bewusster Commit (AC-QA-03).
A_CHECK_IMAGE ?= ghcr.io/pt9912/a-check@sha256:34d3dfb50e44d99ea735186a35e1040589c4681dcfa2a51ed0f2aaea718cdd2d

# Container-Runtime ueber eine Indirektion, damit ein Repo mit podman/nerdctl
# oder einem docker-Wrapper nicht die Haelfte seiner Targets anders faehrt als
# die andere (slice-082).
#
# REIHENFOLGE ZAEHLT: `?=` setzt nur, wenn DOCKER noch nicht belegt ist.
# Wer eine eigene Runtime nutzt, definiert sie VOR dem `include` — oder
# hart (`DOCKER = podman`). Ein `DOCKER ?= podman` NACH dem
# include greift nicht mehr, weil dieses Fragment die Variable dann schon
# gesetzt hat.
DOCKER ?= docker

.PHONY: a-check a-check-graph
a-check: ## Architektur: Hexagon-Regeln via a-check (netzlos, read-only).
	$(DOCKER) run --rm --network none -v "$(CURDIR)":/src:ro $(A_CHECK_IMAGE) /src

a-check-graph: ## Architektur-Graph (Mermaid) aus .a-check.yml auf stdout (read-only, kein Scan).
	$(DOCKER) run --rm --network none -v "$(CURDIR)":/src:ro $(A_CHECK_IMAGE) --print-graph /src
