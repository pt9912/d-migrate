#!/usr/bin/env bash
# MCP-E2E-Harness — jeder versiegelte Cursor des Servers fuehrt zur naechsten Seite.
#
# Warum es das gibt: Im nativen Image lieferte Gson fuer die nicht registrierten Cursor-Klassen `{}`, ohne Fehler
# (Issue #88). Der Server gab Cursor aus, die er selbst ablehnte: Listen endeten nach der ersten Seite,
# `resources/list` fiel bei mehr als 50 Ressourcen ganz aus, grosse Artefakte waren nicht vollstaendig lesbar.
# Die Matrizen holten nie eine zweite Seite.
#
# Ablauf (ein Image, wie es MCP_E2E_DMIGRATE_IMAGE nennt), ohne Datenbank und ohne server.state:
#   1. 26 kleine Schemas und ein grosses (mehrere Chunks) hochladen
#   2. schema_list und artifact_list: Seite 2 ueber nextCursor, mit anderen Eintraegen als Seite 1
#   3. resources/list: Seite 1 nennt nextCursor, Seite 2 antwortet
#   4. artifact_chunk_get: der naechste Chunk ueber nextChunkCursor
#
# Aufruf: scripts/smoke-pagination.sh
# Voraussetzung am Host: docker, curl, jq. Belegt den Port MCP_E2E_PAGE_HTTP_PORT (8796).
set -uo pipefail

IMAGE="${MCP_E2E_DMIGRATE_IMAGE:-d-migrate:dev}"
HTTP_PORT="${MCP_E2E_PAGE_HTTP_PORT:-8796}"
MCP_NAME="mcp-e2e-pagination"
URL="http://127.0.0.1:${HTTP_PORT}/mcp"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
FAILURES=()
note_failure() { FAILURES+=("$*"); log "FEHLER: $*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

for tool_bin in docker curl jq; do command -v "$tool_bin" > /dev/null || fail "$tool_bin fehlt am Host"; done

cleanup() { docker rm -f "$MCP_NAME" > /dev/null 2>&1; }
trap cleanup EXIT

H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

# rpc METHODE PARAMS-JSON -> die rohe Antwort
rpc() {
    curl -s "${H[@]}" "$URL" -d "$(jq -nc --arg m "$1" --argjson p "$2" '{jsonrpc: "2.0", id: 1, method: $m, params: $p}')"
}

# tool NAME ARGS-JSON -> der Text des Ergebnisses, bei Protokollfehlern das Fehlerobjekt
tool() {
    rpc tools/call "$(jq -nc --arg n "$1" --argjson a "$2" '{name: $n, arguments: $a}')" \
        | jq -r '.result.content[0].text // (.error | tostring)'
}

# upload DATEI -> gibt den schemaRef aus
upload() {
    local file="$1" size sha session
    size="$(wc -c < "$file")"
    sha="$(sha256sum "$file" | cut -d' ' -f1)"
    session="$(tool artifact_upload_init \
        "{\"uploadIntent\":\"schema_staging_readonly\",\"expectedSizeBytes\":$size,\"checksumSha256\":\"$sha\"}" \
        | jq -r '.uploadSessionId // empty')"
    [ -n "$session" ] || return 1
    tool artifact_upload "{\"uploadSessionId\":\"$session\",\"segmentIndex\":1,\"segmentOffset\":0,\"segmentTotal\":1,\
\"isFinalSegment\":true,\"segmentSha256\":\"$sha\",\"contentBase64\":\"$(base64 -w0 "$file")\"}" | jq -er '.schemaRef'
}

# check_list TOOL FELD ID-FELD: Seite 2 antwortet und beginnt nicht mit Seite 1.
check_list() {
    local name="$1" field="$2" key="$3" first cursor second a b
    first="$(tool "$name" '{"pageSize":2}')"
    cursor="$(jq -r '.nextCursor // empty' <<< "$first")"
    [ -n "$cursor" ] || { note_failure "$name: Seite 1 nennt keinen nextCursor"; return; }
    second="$(tool "$name" "$(jq -nc --arg c "$cursor" '{pageSize: 2, cursor: $c}')")"
    a="$(jq -r --arg f "$field" --arg k "$key" '.[$f][0][$k] // empty' <<< "$first")"
    b="$(jq -r --arg f "$field" --arg k "$key" '.[$f][0][$k] // empty' <<< "$second")"
    if [ -n "$b" ] && [ "$a" != "$b" ]; then
        log "  $name: Seite 2 ueber nextCursor"
    else
        note_failure "$name: Seite 2 scheitert: $(head -c 200 <<< "$second")"
    fi
}

WORK="$(mktemp -d)"
trap 'cleanup; rm -rf "$WORK"' EXIT

log "Image $IMAGE"
cleanup
docker run -d --name "$MCP_NAME" --network host "$IMAGE" mcp serve --transport=http --bind=127.0.0.1 \
    --port="$HTTP_PORT" --auth-mode=disabled > /dev/null || fail "Container startet nicht"
sid=""
for _ in $(seq 60); do
    sid="$(curl -s -D - -o /dev/null "${H[@]}" "$URL" \
        -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"page-smoke","version":"1"}}}' \
        2> /dev/null | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id" {print $2}')"
    [ -n "$sid" ] && break
    sleep 1
done
[ -n "$sid" ] || { docker logs "$MCP_NAME" 2>&1 | tail -15; fail "kein MCP-Handshake"; }
H+=(-H "MCP-Session-Id: $sid" -H 'MCP-Protocol-Version: 2025-11-25')
curl -s -o /dev/null "${H[@]}" "$URL" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

log "== 1. Schemas hochladen"
for i in $(seq 26); do
    jq -cn --arg n "s$i" '{schema_format: "1.0", name: $n, version: "1.0.0",
        tables: {t: {columns: {id: {type: "integer", required: true}}, primary_key: ["id"]}}}' > "$WORK/s.json"
    upload "$WORK/s.json" > /dev/null || fail "Upload $i scheitert"
done
jq -cn '{schema_format: "1.0", name: "big", version: "1.0.0", tables: {t: {
    columns: ([range(0; 1200) | {key: "column_number_\(.)", value: {type: "integer"}}]
        | from_entries + {id: {type: "integer", required: true}}), primary_key: ["id"]}}}' > "$WORK/big.json"
big_ref="$(upload "$WORK/big.json")" || fail "Upload des grossen Schemas scheitert"

log "== 2. Listen-Werkzeuge"
check_list schema_list schemas resourceUri
check_list artifact_list artifacts artifactId

log "== 3. resources/list"
first="$(rpc resources/list '{}')"
cursor="$(jq -r '.result.nextCursor // empty' <<< "$first")"
if [ -z "$cursor" ]; then
    note_failure "resources/list: Seite 1 ohne nextCursor: $(head -c 200 <<< "$first")"
else
    second="$(rpc resources/list "$(jq -nc --arg c "$cursor" '{cursor: $c}')")"
    if [ "$(jq -r '(.result.resources // []) | length > 0' <<< "$second")" = true ]; then
        log "  resources/list: Seite 2 ueber nextCursor"
    else
        note_failure "resources/list: Seite 2 scheitert: $(head -c 200 <<< "$second")"
    fi
fi

log "== 4. artifact_chunk_get"
artifact="art-${big_ref##*/sch-}"
first="$(tool artifact_chunk_get "{\"artifactId\":\"$artifact\"}")"
cursor="$(jq -r '.nextChunkCursor // empty' <<< "$first")"
if [ -z "$cursor" ]; then
    note_failure "artifact_chunk_get: erster Chunk ohne nextChunkCursor: $(head -c 200 <<< "$first")"
else
    second="$(tool artifact_chunk_get "$(jq -nc --arg a "$artifact" --arg c "$cursor" '{artifactId: $a, nextChunkCursor: $c}')")"
    if [ "$(jq -r '.chunkId // empty' <<< "$second")" = 1 ]; then
        log "  artifact_chunk_get: Chunk 2 ueber nextChunkCursor"
    else
        note_failure "artifact_chunk_get: zweiter Chunk scheitert: $(head -c 200 <<< "$second")"
    fi
fi

if [ ${#FAILURES[@]} -gt 0 ]; then
    printf '\nFAIL: %d Befund(e)\n' "${#FAILURES[@]}" >&2
    printf '  - %s\n' "${FAILURES[@]}" >&2
    exit 1
fi
log "OK — Pagination unter $IMAGE: schema_list, artifact_list, resources/list, artifact_chunk_get"
