#!/usr/bin/env bash
# MCP-E2E-Harness — `mcp serve` mit `server.state` (JDBC) gegen ein echtes PostgreSQL.
#
# Warum es das gibt: Die Scope-, Roundtrip- und Compare-Matrix laufen mit dem Zustandsspeicher im Speicher.
# Mit `server.state` laeuft eine zweite Schicht an: Flyway legt das Schema an, und Jackson (mit Kotlin-Modul)
# liest und schreibt die Datensaetze. Beides kam im nativen Image erst am Aufrufort zum Scheitern (Issue #84):
# Flyway fand `db/migration` nicht, die Store-Tools antworteten mit `INTERNAL_AGENT_ERROR`. Auf der JVM ist
# beides unauffaellig, ein Exit-Code-Test sah es nie.
#
# Ablauf (ein Image, wie es MCP_E2E_DMIGRATE_IMAGE nennt):
#   1. leere State-DB: der Server startet, Flyway hat die Tabellen angelegt (nicht nur die History-Tabelle)
#   2. die drei Listen (schema_list, job_list, artifact_list) antworten ohne Fehler
#   3. schema_reverse_start legt einen Job an; er endet mit SUCCEEDED, Job, Schema und Artefakt stehen in den Listen
#   4. Neustart gegen dieselbe, nun gefuellte State-DB: die Listen lesen die Datensaetze und nennen sie
#
# Aufruf: scripts/smoke-server-state.sh
# Voraussetzung am Host: docker, curl, jq und das Image (`make docker-build IMAGE_TAG=dev` oder ein natives).
# Belegt die Ports MCP_E2E_STATE_PG_PORT (5499) und MCP_E2E_STATE_HTTP_PORT (8797) auf dem Host.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE="${MCP_E2E_DMIGRATE_IMAGE:-d-migrate:dev}"
PG_PORT="${MCP_E2E_STATE_PG_PORT:-5499}"
HTTP_PORT="${MCP_E2E_STATE_HTTP_PORT:-8797}"
# Dasselbe PostgreSQL wie der Rest des Harness (docker-compose.yml), damit es nur eine Pin-Stelle gibt.
PG_IMAGE="$(awk '/image: postgis\/postgis/ {print $2; exit}' "$EXAMPLES_DIR/docker-compose.yml")"
PG_NAME="mcp-e2e-state-pg"
MCP_NAME="mcp-e2e-state-mcp"
URL="http://127.0.0.1:${HTTP_PORT}/mcp"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
FAILURES=()
note_failure() { FAILURES+=("$*"); log "FEHLER: $*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

for tool in docker curl jq; do command -v "$tool" > /dev/null || fail "$tool fehlt am Host"; done
[ -n "$PG_IMAGE" ] || fail "kein postgis-Image in docker-compose.yml gefunden"

WORK="$(mktemp -d)"
cleanup() {
    docker rm -f "$MCP_NAME" "$PG_NAME" > /dev/null 2>&1
    rm -rf "$WORK"
}
trap cleanup EXIT

cat > "$WORK/config.yaml" << YAML
database:
  connections:
    src:
      displayName: "state smoke source"
      dialectId: postgresql
      sensitivity: NON_PRODUCTION
      credentialRef: "env:STATE_SMOKE_SRC_URL"
server:
  state:
    jdbcUrl: "jdbc:postgresql://127.0.0.1:${PG_PORT}/state?currentSchema=dmigrate_state"
    username: "smoke"
    password: "smoke"
    migrations:
      auto: true
YAML
printf 'rules:\n  - tenantId: default\n    toolName: schema_reverse_start\n    effect: allow\n' > "$WORK/policy.yaml"
# Der Server loggt die Ursache eines INTERNAL_AGENT_ERROR nur auf DEBUG. Ohne diese Datei bliebe ein Befund
# dieses Skripts auf der JVM ohne jede Spur (Nebenbefund aus Issue #84). Im Native-Image wirkt sie NICHT:
# Logback wird dort beim Bau initialisiert (`--initialize-at-build-time`), ein Befund bleibt nativ ohne Ursache.
cat > "$WORK/logback.xml" << 'XML'
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder><pattern>%-5level %logger{36} - %msg%n%ex{full}</pattern></encoder>
  </appender>
  <logger name="dev.dmigrate.mcp.protocol.McpServiceImpl" level="DEBUG"/>
  <root level="WARN"><appender-ref ref="STDERR"/></root>
</configuration>
XML
# Das Image laeuft als uid 10001 und muss die Dateien lesen koennen.
chmod 755 "$WORK"; chmod 644 "$WORK/config.yaml" "$WORK/policy.yaml" "$WORK/logback.xml"

# System-Properties erreichen das JVM-Startskript ueber JAVA_OPTS, das native Binary nur als Argument.
LOGBACK_PROP="-Dlogback.configurationFile=/work/logback.xml"
LOG_ENV=()
LOG_ARGS=()
if docker run --rm --entrypoint sh "$IMAGE" -c 'test -d /opt/d-migrate/lib' > /dev/null 2>&1; then
    LOG_ENV=(-e "JAVA_OPTS=$LOGBACK_PROP")
else
    LOG_ARGS=("$LOGBACK_PROP")
fi

# Die Ursachen aus dem Serverlog, je Befund einmal vor dem naechsten Neustart.
server_log() {
    docker logs "$MCP_NAME" 2>&1 | grep -E -A30 'failed before the trust boundary|^Exception|ERROR|Unable to' | head -60 | sed 's/^/    | /'
}

psql_() { docker exec "$PG_NAME" psql -U smoke -d state -qtAc "$1"; }

H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
H2=()

# Startet den Server neu und fuehrt den MCP-Handshake aus. Setzt H2 (Sitzungs-Header).
serve() {
    docker rm -f "$MCP_NAME" > /dev/null 2>&1
    docker run -d --name "$MCP_NAME" --network host \
        -e "STATE_SMOKE_SRC_URL=postgresql://smoke:smoke@127.0.0.1:${PG_PORT}/state" \
        "${LOG_ENV[@]}" \
        -v "$WORK/config.yaml:/work/config.yaml:ro" -v "$WORK/policy.yaml:/work/policy.yaml:ro" \
        -v "$WORK/logback.xml:/work/logback.xml:ro" \
        "$IMAGE" "${LOG_ARGS[@]}" mcp serve --transport=http --bind=127.0.0.1 --port="$HTTP_PORT" \
        --auth-mode=disabled --connection-config=/work/config.yaml --policy-file=/work/policy.yaml > /dev/null
    local up=false
    for _ in $(seq 40); do
        if curl -s -o /dev/null "$URL"; then up=true; break; fi
        [ "$(docker inspect -f '{{.State.Running}}' "$MCP_NAME" 2> /dev/null)" = true ] || break
        sleep 1
    done
    $up || return 1
    local sid
    sid="$(curl -s -D - -o /dev/null "${H[@]}" "$URL" \
        -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"state-smoke","version":"1"}}}' \
        | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id" {print $2}')"
    [ -n "$sid" ] || return 1
    H2=("${H[@]}" -H "MCP-Session-Id: $sid" -H 'MCP-Protocol-Version: 2025-11-25')
    curl -s -o /dev/null "${H2[@]}" "$URL" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
}

# tool NAME ARGS -> gibt die Antwort aus. Der Aufrufer prueft mit tool_ok.
tool() {
    curl -s "${H2[@]}" "$URL" \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"
}
# tool_ok: 0, wenn die Antwort ein Ergebnis ohne isError ist; sonst Fehlercode auf stdout.
tool_ok() {
    local reply
    reply="$(tool "$1" "$2")"
    if [ "$(jq -r '.result.isError == false' <<< "$reply" 2> /dev/null)" = true ]; then
        return 0
    fi
    jq -r '(.result.content[0].text | fromjson? | .code) // .error.message // "keine Antwort"' <<< "$reply" 2> /dev/null \
        || echo "keine Antwort"
    return 1
}

check_lists() {
    local phase="$1" tool_name code
    for tool_name in schema_list job_list artifact_list; do
        if code="$(tool_ok "$tool_name" '{}')"; then
            log "  $tool_name ok"
        else
            note_failure "$phase: $tool_name -> $code"
        fi
    done
}

# Die drei Listen nennen nach einem abgeschlossenen Reverse je mindestens einen Datensatz. Leere Listen waeren
# auch ohne Lesen gruen: erst ein Eintrag zwingt den Server, ihn aus der State-DB zu deserialisieren.
check_records() {
    local phase="$1" pair tool_name key count
    for pair in job_list:jobs schema_list:schemas artifact_list:artifacts; do
        tool_name="${pair%%:*}"
        key="${pair##*:}"
        count="$(tool "$tool_name" '{}' | jq -r --arg k "$key" '.result.content[0].text | fromjson? | (.[$k] // []) | length' 2> /dev/null)"
        if [ "${count:-0}" -ge 1 ]; then
            log "  $tool_name nennt $count Eintrag/Eintraege"
        else
            note_failure "$phase: $tool_name nennt keinen gespeicherten Eintrag"
        fi
    done
}

log "Image $IMAGE, PostgreSQL $PG_IMAGE"
docker rm -f "$MCP_NAME" "$PG_NAME" > /dev/null 2>&1
docker run -d --name "$PG_NAME" -e POSTGRES_USER=smoke -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=state \
    -p "127.0.0.1:${PG_PORT}:5432" "$PG_IMAGE" > /dev/null || fail "PostgreSQL startet nicht"
for _ in $(seq 60); do
    docker exec "$PG_NAME" pg_isready -h 127.0.0.1 -U smoke -d state > /dev/null 2>&1 && break
    sleep 2
done
psql_ 'create table public.t(id serial primary key, v text)' > /dev/null || fail "Quelltabelle nicht anlegbar"

log "== 1. leere State-DB: der Server startet und Flyway legt das Schema an"
if serve; then
    migrated="$(psql_ "select count(*) from information_schema.tables where table_schema = 'dmigrate_state' and table_name <> 'flyway_server_state_history'")"
    if [ "${migrated:-0}" -ge 1 ]; then
        log "  Flyway hat $migrated Tabellen angelegt"
    else
        note_failure "1: Flyway legte keine Tabelle an (nur die History-Tabelle): Migrationen nicht gefunden"
    fi
else
    note_failure "1: der Server startet nicht gegen eine leere State-DB"
    docker logs "$MCP_NAME" 2>&1 | tail -15 | sed 's/^/    | /'
fi

if [ ${#FAILURES[@]} -eq 0 ]; then
    log "== 2. die Listen antworten ohne Fehler"
    check_lists "2"

    log "== 3. schema_reverse_start legt einen Job an, der Datensaetze schreibt"
    if code="$(tool_ok schema_reverse_start '{"connectionId":"dmigrate://tenants/default/connections/src","idempotencyKey":"state-smoke-1"}')"; then
        status=""
        for _ in $(seq 60); do
            status="$(psql_ 'select status from dmigrate_state.jobs limit 1' 2> /dev/null)"
            case "${status^^}" in SUCCEEDED|FAILED|CANCELLED) break ;; esac
            sleep 1
        done
        if [ "${status^^}" = SUCCEEDED ]; then
            log "  Job in der State-DB: $status"
            check_records "3"
        else
            note_failure "3: der Job endet nicht mit SUCCEEDED (Status: ${status:-keine Zeile})"
        fi
    else
        note_failure "3: schema_reverse_start -> $code"
    fi

    [ ${#FAILURES[@]} -eq 0 ] || server_log
    log "== 4. Neustart gegen die gefuellte State-DB: die Listen lesen die Datensaetze"
    if serve; then
        check_lists "4"
        check_records "4"
    else
        note_failure "4: der Server startet nicht gegen die gefuellte State-DB"
        docker logs "$MCP_NAME" 2>&1 | tail -15 | sed 's/^/    | /'
    fi
fi

if [ ${#FAILURES[@]} -gt 0 ]; then
    server_log
    printf '\nFAIL: %d Befund(e)\n' "${#FAILURES[@]}" >&2
    printf '  - %s\n' "${FAILURES[@]}" >&2
    exit 1
fi
log "OK — server.state unter $IMAGE: Migration, Listen, Job anlegen, Neustart"
