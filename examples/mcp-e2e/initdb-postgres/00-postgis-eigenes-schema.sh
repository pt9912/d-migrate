#!/usr/bin/env bash
# Init des PostgreSQL-Dienstes im MCP-E2E-Harness (laeuft einmalig beim ersten
# Volume-Init).
#
# Der Dienst faehrt auf dem PostGIS-Image, damit Geometrie-Faelle ueberhaupt
# messbar sind. Dieses Verzeichnis **ersetzt** das Init-Verzeichnis des Images
# (das die Extension in `public` anlegen wuerde), und zwar aus zwei Gruenden:
#
#   1. Der Reverse liest die Routinen der Datenbank. Steht PostGIS in `public`,
#      kommen rund tausend Funktionen als Anwenderobjekte mit und
#      ueberschwemmen jede PostgreSQL-Zelle der Matrix.
#   2. `dialect_clean` raeumt PostgreSQL mit `DROP SCHEMA public CASCADE` und
#      naehme die Extension mit — jeder zweite Lauf stuende ohne sie da.
#
# Deshalb: eigenes Schema `postgis`, und der `search_path` der Datenbank nennt
# es (die Konfiguration, die die PostGIS-Doku fuer diesen Fall empfiehlt).
# `ALTER DATABASE` haelt ueber Sitzungen hinweg und ueberlebt das Leeren.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE SCHEMA IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis SCHEMA postgis;
ALTER DATABASE "$POSTGRES_DB" SET search_path = public, postgis;
SQL
