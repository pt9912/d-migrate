#!/usr/bin/env bash
# Hin-und-Her-Migrationen ueber **alle fuenf Dialekte**, gegen das echte
# `d-migrate:dev`-Image und echte Server.
#
# Plan: docs/planning/next/mcp-real-e2e-scope-matrix.md Teil B
#
# **Warum dieses Skript neben dem Scope-Smoke steht.** `smoke-scope-matrix.sh`
# prueft MCP-Scopes gegen *eine* Postgres-Verbindung; `examples/sample-db/`
# prueft Cross-Dialekt-Migrationen per CLI, aber ohne die Dialekt-Matrix des
# Konsumenten-Setups. Diese Spec schliesst die Luecke dazwischen: dieselbe
# Matrix, die ein Konsumentenprojekt faehrt, und der ganze Weg
# Schema -> generate -> anwenden -> reverse -> compare — in **beide**
# Richtungen.
#
# **Warum die CLI und nicht MCP.** Migrationen brauchen ein „DDL anwenden";
# die MCP-Tools kennen das nicht (sie arbeiten ueber Artefakte und Jobs).
# Fuer die Schema-**Qualitaet** ist die Ebene gleichgueltig — geprueft wird
# das Image und der Server, nicht das Transportmittel. Der MCP-Weg bleibt
# beim Scope-Smoke.
#
# Ausgabe: out/roundtrip/<dialekt>/ mit den erzeugten Schemata, Skripten und
# Reports. Der Exit-Code ist 0, wenn alle Pfade die erwarteten Funde zeigen.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$EXAMPLES_DIR/out"

COMPOSE_IMAGE="${MCP_E2E_DMIGRATE_IMAGE:-d-migrate:dev}"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
WITH_ORACLE="${MCP_E2E_WITH_ORACLE:-0}"

log()  { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { printf '\nFAIL: %s\n' "$*" >&2; exit 1; }

cd "$EXAMPLES_DIR"

# --- 0. .env + Stack ---------------------------------------------------
mkdir -p "$OUT_DIR"
[ -f "$EXAMPLES_DIR/.env" ] || { cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"; log "created .env from .env.example"; }
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${MCP_E2E_PG_USER:?MCP_E2E_PG_USER not set}"

export MCP_E2E_DMIGRATE_USER="$(id -u):$(id -g)"

services="postgres mysql mssql"
profiles=()
if [ "$WITH_ORACLE" = "1" ]; then
    services="$services oracle"
    profiles=(--profile oracle)
    log "Oracle aktiviert (Kaltstart 2-3 Minuten)"
fi

log "starting: $services"
docker compose -f "$COMPOSE_FILE" "${profiles[@]}" up -d $services || fail "stack did not start"

log "waiting for healthy (timeout 240s)..."
deadline=$(($(date +%s) + 240)); ok="no"
while [ "$(date +%s)" -lt "$deadline" ]; do
    unhealthy=$(docker compose -f "$COMPOSE_FILE" "${profiles[@]}" ps --format '{{.Service}} {{.Health}}' \
        | awk '$2 != "healthy" {print $1}' | grep -v '^$' || true)
    if [ -z "$unhealthy" ]; then ok="yes"; break; fi
    sleep 5
done
[ "$ok" = "yes" ] || fail "not all services became healthy"
log "all healthy"

# --- 1. Das Schema -----------------------------------------------------
# Ein kleines Shop-Schema mit den Konstrukten, an denen die Dialekte
# auseinanderlaufen: Enum-Typ, berechnete Spalte, Identity, CHECK,
# benanntes UNIQUE, Fremdschluessel, Index. Es ist bewusst **nicht** das
# Konsumenten-Schema, sondern das Minimum, das dieselben Kanten trifft.
SCHEMA="$OUT_DIR/roundtrip/source.yaml"
mkdir -p "$OUT_DIR/roundtrip"
cat > "$SCHEMA" <<'YAML'
schema_format: "1.0"
name: roundtrip_probe
version: 1.0.0
custom_types:
  order_status:
    kind: enum
    values: [NEW, PAID, SHIPPED, CANCELLED]
tables:
  customers:
    columns:
      id: { type: identifier, auto_increment: true }
      email: { type: text, max_length: 255, required: true, unique: true, unique_constraint: uq_customer_email }
    primary_key: [id]
  orders:
    columns:
      id: { type: identifier, auto_increment: true }
      customer_id: { type: integer, required: true }
      status: { type: enum, ref_type: order_status, required: true }
    primary_key: [id]
    constraints:
      - name: fk_orders_customer
        type: foreign_key
        columns: [customer_id]
        references: { table: customers, columns: [id] }
  order_items:
    columns:
      id: { type: identifier, auto_increment: true }
      order_id: { type: integer, required: true }
      quantity: { type: integer, required: true }
      unit_price: { type: decimal, precision: 12, scale: 2, required: true }
      line_total:
        type: decimal
        precision: 14
        scale: 2
        generation:
          type: computed
          expression: "quantity * unit_price"
          stored: true
    primary_key: [id]
    constraints:
      - name: ck_items_quantity
        type: check
        expression: "quantity > 0"
YAML

# --- 2. Die Wege -------------------------------------------------------
# Jeder Eintrag: <dialekt>|<verbindungsname aus .d-migrate.yaml>.
# Der Name wird **ohne** Praefix uebergeben: `--source` schlaegt jeden Wert
# ohne `://` in `database.connections` nach — mit `db:` davor suchte er einen
# Eintrag dieses Namens und fand keinen.
TARGETS=(
  "postgresql|mcp_e2e_pg"
  "mysql|mcp_e2e_my"
  "mssql|mcp_e2e_ms"
  "sqlite|mcp_e2e_sqlite"
)
[ "$WITH_ORACLE" = "1" ] && TARGETS+=("oracle|mcp_e2e_ora")

# Ein d-migrate-Lauf im Container. Die Verbindungen kommen aus
# .d-migrate.yaml; die credentialRef zeigt auf die Umgebungsvariablen, die
# der Compose-Service setzt.
dmi() {
    docker compose -f "$COMPOSE_FILE" "${profiles[@]}" run --rm -T dmigrate \
        --config /work/.d-migrate.yaml "$@"
}

summary=""
for target in "${TARGETS[@]}"; do
    dialect="${target%%|*}"; conn="${target#*|}"
    dir="$OUT_DIR/roundtrip/$dialect"
    mkdir -p "$dir"
    log "=== $dialect ==="

    # 2a. Hin: Quellschema -> Dialekt
    dmi schema generate --source /work/out/roundtrip/source.yaml --target "$dialect" \
        --output "/work/out/roundtrip/$dialect/generated.sql" --deterministic \
        > "$dir/generate.log" 2>&1
    gen_exit=$?
    log "generate exit=$gen_exit (erwartet: 0 oder 8 bei uebersprungenen Objekten)"
    [ "$gen_exit" -le 8 ] || fail "$dialect: generate scheiterte ($gen_exit), siehe $dir/generate.log"

    # 2b-0. Aufraeumen. Das Skript ist wiederholbar: ein zweiter Lauf faende
    # sonst das Schema des ersten und die DDL scheiterte an "already exists"
    # (gemessen: `type "order_status" already exists`).
    case "$dialect" in
        postgresql)
            docker exec -i "$(docker compose -f "$COMPOSE_FILE" ps -q postgres)" \
                psql -U "$MCP_E2E_PG_USER" -d "$MCP_E2E_PG_DB" -q \
                -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;' > /dev/null 2>&1 || true
            ;;
        mysql)
            docker exec -i "$(docker compose -f "$COMPOSE_FILE" ps -q mysql)" \
                mysql -uroot -p"$MCP_E2E_MY_ROOT_PASSWORD" -e \
                "DROP DATABASE IF EXISTS \`$MCP_E2E_MY_DB\`; CREATE DATABASE \`$MCP_E2E_MY_DB\`;" > /dev/null 2>&1 || true
            ;;
        mssql)
            # Die Datenbank neu anlegen: ein DROP der Tabellen muesste die
            # Fremdschluessel-Reihenfolge treffen, und die kennt das Skript
            # nicht. Die Ziel-DB ist eine Test-DB ohne Daten.
            docker exec "$(docker compose -f "$COMPOSE_FILE" ps -q mssql)" \
                /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MCP_E2E_MS_PASSWORD" -b \
                -Q "IF DB_ID('$MCP_E2E_MS_DB') IS NOT NULL BEGIN ALTER DATABASE [$MCP_E2E_MS_DB] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$MCP_E2E_MS_DB]; END" > /dev/null 2>&1 || true
            docker exec "$(docker compose -f "$COMPOSE_FILE" ps -q mssql)" \
                /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MCP_E2E_MS_PASSWORD" -b \
                -Q "CREATE DATABASE [$MCP_E2E_MS_DB]" > /dev/null 2>&1 || true
            ;;
        sqlite)
            rm -f "$OUT_DIR/mcp-e2e.sqlite"
            ;;
        oracle) : ;;
    esac

    # 2b. Anwenden — ueber den Dialekt-Client, nicht ueber d-migrate.
    # SQLite braucht nichts: `schema reverse` legt die Datei via ?mode=rwc an.
    case "$dialect" in
        postgresql)
            docker exec -i "$(docker compose -f "$COMPOSE_FILE" ps -q postgres)" \
                psql -U "$MCP_E2E_PG_USER" -d "$MCP_E2E_PG_DB" -v ON_ERROR_STOP=1 -q \
                < "$dir/generated.sql" > "$dir/apply.log" 2>&1 \
                || fail "$dialect: DDL wurde vom Server abgelehnt, siehe $dir/apply.log"
            ;;
        mysql)
            docker exec -i "$(docker compose -f "$COMPOSE_FILE" ps -q mysql)" \
                mysql -u"$MCP_E2E_MY_USER" -p"$MCP_E2E_MY_PASSWORD" "$MCP_E2E_MY_DB" \
                < "$dir/generated.sql" > "$dir/apply.log" 2>&1 \
                || fail "$dialect: DDL wurde vom Server abgelehnt, siehe $dir/apply.log"
            ;;
        mssql)
            docker cp "$dir/generated.sql" "$(docker compose -f "$COMPOSE_FILE" ps -q mssql):/tmp/ddl.sql"
            docker exec "$(docker compose -f "$COMPOSE_FILE" ps -q mssql)" \
                /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MCP_E2E_MS_PASSWORD" \
                -d "$MCP_E2E_MS_DB" -b -i /tmp/ddl.sql > "$dir/apply.log" 2>&1 \
                || fail "$dialect: DDL wurde vom Server abgelehnt, siehe $dir/apply.log"
            ;;
        oracle|sqlite) : ;; # Oracle legt per Reverse an; SQLite ohnehin.
    esac

    # 2c. Her: zuruecklesen und gegen die Quelle stellen
    dmi schema reverse --source "$conn" --output "/work/out/roundtrip/$dialect/reversed.yaml" \
        > "$dir/reverse.log" 2>&1 || fail "$dialect: reverse scheiterte, siehe $dir/reverse.log"

    dmi schema compare --source /work/out/roundtrip/source.yaml \
        --target "/work/out/roundtrip/$dialect/reversed.yaml" \
        > "$dir/compare.txt" 2>&1
    changes=$(grep -cE '^      [+~-] ' "$dir/compare.txt" || true)
    log "$dialect: $changes Fund(e)"
    summary="${summary}${dialect}|${changes}"$'\n'

    # 2d. Die Fehlalarme, die **nicht** auftauchen duerfen: Ein benannter
    # Fremdschluessel, der nur wegen der Schreibweise als geaendert gilt.
    if grep -qE '~ constraint .*_fkey.*->' "$dir/compare.txt"; then
        printf '\nFAIL: %s meldet einen unveraenderten Fremdschluessel als geaendert:\n' "$dialect" >&2
        grep -E '~ constraint .*_fkey.*->' "$dir/compare.txt" >&2
        exit 1
    fi
done

log "=== Ergebnis ==="
printf '%-12s %s\n' "Dialekt" "Funde"
printf '%s\n' "$summary" | while IFS='|' read -r d n; do
    [ -n "$d" ] && printf '%-12s %s\n' "$d" "$n"
done
log "Artefakte in $OUT_DIR/roundtrip/"
log "OK"
