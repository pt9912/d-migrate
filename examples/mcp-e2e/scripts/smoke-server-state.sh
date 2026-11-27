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
#   3. schema_reverse_start legt einen Job an; er endet mit SUCCEEDED, Job, Schema und Artefakt stehen in den Listen,
#      und job_list nennt jedes Artefakt als eine URI (Issue #86). Ein hochgeladenes Schema ist ueber seinen
#      schemaRef erreichbar (Issue #85). schema_compare_start und data_profile_start stehen danach in diff_list und
#      profile_list, und eine per job_input hochgeladene CSV-Datei laesst sich mit data_import_start importieren
#      (Issue #87)
#   4. Neustart gegen dieselbe, nun gefuellte State-DB: die Listen lesen die Datensaetze und nennen sie, und die Bytes
#      eines Artefakts sind noch lesbar (neuer Container, gleiches Volume als --mcp-state-dir), ebenso das
#      hochgeladene Schema und die Eintraege in diff_list und profile_list
#   5. ein erzwungener Serverfehler antwortet mit INTERNAL_AGENT_ERROR, und das Serverlog nennt Tool und Ursache
#   6. ohne --mcp-state-dir startet der Server nicht: server.state verlangt einen persistenten Byte-Store
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
UPLOADED_REF=""
MCP_NAME="mcp-e2e-state-mcp"
BYTES_VOLUME="mcp-e2e-state-bytes"
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
    docker volume rm "$BYTES_VOLUME" > /dev/null 2>&1
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
{
    echo 'rules:'
    for allowed in schema_reverse_start schema_compare_start data_profile_start artifact_upload_init data_import_start; do
        printf '  - tenantId: default\n    toolName: %s\n    effect: allow\n' "$allowed"
    done
} > "$WORK/policy.yaml"
# Das Image laeuft als uid 10001 und muss die Dateien lesen koennen.
chmod 755 "$WORK"; chmod 644 "$WORK/config.yaml" "$WORK/policy.yaml"

# Die Ursachen aus dem Serverlog, je Befund einmal vor dem naechsten Neustart.
server_log() {
    docker logs "$MCP_NAME" 2>&1 | grep -E -A30 'failed with INTERNAL_AGENT_ERROR|^Exception|ERROR|Unable to' | head -60 | sed 's/^/    | /'
}

psql_() { docker exec "$PG_NAME" psql -U smoke -d state -qtAc "$1"; }

H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
H2=()

# Startet den Server neu und fuehrt den MCP-Handshake aus. Setzt H2 (Sitzungs-Header).
serve() {
    docker rm -f "$MCP_NAME" > /dev/null 2>&1
    docker run -d --name "$MCP_NAME" --network host \
        -e "STATE_SMOKE_SRC_URL=postgresql://smoke:smoke@127.0.0.1:${PG_PORT}/state" \
        -v "$WORK/config.yaml:/work/config.yaml:ro" -v "$WORK/policy.yaml:/work/policy.yaml:ro" \
        -v "$BYTES_VOLUME:/state" \
        "$IMAGE" mcp serve --transport=http --bind=127.0.0.1 --port="$HTTP_PORT" \
        --auth-mode=disabled --connection-config=/work/config.yaml --policy-file=/work/policy.yaml --mcp-state-dir=/state > /dev/null
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

# job_list nennt jedes Artefakt als eine Artefakt-URI, nicht mit doppeltem Praefix.
check_job_uris() {
    local phase="$1" uris
    uris="$(tool job_list '{}' | jq -r '.result.content[0].text | fromjson? | .jobs[]?.artifactUris[]?' 2> /dev/null)"
    if [ -n "$uris" ] && ! grep -qvE '^dmigrate://tenants/default/artifacts/[^/]+$' <<< "$uris"; then
        log "  job_list nennt einfache Artefakt-URIs"
    else
        note_failure "$phase: job_list.artifactUris ist leer oder keine einfache URI: $(head -1 <<< "$uris")"
    fi
}

# Laedt ein Schema hoch; setzt UPLOADED_REF auf den schemaRef aus der Antwort.
upload_schema() {
    local phase="$1" file="$WORK/upload.json" size sha session reply
    UPLOADED_REF=""
    # Schema-Uploads liest der Server als JSON.
    jq -cn '{schema_format: "1.0", name: "uploaded", version: "1.0.0",
        tables: {u: {columns: {id: {type: "identifier"}}, primary_key: ["id"]}}}' > "$file"
    size="$(wc -c < "$file")"
    sha="$(sha256sum "$file" | cut -d' ' -f1)"
    session="$(tool artifact_upload_init \
        "{\"uploadIntent\":\"schema_staging_readonly\",\"expectedSizeBytes\":$size,\"checksumSha256\":\"$sha\"}" \
        | jq -r '.result.content[0].text | fromjson? | .uploadSessionId // empty' 2> /dev/null)"
    [ -n "$session" ] || { note_failure "$phase: artifact_upload_init liefert keine uploadSessionId"; return; }
    reply="$(tool artifact_upload "{\"uploadSessionId\":\"$session\",\"segmentIndex\":1,\"segmentOffset\":0,\
\"segmentTotal\":1,\"isFinalSegment\":true,\"segmentSha256\":\"$sha\",\"contentBase64\":\"$(base64 -w0 "$file")\"}")"
    UPLOADED_REF="$(jq -r '.result.content[0].text | fromjson? | .schemaRef // empty' <<< "$reply" 2> /dev/null)"
    if [ -n "$UPLOADED_REF" ]; then
        log "  Upload liefert $UPLOADED_REF"
    else
        note_failure "$phase: artifact_upload liefert keinen schemaRef: $(head -c 300 <<< "$reply")"
    fi
}

# Der schemaRef aus dem Upload muss bei den Tools ankommen, die ihn lesen.
check_upload() {
    local phase="$1" code listed
    [ -n "$UPLOADED_REF" ] || return
    if code="$(tool_ok schema_validate "{\"schemaRef\":\"$UPLOADED_REF\"}")"; then
        log "  schema_validate liest das hochgeladene Schema"
    else
        note_failure "$phase: schema_validate auf das hochgeladene Schema -> $code"
    fi
    listed="$(tool schema_list '{}' | jq -r '.result.content[0].text // empty' 2> /dev/null)"
    if grep -qF "$UPLOADED_REF" <<< "$listed"; then
        log "  schema_list fuehrt das hochgeladene Schema"
    else
        note_failure "$phase: schema_list fuehrt das hochgeladene Schema nicht"
    fi
}

# Startet ein Job-Tool und wartet auf sein Ende; 0 nur bei SUCCEEDED.
run_job() {
    local phase="$1" name="$2" args="$3" reply job status=""
    reply="$(tool "$name" "$args")"
    job="$(jq -r '.result.content[0].text | fromjson? | .jobId // empty' <<< "$reply" 2> /dev/null)"
    [ -n "$job" ] || { note_failure "$phase: $name liefert keinen Job: $(head -c 400 <<< "$reply")"; return 1; }
    for _ in $(seq 60); do
        status="$(tool job_status_get "{\"jobId\":\"$job\"}" \
            | jq -r '.result.content[0].text | fromjson? | .status // empty' 2> /dev/null)"
        case "$status" in SUCCEEDED) log "  $name: SUCCEEDED"; return 0 ;; FAILED|CANCELLED) break ;; esac
        sleep 1
    done
    note_failure "$phase: $name endet nicht mit SUCCEEDED: $(tool job_status_get "{\"jobId\":\"$job\"}" | head -c 400)"
    return 1
}

# Die Ergebnisse der beiden Jobs stehen in ihren Indizes.
check_indices() {
    local phase="$1" pair tool_name key count
    for pair in diff_list:diffs profile_list:profiles; do
        tool_name="${pair%%:*}"
        key="${pair##*:}"
        count="$(tool "$tool_name" '{}' | jq -r --arg k "$key" '.result.content[0].text | fromjson? | (.[$k] // []) | length' 2> /dev/null)"
        if [ "${count:-0}" -ge 1 ]; then
            log "  $tool_name nennt $count Eintrag/Eintraege"
        else
            note_failure "$phase: $tool_name ist leer"
        fi
    done
}

# CSV per job_input hochladen und importieren; die Zeilen muessen in der Zieltabelle stehen.
check_import() {
    local phase="$1" file="$WORK/rows.csv" size sha reply session ref rows
    printf 'id,v\n1,a\n2,b\n' > "$file"
    size="$(wc -c < "$file")"
    sha="$(sha256sum "$file" | cut -d' ' -f1)"
    reply="$(tool artifact_upload_init "{\"uploadIntent\":\"job_input\",\"approvalKey\":\"state-smoke-up\",\
\"sizeBytes\":$size,\"checksumSha256\":\"$sha\",\"mimeType\":\"text/csv\",\"artifactKind\":\"seed-data\",\"targetTable\":\"imp\"}")"
    session="$(jq -r '.result.content[0].text | fromjson? | .uploadSessionId // empty' <<< "$reply" 2> /dev/null)"
    [ -n "$session" ] || { note_failure "$phase: job_input-Init scheitert: $(head -c 400 <<< "$reply")"; return; }
    reply="$(tool artifact_upload "{\"uploadSessionId\":\"$session\",\"segmentIndex\":1,\"segmentOffset\":0,\
\"segmentTotal\":1,\"isFinalSegment\":true,\"segmentSha256\":\"$sha\",\"contentBase64\":\"$(base64 -w0 "$file")\"}")"
    ref="$(jq -r '.result.content[0].text | fromjson? | .schemaRef // empty' <<< "$reply" 2> /dev/null)"
    [ -n "$ref" ] || { note_failure "$phase: job_input-Upload liefert keine Referenz: $(head -c 400 <<< "$reply")"; return; }
    log "  job_input-Upload liefert $ref"
    run_job "$phase" data_import_start "{\"idempotencyKey\":\"state-smoke-imp\",\
\"targetConnectionRef\":\"dmigrate://tenants/default/connections/src\",\"sourceArtifactRef\":\"$ref\",\
\"table\":\"imp\",\"format\":\"csv\"}" || return
    rows="$(psql_ "select string_agg(id || ':' || v, ',' order by id) from public.imp")"
    if [ "$rows" = "1:a,2:b" ]; then
        log "  die importierten Zeilen stehen in public.imp"
    else
        note_failure "$phase: public.imp enthaelt '$rows' statt '1:a,2:b'"
    fi
}

# Ein Serverfehler, den es sonst nicht gibt: Die Tabelle hinter artifact_list wird umbenannt. Der Client sieht nur
# INTERNAL_AGENT_ERROR; die Ursache muss im Serverlog stehen, auch im Native-Image (dort ist die Logstufe fest).
check_error_log() {
    local phase="$1" code logs
    log "== 5. ein Serverfehler steht mit seiner Ursache im Serverlog"
    psql_ 'alter table dmigrate_state.artifact_records rename to artifact_records_off' > /dev/null \
        || { note_failure "$phase: Tabelle nicht umbenennbar"; return; }
    if code="$(tool_ok artifact_list '{}')"; then
        note_failure "$phase: artifact_list antwortet trotz fehlender Tabelle ohne Fehler"
        return
    fi
    [ "$code" = INTERNAL_AGENT_ERROR ] || { note_failure "$phase: artifact_list -> $code statt INTERNAL_AGENT_ERROR"; return; }
    # Erst einsammeln, dann suchen: `docker logs | grep -q` scheitert unter pipefail, sobald grep frueh endet.
    logs="$(docker logs "$MCP_NAME" 2>&1)"
    if grep -q "tool 'artifact_list' .*failed with INTERNAL_AGENT_ERROR" <<< "$logs" \
        && grep -q 'relation .*artifact_records.* does not exist' <<< "$logs"; then
        log "  Serverlog nennt Tool und Ursache"
    else
        note_failure "$phase: das Serverlog nennt die Ursache des INTERNAL_AGENT_ERROR nicht"
    fi
}

# Die Bytes eines gespeicherten Artefakts muessen nach dem Neustart noch da sein, sonst zeigt der Datensatz ins Leere.
check_bytes() {
    local phase="$1" id code
    id="$(tool artifact_list '{}' | jq -r '.result.content[0].text | fromjson? | .artifacts[0].artifactId // empty' 2> /dev/null)"
    [ -n "$id" ] || { note_failure "$phase: artifact_list nennt keine artifactId"; return; }
    if code="$(tool_ok artifact_chunk_get "{\"artifactId\":\"$id\"}")"; then
        log "  artifact_chunk_get liest die Bytes von $id"
    else
        note_failure "$phase: artifact_chunk_get -> $code"
    fi
}

# Ohne --mcp-state-dir laegen die Bytes in einem temporaeren Verzeichnis: der Server verweigert den Start.
check_refusal() {
    local phase="$1" out rc
    log "== 6. ohne --mcp-state-dir startet der Server nicht"
    docker rm -f "$MCP_NAME" > /dev/null 2>&1
    out="$(timeout 60 docker run --name "$MCP_NAME" --network host \
        -e "STATE_SMOKE_SRC_URL=postgresql://smoke:smoke@127.0.0.1:${PG_PORT}/state" \
        -v "$WORK/config.yaml:/work/config.yaml:ro" \
        "$IMAGE" mcp serve --transport=http --bind=127.0.0.1 --port="$HTTP_PORT" \
        --auth-mode=disabled --connection-config=/work/config.yaml 2>&1)"
    rc=$?
    if [ "$rc" = 2 ] && grep -q 'the artifact bytes would not survive' <<< "$out"; then
        log "  Start abgelehnt (Exit 2)"
    else
        note_failure "$phase: Start ohne --mcp-state-dir nicht abgelehnt (Exit $rc)"
        tail -5 <<< "$out" | sed 's/^/    | /'
    fi
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
psql_ 'create table public.imp(id integer primary key, v text)' > /dev/null || fail "Zieltabelle nicht anlegbar"
# Das Volume fuer --mcp-state-dir gehoert dem Image-Benutzer (uid 10001), sonst kann der Server nicht schreiben.
docker run --rm --user 0 -v "$BYTES_VOLUME:/state" --entrypoint chown "$PG_IMAGE" 10001:10001 /state > /dev/null \
    || fail "Volume fuer den Byte-Store nicht anlegbar"

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
            check_job_uris "3"
            upload_schema "3"
            check_upload "3"
            run_job "3" schema_compare_start "{\"idempotencyKey\":\"state-smoke-cmp\",\
\"sourceUri\":\"dmigrate://tenants/default/connections/src\",\"targetUri\":\"$UPLOADED_REF\"}"
            run_job "3" data_profile_start \
                '{"idempotencyKey":"state-smoke-prof","connectionId":"dmigrate://tenants/default/connections/src"}'
            check_indices "3"
            check_import "3"
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
        check_bytes "4"
        check_upload "4"
        check_indices "4"
        check_error_log "5"
    else
        note_failure "4: der Server startet nicht gegen die gefuellte State-DB"
        docker logs "$MCP_NAME" 2>&1 | tail -15 | sed 's/^/    | /'
    fi
fi

# Vor Schritt 6: er ersetzt den Container, dessen Log die Ursachen traegt.
[ ${#FAILURES[@]} -eq 0 ] || server_log
check_refusal "6"

if [ ${#FAILURES[@]} -gt 0 ]; then
    printf '\nFAIL: %d Befund(e)\n' "${#FAILURES[@]}" >&2
    printf '  - %s\n' "${FAILURES[@]}" >&2
    exit 1
fi
log "OK — server.state unter $IMAGE: Migration, Listen, Jobs, Upload, Indizes, Import, Neustart, Bytes, Fehlerprotokoll, Startpruefung"
