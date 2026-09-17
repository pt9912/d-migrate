#!/usr/bin/env bash
# Hin-und-Her-Migrationen ueber **alle fuenf Dialekte**, gegen das echte
# `d-migrate:dev`-Image und echte Server.
#
# Plan: docs/planning/done/mcp-real-e2e-scope-matrix.md Teil B
#
# **Warum dieses Skript neben dem Scope-Smoke steht.** `smoke-scope-matrix.sh`
# prueft MCP-Scopes gegen *eine* Postgres-Verbindung; `examples/sample-db/`
# prueft Cross-Dialekt-Migrationen per CLI, aber ohne die Dialekt-Matrix des
# Konsumenten-Setups. Dieses Skript schliesst die Luecke dazwischen: der ganze
# Weg Schema -> generate -> anwenden -> reverse -> compare, je Dialekt.
# Die volle 5x5-Matrix ueber MCP faehrt `smoke-compare-matrix.sh`.
#
# **Warum die CLI und nicht MCP.** Migrationen brauchen ein „DDL anwenden";
# die MCP-Tools kennen das nicht (sie arbeiten ueber Artefakte und Jobs).
# Fuer die Schema-**Qualitaet** ist die Ebene gleichgueltig — geprueft wird
# das Image und der Server, nicht das Transportmittel.
#
# **Was es verbietet** (versionsunabhaengig, `lib/compare-guards.sh`): einen
# Fund, der nur Schreibweise ist (CHECK, Index-Praedikat, Fremdschluessel),
# einen Name- oder Versionsfund (die Quelle ist eine Datei, die andere Seite
# ein Reverse), einen Erzeugungs-Fund, der nur am Sequenznamen haengt, und
# eine Ausgabe, deren Form die Waechter nicht lesen koennen (Selbstprobe).
# Die Zahl der Funde je Dialekt zeigt es, pinnt sie aber nicht; sie kommt aus
# dem JSON-Dokument, nicht aus der Textausgabe.
#
# Ausgabe: out/roundtrip/<dialekt>/ mit den erzeugten Schemata, Skripten,
# Reports und dem Vergleich als Text und als JSON. Exit 0, wenn alle Pfade
# durchlaufen und kein Waechter anschlaegt.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$EXAMPLES_DIR/out"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
WITH_ORACLE="${MCP_E2E_WITH_ORACLE:-0}"

log()  { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { printf '\nFAIL: %s\n' "$*" >&2; exit 1; }

# shellcheck source=lib/dialects.sh
. "$SCRIPT_DIR/lib/dialects.sh"
# shellcheck source=lib/compare-guards.sh
. "$SCRIPT_DIR/lib/compare-guards.sh"

cd "$EXAMPLES_DIR" || exit 1

# --- 0. .env + Stack ---------------------------------------------------
mcp_e2e_env
[ "$WITH_ORACLE" = "1" ] && log "Oracle aktiviert (Kaltstart 2-3 Minuten)"
log "starting: $(dialect_list)"
mcp_e2e_stack_up
log "all healthy"

# --- 1. Das Schema -----------------------------------------------------
# Ein kleines Shop-Schema mit den Konstrukten, an denen die Dialekte
# auseinanderlaufen. Es ist bewusst **nicht** das Konsumenten-Schema, sondern
# das Minimum, das dieselben Kanten trifft:
#
# - Enum-Typ, berechnete Spalte, Autowert und Identity-Spalte, benanntes
#   UNIQUE, Fremdschluessel, Index, Sicht;
# - die Konstrukte des Compare-Slices (2026-09-17): ein CHECK mit `OR` und
#   `IS NULL`, ein CHECK mit Werteliste an einer `varchar`-Spalte (PostgreSQL
#   liest ihn mit Cast zurueck), ein `numeric`-CHECK `> 0`, ein LIKE-CHECK und
#   ein Index mit Praedikat.
#
# `uq_customer_external_ref` (ungebundene `text`-Spalte) und die Sicht tragen
# zwei Konsumentenbefunde vom 2026-09-15; was sie melden, ist gemessen und in
# der README benannt — nicht unterdrueckt.
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
      # text OHNE max_length: Oracle fuehrt das als unkeyable (isUnkeyable),
      # MySQL nimmt es ohne Praefix-Laenge nicht als Schluessel (W125).
      external_ref: { type: text, required: true, unique: true, unique_constraint: uq_customer_external_ref }
      segment: { type: text, max_length: 20, required: true }
    primary_key: [id]
    constraints:
      - name: ck_customer_email_shape
        type: check
        expression: "email LIKE '%@%'"
      - name: ck_customer_segment
        type: check
        expression: "segment IN ('retail', 'business')"
  orders:
    columns:
      id: { type: identifier, auto_increment: true }
      customer_id: { type: integer, required: true }
      status: { type: enum, ref_type: order_status, required: true }
      placed_at: { type: datetime, required: true }
      shipped_at: { type: datetime }
    primary_key: [id]
    indices:
      - name: ix_orders_open
        columns: [customer_id]
        where: "shipped_at IS NULL"
    constraints:
      - name: fk_orders_customer
        type: foreign_key
        columns: [customer_id]
        references: { table: customers, columns: [id] }
      - name: ck_orders_ship_after_place
        type: check
        expression: "shipped_at IS NULL OR shipped_at >= placed_at"
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
      - name: fk_items_order
        type: foreign_key
        columns: [order_id]
        references: { table: orders, columns: [id] }
      - name: ck_items_quantity
        type: check
        expression: "quantity > 0"
      - name: ck_items_price
        type: check
        expression: "unit_price > 0"
  shipments:
    columns:
      id:
        type: biginteger
        required: true
        generation: { type: identity, mode: by_default }
      order_id: { type: integer, required: true }
    primary_key: [id]
    constraints:
      - name: fk_shipments_order
        type: foreign_key
        columns: [order_id]
        references: { table: orders, columns: [id] }
views:
  # Der Rumpf ist bewusst in PostgreSQL-Schreibweise und unquotiert — genau
  # die Form, in der zwei Reverses bis auf die Spalten-Metadaten
  # uebereinstimmen.
  order_summary:
    query: "SELECT o.id AS order_id, c.email FROM orders o JOIN customers c ON c.id = o.customer_id"
YAML

# --- 2. Die Wege -------------------------------------------------------
# Ein d-migrate-Lauf im Container. Die Verbindungen kommen aus
# .d-migrate.yaml; die credentialRef zeigt auf die Umgebungsvariablen, die
# der Compose-Service setzt. Der Verbindungsname geht **ohne** Praefix an
# `--source`: jeder Wert ohne `://` wird in `database.connections`
# nachgeschlagen.
dmi() {
    mcp_e2e_compose run --rm -T dmigrate --config /work/.d-migrate.yaml "$@"
}

summary=""
violations=""
for dialect in $(dialect_list); do
    conn="$(dialect_connection "$dialect")"
    dir="$OUT_DIR/roundtrip/$dialect"
    mkdir -p "$dir"
    log "=== $dialect ==="

    # 2a. Hin: Quellschema -> Dialekt
    dmi schema generate --source /work/out/roundtrip/source.yaml --target "$dialect" \
        --output "/work/out/roundtrip/$dialect/generated.sql" --deterministic \
        > "$dir/generate.log" 2>&1
    gen_exit=$?
    log "generate exit=$gen_exit (erwartet: 0 oder 8 bei uebersprungenen Objekten)"
    case "$gen_exit" in
        0|8) [ -s "$dir/generated.sql" ] || fail "$dialect: generate schrieb keine DDL, siehe $dir/generate.log" ;;
        *) fail "$dialect: generate scheiterte ($gen_exit), siehe $dir/generate.log" ;;
    esac

    # 2b. Aufraeumen und anwenden — ueber den Dialekt-Client, nicht ueber
    # d-migrate (lib/dialects.sh).
    dialect_clean "$dialect"
    dialect_apply "$dialect" "$dir/generated.sql" "$dir/apply.log" \
        || fail "$dialect: DDL wurde abgelehnt, siehe $dir/apply.log"

    # 2c. Her: zuruecklesen und gegen die Quelle stellen.
    # `--include-views` ist **Pflicht**: ohne es liest der Reverse die Sichten
    # nicht zurueck, und der Vergleich meldete „Views removed" — eine Aussage
    # ueber den Leser, nicht ueber das Schema.
    dmi schema reverse --source "$conn" --include-views \
        --output "/work/out/roundtrip/$dialect/reversed.yaml" \
        > "$dir/reverse.log" 2>&1 || fail "$dialect: reverse scheiterte, siehe $dir/reverse.log"

    dmi schema compare --source /work/out/roundtrip/source.yaml \
        --target "/work/out/roundtrip/$dialect/reversed.yaml" \
        > "$dir/compare.txt" 2>&1
    cmp_exit=$?
    if [ "$cmp_exit" = 3 ] && known_introducer_invalid "$dir/compare.txt"; then
        # Bekannter Reader-Befund (lib/compare-guards.sh): das Reverse ist
        # ungueltig, ein Vergleich findet nicht statt. Sobald der Reader den
        # Introducer streicht, verschwindet diese Zeile — dann misst der Lauf
        # auch diesen Dialekt.
        log "$dialect: Reverse ungueltig (E012, MySQL-Introducer — bekannter Reader-Befund)"
        summary="${summary}${dialect}|ungueltig (E012, Introducer)"$'\n'
        continue
    fi
    [ "$cmp_exit" -le 1 ] || fail "$dialect: compare scheiterte ($cmp_exit), siehe $dir/compare.txt"
    dmi --output-format json schema compare --source /work/out/roundtrip/source.yaml \
        --target "/work/out/roundtrip/$dialect/reversed.yaml" \
        > "$dir/compare.json" 2> "$dir/compare-json.log"
    jq -e . "$dir/compare.json" > /dev/null || fail "$dialect: kein JSON-Vergleich, siehe $dir/compare-json.log"

    # 2d. Die Fehlalarme, die **nicht** auftauchen duerfen — als Klasse, auf
    # der strukturierten Ausgabe (lib/compare-guards.sh). Die Vereinheitlichung
    # prueft zuerst, dass sie das Dokument ganz versteht; ein jq-Fehler ist ein
    # Fehlschlag, kein „nichts gefunden".
    compare_guard_items_from_cli "$dir/compare.json" > "$dir/compare-guard-items.json" 2> "$dir/compare-guard.err" \
        || fail "$dialect: Vergleich nicht auswertbar: $(cat "$dir/compare-guard.err")"
    found="$(compare_guard_violations "$dir/compare-guard-items.json" 2>> "$dir/compare-guard.err")" \
        || fail "$dialect: Waechter nicht auswertbar: $(cat "$dir/compare-guard.err")"

    # Die Zahl kommt aus dem Dokument selbst (die Summe von `summary`, die die
    # Selbstprobe eben gegen `diff` gehalten hat), nicht aus der Textausgabe.
    changes="$(jq '[.summary[]] | add // 0' "$dir/compare.json")" \
        || fail "$dialect: keine Fundzahl im Vergleich, siehe $dir/compare.json"
    log "$dialect: $changes Fund(e)"
    summary="${summary}${dialect}|${changes}"$'\n'

    if [ -n "$found" ]; then
        violations="${violations}${dialect}: ${found//$'\n'/$'\n'"$dialect: "}"$'\n'
        printf '\nFAIL: %s meldet Fehlalarme:\n%s\n' "$dialect" "$found" >&2
    fi
done

log "=== Ergebnis ==="
printf '%-12s %s\n' "Dialekt" "Funde"
printf '%s\n' "$summary" | while IFS='|' read -r d n; do
    [ -n "$d" ] && printf '%-12s %s\n' "$d" "$n"
done
log "Artefakte in $OUT_DIR/roundtrip/"
[ -z "$violations" ] || fail "Waechter angeschlagen:"$'\n'"$violations"
log "OK"
