#!/usr/bin/env bash
# Compare-Matrix 5x5: jeder Dialekt einmal **Quelle**, verglichen mit dem
# Reverse jedes anderen Dialekts — ueber MCP, gegen das echte
# `d-migrate:dev`-Image und echte Server.
#
# Ablauf je Quelle Q:
#
#   fixtures/compare-matrix.yaml --generate (CLI)--> DDL(Q) --anwenden--> Q
#   [fixtures/seeds/<q>.sql, falls vorhanden, zusaetzlich anwenden]
#   schema_reverse_start(Q)                                -> Reverse(Q)
#   je Ziel Z != Q:
#     Reverse(Q) --generate (CLI)--> DDL(Z) --anwenden--> Z
#     schema_reverse_start(Z)                              -> Reverse(Z)
#     schema_compare(Reverse(Q), Reverse(Z))               -> Funde (Werkzeug)
#     schema_compare_start(Reverse(Q), Reverse(Z))         -> Funde (Job)
#
# **Anwenden laeuft nicht ueber MCP** — die Tools kennen kein „DDL
# anwenden". Generiert wird mit der CLI aus demselben Schema, das der
# MCP-Reverse abgelegt hat (Inhalt ueber `artifact_chunk_get`), angewendet
# mit dem Client des Dialekts (lib/dialects.sh).
#
# **Server-Konfiguration:** die Verbindungen aus .d-migrate.yaml plus die
# Lese-Praeferenz `reverse.mysql.autoincrement_syntax: identity`. Ohne sie
# unterschiede sich eine PostgreSQL-Identity-Spalte gegen MySQL immer auch in
# `legacy_serial_syntax`, und der Waechter `sequence` saehe den Sequenznamen
# nie allein — er waere blind. Mit ihr ist PostgreSQL -> MySQL die Zelle, in
# der eine Identity-Spalte (`BY DEFAULT`, Fixture) sich nur im Sequenznamen
# unterscheiden koennte.
#
# **Ausgabe:** je Zelle die Zahl der Funde und ihre Codes. **Erwartungen**
# stehen in expected/compare-matrix.env und sind an die d-migrate-Version
# gebunden: weicht eine Zelle ab, scheitert der Lauf; nach bewusster Pruefung
# des Unterschieds pinnt `--update-expectations` neu (den Diff der Datei vor
# dem Commit lesen). Gepinnt wird nur ein gemessener Zustand: eine Zelle,
# deren Erzeugung scheitert (Exit weder 0 noch 8), deren Fehlerklasse
# unbekannt ist oder deren Reverse, Vergleich oder Job scheitert, ist nie
# pinnbar — gibt es eine solche, schreibt der Lauf die Datei **nicht**.
# **Versionsunabhaengig** und nie zu pinnen (lib/compare-guards.sh und unten):
#
#   - Werkzeug und Job liefern dieselben Funde; das Job-Artefakt hat die Art
#     `COMPARE` und genau `status`, `summary`, `findings`, und der Job nennt
#     nur dieses Artefakt (beide Seiten sind gespeicherte Schemata);
#   - kein Name- oder Versionsfund (zwei Reverses), kein Fund, der nur
#     Schreibweise ist, kein Fund, der nur am Sequenznamen haengt; die
#     Ausgabe hat eine Form, die die Waechter lesen koennen (Selbstprobe);
#   - jeder Reverse-Job legt neben dem Schema (Art `SCHEMA`) seinen
#     Reverse-Report ab (Art `REVERSE_REPORT`, `kind: connection`); der
#     MySQL-Report bestaetigt die Praeferenz (`R205`).
#
# Oracle nur mit MCP_E2E_WITH_ORACLE=1 (Kaltstart 2-3 Minuten); seine
# Zellen werden sonst weder gemessen noch geprueft.
#
# Aufruf: scripts/smoke-compare-matrix.sh [--update-expectations]
# Voraussetzung am Host: docker, docker compose, jq, sqlite3, bash >= 4 und
# das Image `make docker-build IMAGE_TAG=dev`.
if [ -z "${BASH_VERSION:-}" ] || [ "${BASH_VERSION%%.*}" -lt 4 ]; then
    echo "FAIL: bash >= 4 noetig (assoziative Arrays, coproc)" >&2
    exit 1
fi
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"
OUT="$EXAMPLES_DIR/out/compare-matrix"
FIXTURE_REL="fixtures/compare-matrix.yaml"
SEED_DIR="$EXAMPLES_DIR/fixtures/seeds"
EXPECT_FILE="${MCP_E2E_COMPARE_EXPECT:-$EXAMPLES_DIR/expected/compare-matrix.env}"
WITH_ORACLE="${MCP_E2E_WITH_ORACLE:-0}"
IMAGE="${MCP_E2E_DMIGRATE_IMAGE:-d-migrate:dev}"
ADMIN_TOKEN="tok_mcp_e2e_admin_dev_only"
TENANT="default"

UPDATE=false
case "${1:-}" in
    --update-expectations) UPDATE=true ;;
    "") ;;
    *) echo "usage: $0 [--update-expectations]" >&2; exit 2 ;;
esac

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
# Abweichungen landen in **Dateien**, nicht in einer Variablen: viele Pruefungen
# laufen in Kommandosubstitutionen (mcp_reverse), und eine Zuweisung dort
# ginge mit der Subshell verloren — der Lauf endete gruen.
#   note_failure    nie pinnbar (Waechter, Form, gescheiterte Schritte)
#   note_deviation  eine Erwartung weicht ab — pinnbar mit --update-expectations
# Eine Abweichung ist eine Zeile: mehrzeilige Meldungen (jq) werden gefaltet.
note_to() {  # $1=Datei $2...=Meldung
    local file="$1" msg
    shift
    msg="$*"
    msg="${msg//$'\n'/ }"
    printf 'FAIL: %s\n' "$msg" >&2
    printf '%s\n' "$msg" >> "$file"
}
note_failure() { note_to "$FAILURES_FILE" "$@"; }
note_deviation() { note_to "$DEVIATIONS_FILE" "$@"; }

# shellcheck source=lib/dialects.sh
. "$SCRIPT_DIR/lib/dialects.sh"
# shellcheck source=lib/compare-guards.sh
. "$SCRIPT_DIR/lib/compare-guards.sh"

for tool in docker jq sqlite3; do
    command -v "$tool" > /dev/null || fail "$tool fehlt auf dem Host"
done
cd "$EXAMPLES_DIR" || exit 1
rm -rf "$OUT"
mkdir -p "$OUT"
SERVER_LOG="$OUT/server.log"
: > "$SERVER_LOG"
FAILURES_FILE="$OUT/.failures"
DEVIATIONS_FILE="$OUT/.deviations"
: > "$FAILURES_FILE"
: > "$DEVIATIONS_FILE"

# Die Server-Konfiguration (s. Kopf): die Verbindungen plus die Praeferenz.
SERVER_CONFIG="$OUT/server.d-migrate.yaml"
{
    cat "$EXAMPLES_DIR/.d-migrate.yaml"
    printf '\nreverse:\n  mysql:\n    autoincrement_syntax: identity\n'
} > "$SERVER_CONFIG"

# --- MCP-Sitzung (stdio, als Koprozess) -------------------------------
# Der Server haelt seine Stores im Speicher; alle Aufrufe einer Quelle laufen
# deshalb in **einer** Sitzung, und die Anwendungen der Ziele geschehen
# zwischen zwei Aufrufen derselben Sitzung.
# Die Aufrufe laufen in Kommandosubstitutionen; der Zaehler der Anfrage-Ids
# liegt deshalb in einer Datei, nicht in einer Variablen der Subshell.
RPC_ID_FILE="$OUT/.rpc-id"
echo 0 > "$RPC_ID_FILE"
next_rpc_id() {
    local n
    n=$(( $(cat "$RPC_ID_FILE") + 1 ))
    echo "$n" > "$RPC_ID_FILE"
    echo "$n"
}
mcp_open() {
    coproc MCP_SRV {
        mcp_e2e_compose run --rm -T \
            -e "DMIGRATE_MCP_STDIO_TOKEN=$ADMIN_TOKEN" \
            dmigrate mcp serve --transport stdio \
            --stdio-token-file /work/stdio-tokens.yaml \
            --connection-config "$(in_container "$SERVER_CONFIG")" \
            --policy-file /work/policy-rules.yaml 2>> "$SERVER_LOG"
    }
    mcp_rpc initialize \
        '{"protocolVersion":"2025-11-25","clientInfo":{"name":"compare-matrix","version":"0"},"capabilities":{}}' \
        > /dev/null || fail "MCP-Server antwortet nicht (Log: $SERVER_LOG)"
    printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}' >&"${MCP_SRV[1]}"
}

mcp_close() {
    local pid="${MCP_SRV_PID:-}"
    [ -n "${MCP_SRV[1]:-}" ] && eval "exec ${MCP_SRV[1]}>&-"
    [ -n "$pid" ] && wait "$pid" 2> /dev/null
    return 0
}

# $1=Methode $2=Parameter (JSON) -> stdout: die Antwort (ganze Nachricht)
mcp_rpc() {
    local rid line id
    rid="$(next_rpc_id)"
    jq -nc --argjson id "$rid" --arg m "$1" --argjson p "$2" \
        '{jsonrpc:"2.0",id:$id,method:$m,params:$p}' >&"${MCP_SRV[1]}" || return 1
    while IFS= read -r -t 600 line <&"${MCP_SRV[0]}"; do
        id="$(jq -r '.id // empty' <<< "$line" 2> /dev/null)" || continue
        if [ "$id" = "$rid" ]; then
            printf '%s\n' "$line"
            return 0
        fi
    done
    return 1
}

# $1=Werkzeug $2=Argumente -> stdout: der Ergebnistext (JSON);
# Rueckgabe 1 bei `isError` oder einem Protokollfehler
mcp_tool() {
    local resp
    resp="$(mcp_rpc tools/call "$(jq -nc --arg n "$1" --argjson a "$2" '{name:$n,arguments:$a}')")" \
        || { echo '{"code":"NO_RESPONSE"}'; return 1; }
    if ! jq -e '.result' <<< "$resp" > /dev/null; then
        jq -c '.error // .' <<< "$resp"
        return 1
    fi
    jq -r '.result.content[0].text' <<< "$resp"
    [ "$(jq -r '.result.isError // false' <<< "$resp")" = "false" ]
}

# Bash reicht die Deskriptoren des Koprozesses nur an Kommando-Substitutionen
# weiter, nicht an die Glieder einer Pipeline: ein `mcp_* | jq` saehe den
# Server nicht. Deshalb erst in eine Variable, dann auswerten.

# $1=URI -> stdout: der Inhalt von resources/read (JSON)
mcp_resource() {
    local resp
    resp="$(mcp_rpc resources/read "$(jq -nc --arg u "$1" '{uri:$u}')")" || return 1
    jq -r '.result.contents[0].text // "null"' <<< "$resp"
}

# $1=Artefakt-Kennung -> stdout: der volle Text, ueber alle Chunks
mcp_artifact_text() {
    local cursor="" args chunk guard=0
    while :; do
        if [ -z "$cursor" ]; then
            args="$(jq -nc --arg a "$1" '{artifactId:$a}')"
        else
            args="$(jq -nc --arg a "$1" --arg c "$cursor" '{artifactId:$a,nextChunkCursor:$c}')"
        fi
        chunk="$(mcp_tool artifact_chunk_get "$args")" || return 1
        jq -j '.text // error("kein Text")' <<< "$chunk" || return 1
        cursor="$(jq -r '.nextChunkCursor // empty' <<< "$chunk")"
        [ -n "$cursor" ] || return 0
        guard=$((guard + 1))
        [ "$guard" -lt 200 ] || return 1
    done
}

# $1=jobId -> stdout: der Status des beendeten Jobs
mcp_await() {
    local status state i
    for i in $(seq 1 300); do
        status="$(mcp_tool job_status_get "$(jq -nc --arg j "$1" '{jobId:$j}')")" || return 1
        state="$(jq -r .status <<< "$status")"
        case "$state" in
            SUCCEEDED) printf '%s\n' "$status"; return 0 ;;
            FAILED|CANCELLED) printf '%s\n' "$status" >&2; return 1 ;;
        esac
        [ "$i" -gt 1 ] && sleep 1
    done
    return 1
}

artifact_id() { printf '%s\n' "${1##*/artifacts/}"; }

# $1=Artefakt-URI -> stdout: die Art laut resources/read (leer, wenn unlesbar)
mcp_artifact_kind() {
    local meta
    meta="$(mcp_resource "$1")" || return 0
    jq -r '.kind // empty' <<< "$meta" 2> /dev/null || true
}

# Die erste Fehlermeldung des Servers als stabile Klasse (Code statt Text,
# wo der Server einen fuehrt). $1=Dialekt $2=Protokoll
apply_error_class() {
    local line=""
    case "$1" in
        postgresql) line="$(grep -m1 -E '^ERROR:' "$2" | sed -E 's/^ERROR: +//')" ;;
        mysql) line="$(grep -m1 -oE '^ERROR [0-9]+' "$2")" ;;
        mssql) line="$(grep -m1 -oE '^Msg [0-9]+' "$2")" ;;
        # sqlite3 schreibt „Parse error near line 5: …", „Runtime error …" oder
        # „Error: …" — Gross-/Kleinschreibung wechselt, die Zeilennummer haengt
        # am DDL und gehoert nicht in die Klasse.
        sqlite) line="$(grep -m1 -iE '(^|[[:space:]])error' "$2" \
            | sed -E 's/^.*[Ee]rror( near line [0-9]+)?[^:]*: *//; s/^near line [0-9]+: *//')" ;;
        oracle) line="$(grep -m1 -oE 'ORA-[0-9]+' "$2")" ;;
    esac
    printf 'apply:%s\n' "${line:-unbekannt}"
}

# $1=Dialekt $2=Zielverzeichnis -> stdout: der schemaRef des Reverse;
# schreibt reversed.yaml und reverse-report.yaml nach $2
mcp_reverse() {
    local dialect="$1" dir="$2" conn start job job_uri status schema_uri report_uri schema_art report_art
    local schema_id listing
    conn="dmigrate://tenants/$TENANT/connections/$(dialect_connection "$dialect")"
    start="$(mcp_tool schema_reverse_start "$(jq -nc --arg c "$conn" --arg k "cm-$dialect-$RANDOM$RANDOM" \
        '{connectionId:$c,idempotencyKey:$k}')")" || { echo "$start" > "$dir/reverse-start.json"; return 1; }
    job="$(jq -r .jobId <<< "$start")"
    job_uri="$(jq -r .resourceUri <<< "$start")"
    status="$(mcp_await "$job" 2> "$dir/reverse-job-failed.json")" || return 1
    printf '%s\n' "$status" > "$dir/reverse-job.json"
    schema_uri="$(jq -r '.artifacts[0] // empty' <<< "$status")"
    report_uri="$(jq -r '.artifacts[1] // empty' <<< "$status")"
    schema_art="$(artifact_id "$schema_uri")"
    report_art="$(artifact_id "$report_uri")"
    [ -n "$schema_art" ] || return 1
    mcp_artifact_text "$schema_art" > "$dir/reversed.yaml" || return 1
    [ "$(mcp_artifact_kind "$schema_uri")" = "SCHEMA" ] \
        || note_failure "$dialect: das erste Artefakt des Reverse-Jobs hat nicht die Art SCHEMA (${dir#"$EXAMPLES_DIR"/})"
    [ "$(jq -r '.artifacts | length' <<< "$status")" = 2 ] \
        || note_failure "$dialect: der Reverse-Job nennt nicht genau Schema und Report (${dir#"$EXAMPLES_DIR"/})"
    if [ -z "$report_art" ] || ! mcp_artifact_text "$report_art" > "$dir/reverse-report.yaml" \
        || ! grep -q '^  kind: connection' "$dir/reverse-report.yaml"; then
        note_failure "$dialect: der Reverse-Job legt keinen Reverse-Report ab (${dir#"$EXAMPLES_DIR"/})"
    elif [ "$(mcp_artifact_kind "$report_uri")" != "REVERSE_REPORT" ]; then
        note_failure "$dialect: der Reverse-Report hat nicht die Art REVERSE_REPORT (${dir#"$EXAMPLES_DIR"/})"
    elif [ "$dialect" = mysql ] && ! grep -q '^    code: R205$' "$dir/reverse-report.yaml"; then
        note_failure "$dialect: der Reverse-Report bestaetigt die Praeferenz identity nicht (R205, ${dir#"$EXAMPLES_DIR"/})"
    fi
    # `jobId` filtert auf den Job-Verweis, unter dem der Index das Schema
    # fuehrt — die Ressourcen-URI des Jobs.
    listing="$(mcp_tool schema_list "$(jq -nc --arg j "$job_uri" '{jobId:$j,pageSize:50}')")" || return 1
    schema_id="$(jq -r --arg a "$schema_art" '[.schemas[] | select(.artifactRef == $a) | .schemaId][0] // empty' \
        <<< "$listing")"
    [ -n "$schema_id" ] || return 1
    printf 'dmigrate://tenants/%s/schemas/%s\n' "$TENANT" "$schema_id"
}

# CLI im Container (Generate); $OUT ist unter /work/out/compare-matrix sichtbar.
dmi() {
    mcp_e2e_compose run --rm -T dmigrate --config /work/.d-migrate.yaml "$@"
}
in_container() { printf '/work/%s\n' "${1#"$EXAMPLES_DIR"/}"; }

# $1=Schema-Datei (Host) $2=Dialekt $3=Ausgabe-DDL (Host) $4=Protokoll
# Rueckgabe: der Exit-Code der CLI. Nur 0 und 8 heissen „DDL geschrieben"
# (8: Objekte uebersprungen); jeder andere ist ein Fehlschlag.
generate_ddl() {
    dmi schema generate --source "$(in_container "$1")" --target "$2" \
        --output "$(in_container "$3")" --deterministic > "$4" 2>&1
}

# $1=Exit von generate_ddl $2=erwartete DDL-Datei -> 0, wenn DDL geschrieben ist
generated_ok() {
    case "$1" in
        0|8) [ -s "$2" ] ;;
        *) return 1 ;;
    esac
}

# --- Erwartungen ------------------------------------------------------
declare -A EXPECT MEASURED
EXPECT_HEADER=""
if [ -f "$EXPECT_FILE" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            \#*|"") [ -z "${EXPECT_KEYS_STARTED:-}" ] && EXPECT_HEADER+="$line"$'\n' ;;
            *=*)
                EXPECT_KEYS_STARTED=1
                key="${line%%=*}"; value="${line#*=}"; value="${value#\"}"; value="${value%\"}"
                EXPECT["$key"]="$value"
                ;;
        esac
    done < "$EXPECT_FILE"
fi

# Ein Zustand ist nur pinnbar, wenn er etwas sagt: eine unbekannte
# Fehlerklasse ist keine Messung. (Eine leere Code-Liste ist es: die Zelle
# hat keine Funde.)
pinnable() {  # $1=gemessener Wert
    case "$1" in
        apply:unbekannt) return 1 ;;
    esac
    return 0
}

check_expect() {  # $1=Schluessel $2=gemessener Wert
    local exp="${EXPECT[$1]-}"
    if ! pinnable "$2"; then
        note_failure "$1: gemessen '$2' — kein pinnbarer Zustand (erwartet '${exp:-?}')"
        return 0
    fi
    MEASURED["$1"]="$2"
    if [ -z "${EXPECT[$1]+x}" ]; then
        if $UPDATE; then
            log "   PIN $1: (neu) -> $2"
        else
            note_deviation "keine Erwartung fuer $1 (gemessen: $2) — nach Pruefung --update-expectations"
        fi
    elif [ "$exp" != "$2" ]; then
        if $UPDATE; then
            log "   PIN $1: $exp -> $2"
        else
            note_deviation "$1: erwartet '$exp', gemessen '$2'"
        fi
    fi
}

IMAGE_VERSION="$(docker run --rm "$IMAGE" --version 2> /dev/null | awk '{print $NF}')"
[ -n "$IMAGE_VERSION" ] || fail "Image $IMAGE nicht lauffaehig (make docker-build IMAGE_TAG=dev)"
if ! $UPDATE && [ "${EXPECT[EXPECT_VERSION]-}" != "$IMAGE_VERSION" ]; then
    note_deviation "Erwartungen gelten fuer d-migrate '${EXPECT[EXPECT_VERSION]-?}', das Image ist '$IMAGE_VERSION' — messen, pruefen, bewusst neu pinnen"
fi

# --- Stack ------------------------------------------------------------
mcp_e2e_env
log "Image $IMAGE ($IMAGE_VERSION), Dialekte: $(dialect_list)"
mcp_e2e_stack_up
log "Stack bereit"

declare -A CELL CODES
DIALECTS="$(dialect_list)"

for source in $DIALECTS; do
    sdir="$OUT/$source"
    mkdir -p "$sdir"
    log "=== Quelle $source ==="
    generate_ddl "$EXAMPLES_DIR/$FIXTURE_REL" "$source" "$sdir/seed.sql" "$sdir/seed-generate.log"
    seed_rc=$?
    generated_ok "$seed_rc" "$sdir/seed.sql" \
        || { note_failure "$source: generate der Fixture scheiterte ($seed_rc, $sdir/seed-generate.log)"; continue; }
    dialect_clean "$source"
    if ! dialect_apply "$source" "$sdir/seed.sql" "$sdir/seed-apply.log"; then
        note_failure "$source: Fixture-DDL abgelehnt ($sdir/seed-apply.log)"
        continue
    fi
    # Anknuepfungspunkt fuer native Typ-Seeds je Dialekt.
    if [ -f "$SEED_DIR/$source.sql" ]; then
        dialect_apply "$source" "$SEED_DIR/$source.sql" "$sdir/native-seed.log" \
            || { note_failure "$source: nativer Seed abgelehnt ($sdir/native-seed.log)"; continue; }
    fi

    mcp_open
    if ! source_ref="$(mcp_reverse "$source" "$sdir")"; then
        note_failure "$source: Reverse ueber MCP scheiterte ($sdir)"
        mcp_close
        continue
    fi

    for target in $DIALECTS; do
        [ "$target" = "$source" ] && continue
        cell="${source}_${target}"
        cdir="$sdir/$target"
        mkdir -p "$cdir"
        key="$(tr '[:lower:]' '[:upper:]' <<< "$cell")"
        generate_ddl "$sdir/reversed.yaml" "$target" "$cdir/generated.sql" "$cdir/generate.log"
        gen_rc=$?
        if [ "$gen_rc" = 3 ] && known_introducer_invalid "$cdir/generate.log"; then
            # Bekannter Reader-Befund (lib/compare-guards.sh): das Reverse der
            # Quelle ist ungueltig, die CLI erzeugt daraus nichts. Als Zustand
            # gepinnt — verschwindet er, muss die Erwartung bewusst neu.
            CELL[$cell]="INVALID"
            CODES[$cell]="E012-introducer"
            log "   $source -> $target: Reverse der Quelle ungueltig (E012, MySQL-Introducer)"
            check_expect "CELL_$key" "INVALID"
            check_expect "CODES_$key" "E012-introducer"
            continue
        fi
        if ! generated_ok "$gen_rc" "$cdir/generated.sql"; then
            CELL[$cell]="GEN-FAIL"; CODES[$cell]="generate:$gen_rc"
            note_failure "$cell: generate scheiterte ($gen_rc, $cdir/generate.log)"
            continue
        fi
        dialect_clean "$target"
        if ! dialect_apply "$target" "$cdir/generated.sql" "$cdir/apply.log"; then
            # Das Ziel lehnt die aus dem Reverse der Quelle erzeugte DDL ab —
            # ein Befund ueber Reader oder Generator, nicht ueber den
            # Vergleich. Als Zustand mit der Fehlerklasse des Servers
            # gepinnt; die Zelle misst erst wieder, wenn er verschwindet.
            reason="$(apply_error_class "$target" "$cdir/apply.log")"
            CELL[$cell]="APPLY-FAIL"
            CODES[$cell]="$reason"
            log "   $source -> $target: DDL abgelehnt [$reason]"
            check_expect "CELL_$key" "APPLY-FAIL"
            check_expect "CODES_$key" "$reason"
            continue
        fi
        if ! target_ref="$(mcp_reverse "$target" "$cdir")"; then
            CELL[$cell]="REV-FAIL"; note_failure "$cell: Reverse des Ziels scheiterte ($cdir)"; continue
        fi

        # Werkzeug
        args="$(jq -nc --arg l "$source_ref" --arg r "$target_ref" '{left:{schemaRef:$l},right:{schemaRef:$r}}')"
        if ! mcp_tool schema_compare "$args" > "$cdir/tool.json"; then
            CELL[$cell]="CMP-FAIL"; note_failure "$cell: schema_compare scheiterte ($cdir/tool.json)"; continue
        fi
        # Das vollstaendige Ergebnis des Werkzeugs: die Antwort oder, wenn sie
        # gekuerzt ist, das Ueberlauf-Artefakt (beide mit status und findings).
        tool_result="$cdir/tool.json"
        if [ "$(jq -r '.truncated // false' "$cdir/tool.json")" = "true" ]; then
            overflow="$(artifact_id "$(jq -r '.diffArtifactRef' "$cdir/tool.json")")"
            mcp_artifact_text "$overflow" > "$cdir/tool-overflow.json"
            tool_result="$cdir/tool-overflow.json"
        fi
        if ! jq -e '.findings | type == "array"' "$tool_result" > /dev/null 2>&1; then
            CELL[$cell]="CMP-FAIL"; note_failure "$cell: schema_compare ohne lesbare Fundliste ($tool_result)"; continue
        fi
        jq '.findings' "$tool_result" > "$cdir/tool-findings.json"

        # Job
        args="$(jq -nc --arg s "$source_ref" --arg t "$target_ref" --arg k "cm-$cell-$RANDOM$RANDOM" \
            '{sourceUri:$s,targetUri:$t,idempotencyKey:$k}')"
        if ! start="$(mcp_tool schema_compare_start "$args")" \
            || ! status="$(mcp_await "$(jq -r .jobId <<< "$start")" 2> "$cdir/job-failed.json")"; then
            CELL[$cell]="JOB-FAIL"; note_failure "$cell: schema_compare_start scheiterte ($cdir)"; continue
        fi
        printf '%s\n' "$status" > "$cdir/job-status.json"
        job_uri="$(jq -r '.artifacts[0]' <<< "$status")"
        [ "$(jq -r '.artifacts | length' <<< "$status")" = 1 ] \
            || note_failure "$cell: der Job zweier gespeicherter Schemata nennt mehr als das Compare-Artefakt"
        mcp_resource "$job_uri" > "$cdir/job-artifact-meta.json"
        mcp_artifact_text "$(artifact_id "$job_uri")" > "$cdir/job.json"
        [ "$(jq -r '.kind // empty' "$cdir/job-artifact-meta.json")" = "COMPARE" ] \
            || note_failure "$cell: das Job-Artefakt hat nicht die Art COMPARE"
        [ "$(jq -c 'keys' "$cdir/job.json" 2> /dev/null)" = '["findings","status","summary"]' ] \
            || note_failure "$cell: das Job-Artefakt ist nicht {status, summary, findings}"
        jq -S '.findings' "$cdir/job.json" > "$cdir/job-findings.json" 2> /dev/null
        if [ "$(jq -S . "$cdir/tool-findings.json")" != "$(cat "$cdir/job-findings.json")" ]; then
            note_failure "$cell: Werkzeug und Job melden verschiedene Funde ($cdir)"
        fi

        # Waechter — beide Oberflaechen, und ein jq-Fehler ist ein Fehlschlag,
        # kein „nichts gefunden".
        for surface in tool job; do
            result_file="$tool_result"
            [ "$surface" = job ] && result_file="$cdir/job.json"
            if ! compare_guard_items_from_mcp "$result_file" > "$cdir/guard-items-$surface.json" 2> "$cdir/guard-$surface.err" \
                || ! found="$(compare_guard_violations "$cdir/guard-items-$surface.json" 2>> "$cdir/guard-$surface.err")"; then
                note_failure "$cell: Waechter ($surface) nicht auswertbar: $(head -c 300 "$cdir/guard-$surface.err")"
                continue
            fi
            if [ -n "$found" ]; then
                while IFS= read -r v; do note_failure "$cell ($surface): $v"; done <<< "$found"
            fi
        done

        n="$(jq 'length' "$cdir/tool-findings.json")"
        codes="$(jq -r 'map(.code) | group_by(.) | map("\(.[0]):\(length)") | join(" ")' "$cdir/tool-findings.json")"
        CELL[$cell]="$n"
        CODES[$cell]="$codes"
        log "   $source -> $target: $n Fund(e) [$codes]"
        check_expect "CELL_$key" "$n"
        check_expect "CODES_$key" "$codes"
    done
    mcp_close
done

# --- Bericht ----------------------------------------------------------
echo
echo "== Compare-Matrix (Zeile: Quelle, Spalte: Ziel; Zahl der Funde, schema_compare = schema_compare_start)"
printf '%-12s' "Quelle"
for d in $DIALECTS; do printf '%-12s' "$d"; done
printf '\n'
for s in $DIALECTS; do
    printf '%-12s' "$s"
    for t in $DIALECTS; do
        if [ "$s" = "$t" ]; then printf '%-12s' "-"; else printf '%-12s' "${CELL[${s}_$t]:-?}"; fi
    done
    printf '\n'
done
echo
echo "== Codes je Zelle"
for s in $DIALECTS; do
    for t in $DIALECTS; do
        [ "$s" = "$t" ] && continue
        printf '  %-10s -> %-10s %s\n' "$s" "$t" "${CODES[${s}_$t]:-${CELL[${s}_$t]:-?}}"
    done
done

mapfile -t FAILURES < "$FAILURES_FILE"
mapfile -t DEVIATIONS < "$DEVIATIONS_FILE"

if [ "${#FAILURES[@]}" -gt 0 ]; then
    echo
    echo "== ${#FAILURES[@]} nicht pinnbare Abweichung(en):"
    printf '  - %s\n' "${FAILURES[@]}"
fi
if [ "${#DEVIATIONS[@]}" -gt 0 ]; then
    echo
    echo "== ${#DEVIATIONS[@]} Abweichung(en) von den Erwartungen:"
    printf '  - %s\n' "${DEVIATIONS[@]}"
fi

if $UPDATE; then
    # Nur ein Lauf ohne nicht pinnbare Abweichung darf Erwartungen schreiben —
    # sonst pinnte er einen Rueckschritt.
    if [ "${#FAILURES[@]}" -gt 0 ]; then
        log "Erwartungen NICHT geschrieben: ${#FAILURES[@]} nicht pinnbare Abweichung(en)"
        exit 1
    fi
    MEASURED[EXPECT_VERSION]="$IMAGE_VERSION"
    {
        printf '%s' "${EXPECT_HEADER:-# Erwartungen fuer scripts/smoke-compare-matrix.sh$'\n'}"
        for k in $(printf '%s\n' "${!EXPECT[@]}" "${!MEASURED[@]}" | sort -u); do
            v="${MEASURED[$k]-${EXPECT[$k]}}"
            printf '%s="%s"\n' "$k" "$v"
        done
    } > "$EXPECT_FILE.tmp" && mv "$EXPECT_FILE.tmp" "$EXPECT_FILE"
    log "Erwartungen geschrieben: $EXPECT_FILE — den Diff vor dem Commit lesen"
elif [ "${#FAILURES[@]}" -gt 0 ] || [ "${#DEVIATIONS[@]}" -gt 0 ]; then
    exit 1
fi
log "OK — Artefakte in ${OUT#"$EXAMPLES_DIR"/}"
