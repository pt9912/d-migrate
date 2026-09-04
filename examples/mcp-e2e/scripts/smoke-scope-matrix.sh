#!/usr/bin/env bash
# MCP-E2E-Harness — Scope-Matrix-Smoke gegen das echte d-migrate:dev-Image
# Plan: docs/planning/next/mcp-real-e2e-scope-matrix.md Teil B
#
# Fährt `mcp serve --transport stdio` als echten Container-Prozess
# (`docker compose run -T`, NDJSON-Requests per stdin) — genau die Ebene,
# die weder die In-Process-Szenario-Tests noch der JVM-Real-Subprozess
# (Teil A, test/e2e-cli) anfassen: das GEBAUTE Runtime-Image selbst.
#
# Ein Vertreter pro Scope (die vollständige, aus DEFAULT_SCOPE_MAPPING
# generierte Matrix ist Teil As Job, siehe
# McpScopeEnforcementMatrixTest.kt) plus `connections/list?checkLive=true`
# gegen den echten postgres-Service.
#
# admin-Token (isAdmin, stdio-tokens.yaml): jeder Aufruf darf NICHT
# scope-verweigert werden. Für die fünf *_start-Tools ist ein
# POLICY_DENIED-Ergebnis erwartet und KEIN Fehlschlag (kein --policy-file
# verdrahtet — fail-closed-Default, siehe mcp-real-e2e-scope-matrix.md
# AE-A2/AE-B4): das beweist weiterhin, dass die Scope-Prüfung durchließ.
# restricted-Token (nur dmigrate:read): `connections/list` MUSS
# scope-verweigert werden — der einzige dmigrate:admin-gated Eintrag.
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
RESTRICTED_TOKEN="tok_mcp_e2e_restricted_dev_only"

log()  { printf '[mcp-e2e-smoke] %s\n' "$*"; }
fail() { printf '[mcp-e2e-smoke] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 0. .env + Stack hoch + healthy ---------------------------------
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

# --- 1. Requests bauen ------------------------------------------------
INIT_REQ='{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"mcp-e2e-smoke","version":"0.0.0"},"capabilities":{}}}'
INITIALIZED_NOTIF='{"jsonrpc":"2.0","method":"notifications/initialized"}'

admin_requests_file="$OUT_DIR/admin-requests.ndjson"
cat > "$admin_requests_file" <<EOF
$INIT_REQ
$INITIALIZED_NOTIF
{"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"schema_reverse_start"}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"job_cancel"}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"data_import_start"}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"artifact_upload_abort"}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"testdata_plan"}}
{"jsonrpc":"2.0","id":7,"method":"connections/list","params":{}}
{"jsonrpc":"2.0","id":8,"method":"connections/list","params":{"checkLive":true}}
EOF

restricted_requests_file="$OUT_DIR/restricted-requests.ndjson"
cat > "$restricted_requests_file" <<EOF
$INIT_REQ
$INITIALIZED_NOTIF
{"jsonrpc":"2.0","id":1,"method":"connections/list","params":{}}
EOF

# --- 2. Sessions fahren ------------------------------------------------
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
log "running admin session (7 scopes)..."
run_session "$ADMIN_TOKEN" "$admin_requests_file" "$admin_responses_file"

restricted_responses_file="$OUT_DIR/restricted-responses.ndjson"
log "running restricted session (dmigrate:read only)..."
run_session "$RESTRICTED_TOKEN" "$restricted_requests_file" "$restricted_responses_file"

# --- 3. Assertions -------------------------------------------------
response_for_id() {  # response_for_id <file> <id>
    jq -c --argjson id "$2" 'select(.id == $id)' "$1"
}

# jq-Filter: true wenn die Antwort NICHT scope-verweigert ist (weder
# JSON-RPC InvalidRequest/lacks-required-scope noch
# ToolsCallResult.isError mit FORBIDDEN_PRINCIPAL).
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

is_scope_denied_protocol() {  # is_scope_denied_protocol <response-json> <method>
    jq -e --arg method "$2" '
        .error != null and .error.code == -32600 and
        (.error.message // "" | contains("lacks required scope(s) for '\''" + $method + "'\''"))
    ' <<<"$1" >/dev/null
}

is_policy_denied() {  # is_policy_denied <response-json>
    jq -e '
        .result.isError == true and
        (.result.content[0].text | fromjson | .code) == "POLICY_DENIED"
    ' <<<"$1" >/dev/null
}

assert_admin_ok() {  # assert_admin_ok <id> <label> [--allow-policy-denied]
    local id="$1" label="$2" allow_policy="${3:-}"
    local resp; resp="$(response_for_id "$admin_responses_file" "$id")"
    [ -n "$resp" ] || fail "admin: no response for id=$id ($label)"
    if not_scope_denied "$resp"; then
        log "admin: '$label' not scope-rejected — OK"
        return
    fi
    if [ "$allow_policy" = "--allow-policy-denied" ] && is_policy_denied "$resp"; then
        log "admin: '$label' hit POLICY_DENIED (no --policy-file wired) — expected, counts as OK"
        return
    fi
    fail "admin: '$label' was scope-rejected (response=$resp)"
}

log "asserting admin session..."
assert_admin_ok 1 "resources/list"
assert_admin_ok 2 "schema_reverse_start" --allow-policy-denied
assert_admin_ok 3 "job_cancel" --allow-policy-denied
assert_admin_ok 4 "data_import_start" --allow-policy-denied
assert_admin_ok 5 "artifact_upload_abort"
assert_admin_ok 6 "testdata_plan"
assert_admin_ok 7 "connections/list"

live_resp="$(response_for_id "$admin_responses_file" 8)"
[ -n "$live_resp" ] || fail "admin: no response for connections/list?checkLive=true"
status="$(jq -r '.result.connections[] | select(.connectionId=="mcp_e2e_pg") | .status' <<<"$live_resp")"
[ "$status" = "REACHABLE" ] || fail "connections/list?checkLive=true: expected REACHABLE for mcp_e2e_pg, got '$status' (response=$live_resp)"
log "connections/list?checkLive=true: mcp_e2e_pg is REACHABLE — OK"

log "asserting restricted session..."
restricted_resp="$(response_for_id "$restricted_responses_file" 1)"
[ -n "$restricted_resp" ] || fail "restricted: no response for connections/list"
is_scope_denied_protocol "$restricted_resp" "connections/list" \
    || fail "restricted: connections/list was NOT scope-rejected (response=$restricted_resp)"
log "restricted: connections/list correctly scope-rejected (dmigrate:admin) — OK"

log "all assertions passed."
