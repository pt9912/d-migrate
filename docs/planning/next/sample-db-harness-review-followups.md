# Plan: Sample-DB-Harness — Review-Härtungen (Bash-Robustheit)

> Dokumenttyp: Next-Plan (Folge-Härtung)
> Status: Entwurf (2026-06-20). Scope ausgearbeitet, **Fix folgt**.
> Trigger: `/code-review` auf den Sample-DB-Harness-Diff (Phase 2/2b, Session
> 2026-06-20). Drei verifizierte Bash-Robustheits-Befunde; F1 ist der einzige
> fixwürdige (False-Green), F2/F3 optional.
> Referenzen: [`../done/sample-db-integration-harness.md`](../done/sample-db-integration-harness.md)
> (Umbrella), ADR 0014 (Harness-Mechanik), ADR 0004 (Planning-Struktur).
> Nicht-blockierend für den Harness selbst (Phase 0/1/2/2b sind grün); dies ist
> eine Stärkung der Verifikations-*Schärfe*.

## Kontext

Der Review fand drei Befunde; **zwei vermeintliche False-Green-Klassen wurden
durch `set -o pipefail` widerlegt** (wichtig, damit der Fix nicht über-greift):

- **Widerlegt:** dual-variable Typ-Konvertierungs-Checks (`[ "$a" != "$b" ]` mit
  `*_val`-Helfern). Empirisch bestätigt: unter `set -euo pipefail` propagiert ein
  SQL-Fehler durch das trailing `| tr` der Helfer, der bare-Assignment
  `s=$(my_val …)` **abortiert** das Skript (fail-loud) — kein stiller
  Empty-vs-Empty-Vergleich. Hier ist **kein** Fix nötig.
- **Bestätigt:** der Generator-Schluck in den Paritäts-Schleifen (F1, siehe
  unten) — Prozess-Substitution propagiert den Generator-Exit *nicht*, auch nicht
  unter `pipefail`.

## F1 — Paritäts-Schleifen-Generator-Schluck (False-Green) · fixwürdig

**Mechanik.** Alle drei Smoke-Skripte prüfen Zeilen-Parität mit dem Muster:

```bash
while IFS= read -r t; do … done < <(mysql_root -e "SELECT … FROM information_schema.tables …")
```

Prozess-Substitution `< <(…)` leitet den **Exit-Code des Generators nicht** an die
Haupt-Shell — selbst mit `set -euo pipefail`. Errt das Tabellenlisten-Query
(z. B. transienter Connection-Drop zwischen Transfer und Parität), läuft die
Schleife **0×**, `mismatch` bleibt `0`, und das Skript loggt
`row-count parity OK (all N tables)` — obwohl **keine einzige Tabelle verglichen**
wurde. Die Erfolgsmeldung druckt sogar `$src_tables` (aus einem früheren Schritt)
und verstärkt den falschen Eindruck.

**Warum fixwürdig (trotz niedriger Trigger-W.).** Eine Paritäts-Prüfung, die still
grün sein kann ohne zu vergleichen, ist genau die **„Harness-Lüge"-Klasse**
(behauptete Kontrolle, die real nicht greift) — vom Regelwerk verboten,
unabhängig von der Eintrittswahrscheinlichkeit.

**Betroffene Stellen:**
- `examples/sample-db/scripts/smoke-cross.sh` (Parität-Schleife, ~Z. 126-132)
- `examples/sample-db/scripts/smoke-cross-pg2my.sh` (~Z. 134-141)
- `examples/sample-db/scripts/smoke-sqlite.sh` (~Z. 94-104; Generator = `sqlite3 … sqlite_master`)

**Fix (Scope).** Tabellenliste **erst in eine Variable** holen, dann iterieren —
unter `set -e` abortet der Assignment bei Generator-Fehler:

```bash
tables=$(mysql_root -e "SELECT … ;") || fail "could not list source tables"
[ -n "$tables" ] || fail "source table list is empty"
while IFS= read -r t; do … done <<< "$tables"
```

Plus eine **Untergrenze-Assertion** (verglichene Tabellen == erwartete Anzahl), die
einen 0-Iterationen-Fall hart fängt — doppelte Absicherung gegen die False-Green-
Klasse.

**DoD.** Alle drei Skripte: Generator-Fehler bricht den Lauf (kein „parity OK");
Anzahl verglichener Tabellen wird gegen die erwartete Zahl asserted; je ein
Re-Run bleibt grün.

## F2 — Word-Splitting bei Leerzeichen im Pfad · optional (latent)

`DRUN="docker run … -v $EXAMPLES_DIR:/work …"` (`smoke-sqlite.sh` ~Z. 37) und
`COMPOSE="docker compose -f $COMPOSE_FILE"` (cross-Skripte), unquoted expandiert,
brechen, wenn der Repo-Pfad ein **Leerzeichen** enthält (`-v /a b/…` → zwei
Tokens). **Nicht** das committed Layout; reine latente Fragilität. Fix: Bash-Array
für die Kommandozeile (`DRUN=(docker run … -v "$EXAMPLES_DIR:/work" …)`; Aufruf
`"${DRUN[@]}"`) statt String-Splitting.

## F3 — Decimal→REAL-Präzisions-Check tautologisch · optional (test-quality)

`smoke-sqlite.sh` (~Z. 106-111): Quelle `Track.UnitPrice` ist `NUMERIC(10,2)` →
SQLite-NUMERIC-Affinität = float8; Ziel `REAL` = float8. Beide Seiten halten
identische Bitmuster, `ROUND(SUM(…),2)` ist **per Konstruktion** gleich → der
Check kann keinen echten Präzisionsverlust fangen (nur groben Zeilenverlust, schon
durch die Paritäts-Prüfung gedeckt). Kein Bug, aber der Check verifiziert nicht,
was sein Name behauptet. Option: entweder ehrlicher benennen (Wert-Round-Trip-
Stichprobe) oder durch eine Spalte mit echtem Decimal→REAL-Grenzfall ersetzen.

## Akzeptanzkriterien

- **F1 behoben** in allen drei Skripten (DoD oben), je Re-Run grün.
- F2/F3: entweder behoben **oder** als bewusste Nicht-Ziele hier vermerkt.
- `make docs-check` grün.

## Nicht-Ziel

- Keine Funktionsänderung am Harness-Flow (reverse/generate/transfer bleiben).
- Kein Refactor der `*_val`-Helfer (pipefail schützt sie bereits, siehe Kontext).
