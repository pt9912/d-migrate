# ── BI-Demo (examples/bi-demo) ─────────────────────────────────────
#
# Ausgelagert aus dem Haupt-Makefile (per `include make/bi-demo.mk`), damit der
# BI-Demo-Compose-Stack gebündelt an einem Ort liegt.
#
# Kapselt den langen `docker compose -f
# examples/bi-demo/docker-compose.yml ...`-Pfad. Spec siehe
# `docs/planning/in-progress/bi-demo-compose.md`. Voraussetzung
# fuer den `dmigrate`-Service: einmaliger `make docker-build
# IMAGE_TAG=dev` (baut das d-migrate:dev-Runtime-Image).

.PHONY: bi-demo-env bi-demo-pull bi-demo-up bi-demo-down bi-demo-purge bi-demo-smoke

BI_DEMO_COMPOSE := docker compose -f examples/bi-demo/docker-compose.yml

bi-demo-env:
	@if [ ! -f examples/bi-demo/.env ]; then \
	  cp examples/bi-demo/.env.example examples/bi-demo/.env; \
	  echo "[bi-demo] created examples/bi-demo/.env from .env.example"; \
	fi
	@mkdir -p examples/bi-demo/out

bi-demo-pull: bi-demo-env
	$(BI_DEMO_COMPOSE) pull

bi-demo-up: bi-demo-env
	$(BI_DEMO_COMPOSE) up -d

bi-demo-down:
	$(BI_DEMO_COMPOSE) down

bi-demo-purge:
	$(BI_DEMO_COMPOSE) down -v

bi-demo-smoke:
	./examples/bi-demo/scripts/smoke.sh
