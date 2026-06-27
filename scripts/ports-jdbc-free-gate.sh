#!/usr/bin/env bash
# Architektur-Fitness-Function (ADR 0022 / Slice ports-jdbc-entkopplung, P5).
#
# Die Hexagon-Ports-Schicht (hexagon:ports*) darf `java.sql` weder importieren noch
# inline nutzen — JDBC lebt ausschliesslich in den Adaptern (das neutrale
# `DatabaseConnection` ist die Port-Waehrung; Adapter unwrappen via `asJdbc()`).
# Dieses Gate verhindert, dass der in P1-P4 entfernte Leak zurueckkehrt.
#
# Doku-Erwaehnungen (KDoc/Kommentar) sind erlaubt — geprueft wird nur echter Code.
# Bewusst ein Shell-Gate (Projekt-Idiom, vgl. solid-suppression-gate.sh) statt einer
# Detekt-ForbiddenImport-Regel: letztere greift global und wuerde die Adapter (die
# java.sql legitim nutzen) mitflaggen.
set -euo pipefail
cd "$(dirname "$0")/.."

MODULES="hexagon/ports-common hexagon/ports-read hexagon/ports-write hexagon/ports-execute hexagon/ports"

# Treffer sammeln, Kommentar-/KDoc-Zeilen (`*`, `//`, `/*`) ausschliessen.
hits="$(grep -rnE "java\.sql" $MODULES --include=*.kt 2>/dev/null \
          | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)"

if [ -n "$hits" ]; then
  echo "FAIL: java.sql in der Ports-Schicht (ADR 0022 — JDBC gehoert in die Adapter):"
  echo "$hits"
  echo ""
  echo "Fix: neutrales DatabaseConnection verwenden; im Adapter via asJdbc() unwrappen."
  exit 1
fi

echo "OK: hexagon:ports* ist java.sql-frei (ADR 0022 P5)."
