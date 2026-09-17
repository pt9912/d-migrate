# shellcheck shell=bash
# Dialekt-Operationen fuer die Harnesses in scripts/: Stack starten,
# Datenbank leeren, DDL mit dem Client des Dialekts anwenden.
#
# Benutzt von smoke-cross-dialect-roundtrip.sh und smoke-compare-matrix.sh.
# Anwenden laeuft **nicht** ueber d-migrate: weder die CLI noch MCP kennen
# ein „DDL anwenden"; genommen wird der Client, den der Server mitbringt
# (psql, mysql, sqlcmd, sqlplus) bzw. `sqlite3` auf dem Host — die
# SQLite-Datenbank ist eine Datei im gemounteten out/.
#
# Erwartet vom Aufrufer: EXAMPLES_DIR, COMPOSE_FILE, WITH_ORACLE (0|1),
# eine fail()-Funktion und die Variablen aus .env (per `mcp_e2e_env`).

MCP_E2E_SQLITE_FILE="${EXAMPLES_DIR}/out/mcp-e2e.sqlite"

# Die Verbindungsnamen aus .d-migrate.yaml, je Dialekt.
dialect_connection() {  # $1=Dialekt
    case "$1" in
        postgresql) echo mcp_e2e_pg ;;
        mysql) echo mcp_e2e_my ;;
        mssql) echo mcp_e2e_ms ;;
        sqlite) echo mcp_e2e_sqlite ;;
        oracle) echo mcp_e2e_ora ;;
        *) return 1 ;;
    esac
}

# Die Dialekte eines Laufs; Oracle nur mit WITH_ORACLE=1.
dialect_list() {
    if [ "${WITH_ORACLE:-0}" = "1" ]; then
        echo "postgresql mysql mssql sqlite oracle"
    else
        echo "postgresql mysql mssql sqlite"
    fi
}

# Compose mit dem Oracle-Profil, wenn es gebraucht wird.
mcp_e2e_compose() {
    if [ "${WITH_ORACLE:-0}" = "1" ]; then
        docker compose -f "$COMPOSE_FILE" --profile oracle "$@"
    else
        docker compose -f "$COMPOSE_FILE" "$@"
    fi
}

# .env anlegen (aus dem Beispiel) und laden.
mcp_e2e_env() {
    mkdir -p "$EXAMPLES_DIR/out"
    [ -f "$EXAMPLES_DIR/.env" ] || cp "$EXAMPLES_DIR/.env.example" "$EXAMPLES_DIR/.env"
    set -a
    # shellcheck disable=SC1091 # .env entsteht erst zur Laufzeit
    . "$EXAMPLES_DIR/.env"
    set +a
    : "${MCP_E2E_PG_USER:?MCP_E2E_PG_USER not set}"
    MCP_E2E_DMIGRATE_USER="$(id -u):$(id -g)"
    export MCP_E2E_DMIGRATE_USER
}

# Die Server starten und auf „healthy" warten.
mcp_e2e_stack_up() {
    local services="postgres mysql mssql" deadline unhealthy ok="no"
    [ "${WITH_ORACLE:-0}" = "1" ] && services="$services oracle"
    # shellcheck disable=SC2086 # die Dienstliste ist bewusst ein Wort je Dienst
    mcp_e2e_compose up -d $services || fail "stack did not start"
    deadline=$(($(date +%s) + 240))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        unhealthy=$(mcp_e2e_compose ps --format '{{.Service}} {{.Health}}' \
            | awk '$2 != "healthy" {print $1}' | grep -v '^$' || true)
        if [ -z "$unhealthy" ]; then ok="yes"; break; fi
        sleep 5
    done
    [ "$ok" = "yes" ] || fail "not all services became healthy: $unhealthy"
}

_container() {  # $1=Dienst
    mcp_e2e_compose ps -q "$1"
}

# Der PostgreSQL-Dienst faehrt auf dem PostGIS-Image, und die Extension gehoert
# ins Schema `postgis` (initdb-postgres/): in `public` kaemen ihre rund tausend
# Routinen als Anwenderobjekte in jeden Reverse, und `dialect_clean` naehme sie
# mit. Das Init-Verzeichnis laeuft nur beim **ersten** Volume-Init — ein Volume
# von vor dem Image-Wechsel traegt sie nicht. Deshalb geprueft, nicht
# angenommen; ohne die Pruefung maesse der Lauf still etwas anderes.
mcp_e2e_assert_postgis() {
    local schema
    schema="$(docker exec -i "$(_container postgres)" \
        psql -U "$MCP_E2E_PG_USER" -d "$MCP_E2E_PG_DB" -tAc \
        "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'postgis'" \
        2> /dev/null | tr -d '\r')"
    [ "$schema" = "postgis" ] || fail "PostGIS steht nicht im Schema postgis (gefunden: '${schema:-keine Extension}') - ein Volume von vor dem Image-Wechsel? 'make mcp-e2e-purge' und neu starten"
}

# Die Datenbank des Dialekts leeren. Wiederholbar: ein zweiter Lauf faende
# sonst das Schema des ersten, und die DDL scheiterte an „already exists".
dialect_clean() {  # $1=Dialekt
    case "$1" in
        postgresql)
            docker exec -i "$(_container postgres)" \
                psql -U "$MCP_E2E_PG_USER" -d "$MCP_E2E_PG_DB" -q \
                -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;' > /dev/null 2>&1 || true
            ;;
        mysql)
            docker exec -i "$(_container mysql)" \
                mysql -uroot -p"$MCP_E2E_MY_ROOT_PASSWORD" -e \
                "DROP DATABASE IF EXISTS \`$MCP_E2E_MY_DB\`; CREATE DATABASE \`$MCP_E2E_MY_DB\`;" \
                > /dev/null 2>&1 || true
            ;;
        mssql)
            # Die Datenbank neu anlegen: ein DROP der Tabellen muesste die
            # Fremdschluessel-Reihenfolge treffen. Die Ziel-DB ist eine
            # Test-DB ohne Daten.
            docker exec "$(_container mssql)" \
                /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MCP_E2E_MS_PASSWORD" -b \
                -Q "IF DB_ID('$MCP_E2E_MS_DB') IS NOT NULL BEGIN ALTER DATABASE [$MCP_E2E_MS_DB] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$MCP_E2E_MS_DB]; END" \
                > /dev/null 2>&1 || true
            docker exec "$(_container mssql)" \
                /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MCP_E2E_MS_PASSWORD" -b \
                -Q "CREATE DATABASE [$MCP_E2E_MS_DB]" > /dev/null 2>&1 || true
            ;;
        sqlite)
            rm -f "$MCP_E2E_SQLITE_FILE"
            ;;
        oracle)
            # Als APP_USER ueber `user_objects` — kein DBA-Recht, kein
            # `DROP USER`. Einzelne Fehlschlaege sind erwartbar (ein Index
            # verschwindet mit seiner Tabelle) und werden geschluckt.
            docker exec -i "$(_container oracle)" \
                sqlplus -S -L "$MCP_E2E_ORA_USER/$MCP_E2E_ORA_PASSWORD@//localhost:1521/$MCP_E2E_ORA_DB" <<'SQL' > /dev/null 2>&1 || true
                BEGIN
                  FOR o IN (SELECT object_name, object_type FROM user_objects
                             WHERE object_type IN ('TABLE','VIEW','SEQUENCE','PROCEDURE',
                                                   'FUNCTION','TRIGGER','TYPE','INDEX')) LOOP
                    BEGIN
                      EXECUTE IMMEDIATE 'DROP ' || o.object_type || ' "' || o.object_name || '"' ||
                        CASE WHEN o.object_type = 'TABLE' THEN ' CASCADE CONSTRAINTS' ELSE '' END;
                    EXCEPTION WHEN OTHERS THEN NULL;
                    END;
                  END LOOP;
                END;
                /
SQL
            ;;
        *) fail "unbekannter Dialekt: $1" ;;
    esac
}

# DDL anwenden. Rueckgabe != 0, wenn der Server (oder Client) sie ablehnt;
# das Protokoll steht in $3.
dialect_apply() {  # $1=Dialekt $2=DDL-Datei $3=Protokolldatei
    local dialect="$1" ddl="$2" log="$3"
    case "$dialect" in
        postgresql)
            docker exec -i "$(_container postgres)" \
                psql -U "$MCP_E2E_PG_USER" -d "$MCP_E2E_PG_DB" -v ON_ERROR_STOP=1 -q \
                < "$ddl" > "$log" 2>&1
            ;;
        mysql)
            docker exec -i "$(_container mysql)" \
                mysql -u"$MCP_E2E_MY_USER" -p"$MCP_E2E_MY_PASSWORD" "$MCP_E2E_MY_DB" \
                < "$ddl" > "$log" 2>&1
            ;;
        mssql)
            docker cp "$ddl" "$(_container mssql):/tmp/ddl.sql" > /dev/null \
                && docker exec "$(_container mssql)" \
                    /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$MCP_E2E_MS_PASSWORD" \
                    -d "$MCP_E2E_MS_DB" -b -i /tmp/ddl.sql > "$log" 2>&1
            ;;
        sqlite)
            sqlite3 "$MCP_E2E_SQLITE_FILE" < "$ddl" > "$log" 2>&1
            ;;
        oracle)
            # `WHENEVER SQLERROR EXIT FAILURE` ist Pflicht: ohne es meldet
            # SQL*Plus auch bei abgelehntem DDL Exit 0.
            docker cp "$ddl" "$(_container oracle):/tmp/ddl.sql" > /dev/null \
                && docker exec -i "$(_container oracle)" \
                    sqlplus -S -L "$MCP_E2E_ORA_USER/$MCP_E2E_ORA_PASSWORD@//localhost:1521/$MCP_E2E_ORA_DB" \
                    > "$log" 2>&1 <<'SQL'
                WHENEVER SQLERROR EXIT FAILURE
                @/tmp/ddl.sql
SQL
            ;;
        *) fail "unbekannter Dialekt: $dialect" ;;
    esac
}
