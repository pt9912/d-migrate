#!/usr/bin/env bash
# MCP-E2E-Harness — Scope-Matrix-Smoke gegen das echte d-migrate:dev-Image
# Plan: docs/planning/done/mcp-real-e2e-scope-matrix.md Teil B
#
# Fährt `mcp serve --transport stdio` als echten Container-Prozess
# (`docker compose run -T`, NDJSON-Requests per stdin) — genau die Ebene,
# die weder die In-Process-Szenario-Tests noch der JVM-Real-Subprozess
# (Teil A, test/e2e-cli) anfassen: das GEBAUTE Runtime-Image selbst.
#
# Vollständige, aus McpServerConfig.DEFAULT_SCOPE_MAPPING übernommene Matrix
# (alle 31 Einträge, wie McpScopeEnforcementMatrixTest.kt in Teil A) plus
# `connections/list?checkLive=true` gegen den echten postgres-Service.
#
# admin-Token (isAdmin, stdio-tokens.yaml): kein Aufruf darf scope-verweigert
# werden. Für die fünf *_start-Tools (schema_reverse_start,
# schema_compare_start, data_profile_start, data_import_start,
# data_transfer_start) ist ein POLICY_DENIED-Ergebnis erwartet und KEIN
# Fehlschlag (kein --policy-file verdrahtet — fail-closed-Default): das
# beweist weiterhin, dass die Scope-Prüfung durchließ.
# noscope-Token (keine Scopes): JEDER der 31 Einträge muss scope-verweigert
# werden, in der jeweils passenden Form (7 JSON-RPC-Protokollmethoden →
# InvalidRequest, 24 Tool-Namen via tools/call → FORBIDDEN_PRINCIPAL).
#
# Voraussetzung am Host: docker, docker compose, jq sowie das lokal
# gebaute d-migrate:dev-Image (`make docker-build IMAGE_TAG=dev`).
# Der Stack bleibt nach dem Lauf stehen — Cleanup via
# `make mcp-e2e-down` (Volume bleibt) / `make mcp-e2e-purge`.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
OUT_DIR="$EXAMPLES_DIR/out"

ADMIN_TOKEN="tok_mcp_e2e_admin_dev_only"
NOSCOPE_TOKEN="tok_mcp_e2e_noscope_dev_only"

log()  { printf '[mcp-e2e-smoke] %s\n' "$*"; }
fail() { printf '[mcp-e2e-smoke] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. Die volle DEFAULT_SCOPE_MAPPING-Matrix (31 Einträge) -----------
# Reihenfolge/Inhalt gespiegelt aus
# adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt
# (buildDefaultScopeMapping) — dieselbe Quelle wie Teil As
# McpScopeEnforcementMatrixTest.kt (dort zur Laufzeit introspektiert; ein
# Bash-Skript kann das nicht, daher hier gepflegt gespiegelt).
ENTRY_NAMES=(
    capabilities_list tools/list resources/list resources/templates/list resources/read
    prompts/list prompts/get schema_validate schema_compare schema_generate schema_list
    profile_list diff_list job_list job_status_get artifact_list artifact_chunk_get
    artifact_upload_init artifact_upload
    schema_reverse_start schema_compare_start data_profile_start
    artifact_upload_abort
    data_import_start data_transfer_start
    job_cancel
    procedure_transform_plan procedure_transform_execute testdata_plan testdata_execute
    connections/list
)
declare -A SCOPE_OF=(
    [capabilities_list]="dmigrate:read" [tools/list]="dmigrate:read"
    [resources/list]="dmigrate:read" [resources/templates/list]="dmigrate:read"
    [resources/read]="dmigrate:read" [prompts/list]="dmigrate:read" [prompts/get]="dmigrate:read"
    [schema_validate]="dmigrate:read" [schema_compare]="dmigrate:read"
    [schema_generate]="dmigrate:read" [schema_list]="dmigrate:read" [profile_list]="dmigrate:read"
    [diff_list]="dmigrate:read" [job_list]="dmigrate:read" [job_status_get]="dmigrate:read"
    [artifact_list]="dmigrate:read" [artifact_chunk_get]="dmigrate:read"
    [artifact_upload_init]="dmigrate:read" [artifact_upload]="dmigrate:read"
    [schema_reverse_start]="dmigrate:job:start" [schema_compare_start]="dmigrate:job:start"
    [data_profile_start]="dmigrate:job:start"
    [artifact_upload_abort]="dmigrate:artifact:upload"
    [data_import_start]="dmigrate:data:write" [data_transfer_start]="dmigrate:data:write"
    [job_cancel]="dmigrate:job:cancel"
    [procedure_transform_plan]="dmigrate:ai:execute" [procedure_transform_execute]="dmigrate:ai:execute"
    [testdata_plan]="dmigrate:ai:execute" [testdata_execute]="dmigrate:ai:execute"
    [connections/list]="dmigrate:admin"
)
POLICY_TOLERANT=(schema_reverse_start schema_compare_start data_profile_start data_import_start data_transfer_start)

is_protocol_method() {
    case "$1" in
        tools/list|resources/list|resources/templates/list|resources/read|prompts/list|prompts/get|connections/list) return 0 ;;
        *) return 1 ;;
    esac
}

is_policy_tolerant() {
    local name="$1" p
    for p in "${POLICY_TOLERANT[@]}"; do [ "$p" = "$name" ] && return 0; done
    return 1
}

# --- 1. .env + Stack hoch + healthy ---------------------------------
mkdir -p "$OUT_DIR"
if [ ! -f "$EXAMPLES_DIR/.env" ]; then
    cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    log "created examples/mcp-e2e/.env from .env.example"
fi
# shellcheck disable=SC1091
set -a; . "$EXAMPLES_DIR/.env"; set +a
: "${POSTGRES_USER:?POSTGRES_USER not set}"

export MCP_E2E_DMIGRATE_USER="$(id -u):$(id -g)"

log "starting postgres..."
$COMPOSE up -d postgres
log "waiting for postgres healthy (timeout 120s)..."
deadline=$(($(date +%s) + 120)); pg_ok="no"
while [ "$(date +%s)" -lt "$deadline" ]; do
    st=$(docker inspect --format '{{.State.Health.Status}}' "$($COMPOSE ps -q postgres)" 2>/dev/null || echo "?")
    if [ "$st" = "healthy" ]; then pg_ok="yes"; break; fi
    sleep 2
done
[ "$pg_ok" = "yes" ] || fail "postgres did not become healthy within 120s"

# --- 2. Requests bauen ------------------------------------------------
INIT_REQ='{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"mcp-e2e-smoke","version":"0.0.0"},"capabilities":{}}}'
INITIALIZED_NOTIF='{"jsonrpc":"2.0","method":"notifications/initialized"}'
LIVE_CHECK_ID=$(( ${#ENTRY_NAMES[@]} + 1 ))

request_line_for() {  # request_line_for <id> <name>
    local id="$1" name="$2"
    if [ "$name" = "prompts/get" ]; then
        printf '{"jsonrpc":"2.0","id":%d,"method":"%s","params":{"name":""}}\n' "$id" "$name"
    elif is_protocol_method "$name"; then
        printf '{"jsonrpc":"2.0","id":%d,"method":"%s","params":{}}\n' "$id" "$name"
    else
        printf '{"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s"}}\n' "$id" "$name"
    fi
}

build_requests_file() {  # build_requests_file <out-file> [--with-live-check]
    local out="$1" with_live="${2:-}"
    {
        echo "$INIT_REQ"
        echo "$INITIALIZED_NOTIF"
        local i id name
        for i in "${!ENTRY_NAMES[@]}"; do
            id=$((i + 1)); name="${ENTRY_NAMES[$i]}"
            request_line_for "$id" "$name"
        done
        if [ "$with_live" = "--with-live-check" ]; then
            printf '{"jsonrpc":"2.0","id":%d,"method":"connections/list","params":{"checkLive":true}}\n' "$LIVE_CHECK_ID"
        fi
    } > "$out"
}

admin_requests_file="$OUT_DIR/admin-requests.ndjson"
build_requests_file "$admin_requests_file" --with-live-check

noscope_requests_file="$OUT_DIR/noscope-requests.ndjson"
build_requests_file "$noscope_requests_file"

# --- 3. Sessions fahren ------------------------------------------------
run_session() {  # run_session <token> <requests-file> <responses-file>
    local token="$1" requests_file="$2" responses_file="$3"
    $COMPOSE run --rm -T \
        -e "DMIGRATE_MCP_STDIO_TOKEN=$token" \
        dmigrate mcp serve --transport stdio \
        --stdio-token-file /work/stdio-tokens.yaml \
        --connection-config /work/.d-migrate.yaml \
        < "$requests_file" > "$responses_file"
}

admin_responses_file="$OUT_DIR/admin-responses.ndjson"
log "running admin session (all ${#ENTRY_NAMES[@]} entries + checkLive)..."
run_session "$ADMIN_TOKEN" "$admin_requests_file" "$admin_responses_file"

noscope_responses_file="$OUT_DIR/noscope-responses.ndjson"
log "running noscope session (all ${#ENTRY_NAMES[@]} entries)..."
run_session "$NOSCOPE_TOKEN" "$noscope_requests_file" "$noscope_responses_file"

# --- 4. Assertions -------------------------------------------------
response_for_id() {  # response_for_id <file> <id>
    jq -c --argjson id "$2" 'select(.id == $id)' "$1"
}

# true wenn die Antwort NICHT scope-verweigert ist (weder JSON-RPC
# InvalidRequest/lacks-required-scope noch ToolsCallResult.isError mit
# FORBIDDEN_PRINCIPAL) — für den admin-Positiv-Fall.
not_scope_denied() {  # not_scope_denied <response-json>
    jq -e '
        if .error then
            (.error.code != -32600) or ((.error.message // "") | contains("lacks required scope(s)") | not)
        elif .result.isError == true then
            (.result.content[0].text | fromjson | .code) != "FORBIDDEN_PRINCIPAL"
        else
            true
        end
    ' <<<"$1" >/dev/null
}

is_policy_denied() {  # is_policy_denied <response-json>
    jq -e '
        .result.isError == true and
        (.result.content[0].text | fromjson | .code) == "POLICY_DENIED"
    ' <<<"$1" >/dev/null
}

# true wenn die Antwort exakt in der zum Eintrag passenden Form
# scope-verweigert ist — für den noscope-Negativ-Fall.
is_scope_denied() {  # is_scope_denied <response-json> <name> <expected-scope>
    local resp="$1" name="$2" scope="$3"
    if is_protocol_method "$name"; then
        jq -e --arg method "$name" --arg scope "$scope" '
            .error != null and .error.code == -32600 and
            ((.error.message // "") | contains("lacks required scope(s) for '\''" + $method + "'\''")) and
            ((.error.message // "") | contains($scope))
        ' <<<"$resp" >/dev/null
    else
        jq -e --arg scope "$scope" '
            if .result.isError == true then
                (.result.content[0].text | fromjson) as $env
                | ($env.details // [] | map(select(.key == "reason")) | .[0].value // "") as $reason
                | ($env.code == "FORBIDDEN_PRINCIPAL") and ($reason | contains("missing scope(s)")) and ($reason | contains($scope))
            else
                false
            end
        ' <<<"$resp" >/dev/null
    fi
}

log "asserting admin session (positive, all ${#ENTRY_NAMES[@]} entries)..."
for i in "${!ENTRY_NAMES[@]}"; do
    id=$((i + 1)); name="${ENTRY_NAMES[$i]}"
    resp="$(response_for_id "$admin_responses_file" "$id")"
    [ -n "$resp" ] || fail "admin: no response for id=$id ('$name')"
    if not_scope_denied "$resp"; then
        continue
    fi
    if is_policy_tolerant "$name" && is_policy_denied "$resp"; then
        continue
    fi
    fail "admin: '$name' was scope-rejected (response=$resp)"
done
log "admin: all ${#ENTRY_NAMES[@]} entries passed (no scope rejection; POLICY_DENIED tolerated for *_start)."

live_resp="$(response_for_id "$admin_responses_file" "$LIVE_CHECK_ID")"
[ -n "$live_resp" ] || fail "admin: no response for connections/list?checkLive=true"
status="$(jq -r '.result.connections[] | select(.connectionId=="mcp_e2e_pg") | .status' <<<"$live_resp")"
[ "$status" = "REACHABLE" ] || fail "connections/list?checkLive=true: expected REACHABLE for mcp_e2e_pg, got '$status' (response=$live_resp)"
log "connections/list?checkLive=true: mcp_e2e_pg is REACHABLE — OK"

log "asserting noscope session (negative, all ${#ENTRY_NAMES[@]} entries)..."
for i in "${!ENTRY_NAMES[@]}"; do
    id=$((i + 1)); name="${ENTRY_NAMES[$i]}"; scope="${SCOPE_OF[$name]}"
    resp="$(response_for_id "$noscope_responses_file" "$id")"
    [ -n "$resp" ] || fail "noscope: no response for id=$id ('$name')"
    is_scope_denied "$resp" "$name" "$scope" \
        || fail "noscope: '$name' was NOT correctly scope-rejected (expected scope '$scope', response=$resp)"
done
log "noscope: all ${#ENTRY_NAMES[@]} entries correctly scope-rejected."

log "all assertions passed (${#ENTRY_NAMES[@]} entries x 2 directions + live-check)."
