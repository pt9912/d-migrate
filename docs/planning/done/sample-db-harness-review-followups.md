# Plan: Sample-DB-Harness — Review-Härtungen (Bash-Robustheit)

> Dokumenttyp: Next-Plan (Folge-Härtung)
> Status: **Fix geliefert (2026-06-25) → graduiert nach done/.** F1 in **vier** Skripten
> behoben (Audit fand eine vierte Stelle über die Doc-Scope hinaus, s. u.), F3 ehrlich
> umbenannt, F2 als bewusstes Nicht-Ziel vermerkt. Closure am Ende.
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

**Betroffene Stellen (behoben):**
- `examples/sample-db/scripts/smoke-cross.sh` ✅
- `examples/sample-db/scripts/smoke-cross-pg2my.sh` ✅
- `examples/sample-db/scripts/smoke-sqlite.sh` (Generator = `sqlite3 … sqlite_master`) ✅
- `examples/sample-db/scripts/smoke.sh` ✅ — **vierte Stelle, vom Audit gefunden** (Doc-Scope
  war auf den Phase-2/2b-Diff begrenzt; der Phase-1-PG→PG-Smoke trägt denselben Defekt). Hier
  **Untergrenze**-Assertion (`compared > 0`) statt exakt: die Schleife listet `pg_tables`
  (relkind 'r', inkl. Partition-Kinder, ohne Parent), `src_tables` zählt aber
  `information_schema` BASE TABLE — verschiedene Filter, exakt wäre ein False-Red-Risiko.

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

**Entscheidung (2026-06-25): bewusstes Nicht-Ziel.** Latent (kein Leerzeichen im committed
Repo-Pfad), aber der Array-Umbau ist invasiv (jede `$COMPOSE`/`$DRUN`-Aufrufstelle) für
Null realen Gewinn in diesen Beispiel-Skripten. Triggert ein echter Bedarf (Repo-Pfad mit
Leerzeichen), ist der Array-Fix oben vorgezeichnet.

## F3 — Decimal→REAL-Präzisions-Check tautologisch · optional (test-quality)

`smoke-sqlite.sh` (~Z. 106-111): Quelle `Track.UnitPrice` ist `NUMERIC(10,2)` →
SQLite-NUMERIC-Affinität = float8; Ziel `REAL` = float8. Beide Seiten halten
identische Bitmuster, `ROUND(SUM(…),2)` ist **per Konstruktion** gleich → der
Check kann keinen echten Präzisionsverlust fangen (nur groben Zeilenverlust, schon
durch die Paritäts-Prüfung gedeckt). Kein Bug, aber der Check verifiziert nicht,
was sein Name behauptet. Option: entweder ehrlicher benennen (Wert-Round-Trip-
Stichprobe) oder durch eine Spalte mit echtem Decimal→REAL-Grenzfall ersetzen.

**Behoben (2026-06-25): ehrlich umbenannt.** In SQLite ist NUMERIC↔REAL beidseitig float8 —
es gibt **keinen** Präzisionsdelta zu fangen; ein „echter Grenzfall" existiert für dieses
Typ-Paar nicht. Daher die ehrliche Option: Log/Kommentar in `smoke-sqlite.sh` auf
„Track.UnitPrice value round-trip (SUM sample)" umgestellt und explizit vermerkt, dass kein
Präzisionsverlust fangbar ist. Live-grün bestätigt.

## Akzeptanzkriterien

- [x] **F1 behoben** in allen betroffenen Skripten (DoD oben) — **vier** statt drei
      (smoke.sh ergänzt); `bash -n` grün; SQLite-Smoke live grün (`parity OK (all 11 tables)`).
- [x] F2 als bewusstes Nicht-Ziel vermerkt; F3 behoben (ehrlich umbenannt).
- [x] `make docs-check` grün.

## Nicht-Ziel

- Keine Funktionsänderung am Harness-Flow (reverse/generate/transfer bleiben).
- Kein Refactor der `*_val`-Helfer (pipefail schützt sie bereits, siehe Kontext).

## Closure (2026-06-25)

**Fix geliefert → graduiert nach `done/`.** F1 (Paritäts-Schleifen-Generator-Schluck,
False-Green der „Harness-Lüge"-Klasse) in **vier** Smoke-Skripten behoben: Tabellenliste
zuerst in eine Variable (bricht bei Generator-Fehler via `|| fail`), Non-Empty-Check, plus
eine Anzahl-Assertion (exakt gegen die gleich-gefilterte Erwartungszahl bei
smoke-cross/-cross-pg2my/-sqlite; **Untergrenze** `> 0` bei smoke.sh wegen
`pg_tables`-vs-`information_schema`-Filterdifferenz). `smoke.sh` war **nicht** im
ursprünglichen Doc-Scope — der Audit fand den identischen Defekt im Phase-1-Smoke und hat
ihn mitgezogen.

F3 ehrlich umbenannt (Wert-Round-Trip statt „Präzision"; in SQLite ist NUMERIC↔REAL float8,
kein Präzisionsverlust fangbar). F2 als bewusstes Nicht-Ziel (latent, invasiv vs. Null
Gewinn). Verifikation: `bash -n` aller vier Skripte, SQLite-Smoke **live grün**, `docs-check`
grün. Die schwereren Cross-/Scale-Smokes (MySQL+PG-Compose) sind regulär per CI-Workflow
abgedeckt; der Change ist eine mechanische Loop-Input-Refaktorierung ohne Happy-Path-Wirkung.

Kein Carve-Out offen.
