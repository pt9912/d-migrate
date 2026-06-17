# Pilot-Validierungs-Playbook

> **Stand:** 16.06.2026 · **Zielgruppe:** Maintainer, die einen Pilot-Lauf
> beauftragen. **Wiederverwendbar:** versionsunabhängig — für jeden Beta-/RC-
> Milestone mit Pilot-Deliverable. **Aktueller Lauf:** 0.9.9 (Lastenheft 9.2).

## 1. Zweck und wann

Die Pilot-Validierung prüft d-migrate **end-to-end gegen reale Beispiel-
Datenbanken** in der Rolle eines Pilotanwenders — als Abnahmeschritt vor einem
RC-Cut. Sie wird pro Milestone einmal gefahren; das Ergebnis ist ein
strukturierter Report mit priorisierten Befunden.

**Ehrlichkeits-Vorbehalt:** Lastenheft 9.2 verlangt „mindestens 5 Tester"
(Menschen). Ein Code-Agent ersetzt das **nicht** — er liefert eine
automatisierte Validierung über ≥5 repräsentative Szenarien als Breiten-Proxy.
Das erhöht die Konfidenz und findet Bugs, ersetzt aber die externe Pilotgruppe
nicht; im Report ist das so auszuweisen.

## 2. Voraussetzungen

- **Docker** (Kandidaten-DBs + d-migrate-Runtime) und **Netzzugang** (um die
  Sample-Dumps zu ziehen). Ohne Netz: Rückfall auf die Daten unter
  [`examples/bi-demo/`](../../examples/bi-demo) — geringerer Realismus.
- d-migrate via GHCR-Image (`ghcr.io/pt9912/d-migrate:latest`) oder
  `make`-Build.
- **Keine** echten Kundendaten/Secrets; Verbindungen über `.d-migrate.yaml` /
  `${VAR}`.

## 3. Referenzierte Artefakte

Der Agent liest zuerst diese Dokumente (im Brief unten als Pfade genannt):

- [Migrations-Leitfaden](../user/migrations-leitfaden.md) — Workflow, Playbooks, Abnahme-Checkliste
- [`guide.md`](../user/guide.md) — exakte Befehle/Flags
- [API-Referenz](../user/api-referenz.md) · [`spec/cli-spec.md`](../../spec/cli-spec.md) — CLI-Vertrag, Exit-Codes
- [Administrationshandbuch](../user/administrationshandbuch.md) — Deployment, Verbindungen
- [Test-Database-Candidates](../planning/open/test-database-candidates.md) — Kandidaten + Teststaffelung
- [`examples/bi-demo/`](../../examples/bi-demo) — Referenz für docker-compose + d-migrate-Aufruf
- [Performance-Benchmarks](performance-benchmarks.md) — falls Perf-Stichproben Teil des Laufs sind
- [ADR 0004](../adr/0004-documentation-and-planning-structure.md) — Ablageort des Reports

## 4. Agent-Brief (kopierbar)

Den folgenden Block einem Code-Agent übergeben. `<version>` durch die aktive
Version ersetzen (aktuell `0.9.9`).

```text
ROLLE & ZIEL
Du bist Pilot-Tester für d-migrate (aktive Version <version>). Validiere das Tool
end-to-end gegen reale Beispiel-Datenbanken und berichte schonungslos, was
funktioniert und was bricht — als Beta-Pilot-Validierung (Lastenheft 9.2),
Vorbereitung des 1.0.0-RC. Du behebst KEINE Bugs in diesem Lauf — du validierst
und berichtest; gefundene Fixes schlägst du als priorisierte Issues vor.

EHRLICHKEITS-VORBEHALT
Lastenheft 9.2 verlangt „mindestens 5 Tester" (Menschen). Ein Agent ersetzt das
nicht. Du lieferst eine automatisierte Validierung über >=5 repräsentative
Szenarien als Breiten-Proxy und weist genau das im Report aus — kein Vortäuschen
einer menschlichen Pilotgruppe. Keine erfundenen Zahlen; bei Perf-Messung die
Maschine/Container-Umgebung nennen.

ZUERST LESEN (Kontext, nicht überspringen)
- docs/user/migrations-leitfaden.md  — Workflow, Playbooks, Abnahme-Checkliste
- docs/user/guide.md                 — exakte Befehle/Flags
- docs/user/api-referenz.md, spec/cli-spec.md — CLI-Vertrag, Exit-Codes
- docs/user/administrationshandbuch.md — Deployment, Verbindungen
- docs/planning/open/test-database-candidates.md — Kandidaten + Teststaffelung
- examples/bi-demo/                   — Referenz docker-compose + d-migrate-Aufruf

UMGEBUNG (Voraussetzungen: Docker + Netzzugang für Sample-Dumps)
- d-migrate via GHCR-Image (ghcr.io/pt9912/d-migrate:latest) oder make-Build.
- Kandidaten-DBs in Containern, Beispiel-Dumps laden: Pagila (PostgreSQL),
  Sakila (MySQL), Employees (MySQL); SQLite-Ziel als Datei.
- Keine echten Kundendaten/Secrets; Verbindungen über .d-migrate.yaml / ${VAR}.

SZENARIEN (>=5, Teststaffelung Smoke -> Compatibility -> Scale folgen)
1. Smoke: Pagila PG -> reverse -> validate -> generate --split pre-post ->
   neues PG-Schema anlegen -> data transfer -> schema compare (muss clean sein).
2. PG -> MySQL (Pagila): Sequenz-Emulation (dmg_sequences), --trigger-mode
   disable, --on-conflict update.
3. MySQL -> PG (Sakila oder Employees): TINYINT(1)<->BOOLEAN, MySQL-Sequence-
   Emulation -> native PG-Sequenzen.
4. -> SQLite (--sqlite-named-sequences helper_table): Materialized Views (W103),
   E056-Verhalten.
5. Round-Trip PG -> MySQL -> SQLite (Abnahmeziel 8.6): jede Stufe mit
   schema compare einzeln abnehmen.
6. Feature-Stichproben: inkrementeller Export (--since-column/--since),
   Parquet-Transport (--format parquet), data profile.

PRO SZENARIO ERFASSEN
- exakte Befehle + Exit-Codes (gegen api-referenz 2.2 prüfen),
- schema-compare-Ergebnis (clean / erklärte Differenzen),
- Datenintegrität: Zeilenzahlen je Tabelle Quelle<->Ziel, Stichproben,
  Sequenz-Folgewerte,
- Befunde: Bug/Drift mit minimalem Repro (erwartet vs. tatsächlich). UNBEDINGT
  echten Tool-Bug von erwarteter Dialekt-Grenze (W103/E053/E056) unterscheiden.

ABNAHME je Szenario (Leitfaden-Checkliste 10.4)
[ ] reverse + validate ohne offene Errors
[ ] pre-data/post-data korrekt, Daten geladen
[ ] schema compare ohne unerwartete Differenzen
[ ] Zeilenzahlen + Stichproben verifiziert
[ ] Sequenzen korrekt

DELIVERABLE
- Strukturierter Markdown-Report (Ablage gemäß ADR 0004, Vorschlag:
  docs/planning/in-progress/pilot-validation-<version>.md): pro Szenario
  Setup/Befehle/Ergebnis/Exit-Codes/Befunde + Gesamt-Verdikt + priorisierte
  Issue-Liste (Titel, Schwere, Repro).
- make docs-check muss für den Report grün bleiben (Links/Anker/Pfade).
- Alle Behauptungen mit echten Läufen belegen; nichts erfinden.

GRENZEN
- Keine Tool-Fixes in diesem Lauf — nur validieren + berichten.
- Ersetzt keine menschliche Pilotgruppe (siehe Ehrlichkeits-Vorbehalt).
```

### 4.1 Re-Run-Variante (Re-Validierung nach Fixes)

Ist dies **nicht der Erstlauf**, sondern eine Re-Validierung nach Bugfixes,
**existiert der Erst-Report bereits** unter
`docs/planning/in-progress/pilot-validation-<version>.md`. Eine frische Instanz
würde ihn sonst überschreiben. Für einen Re-Run daher:

- Report in eine **neue** Datei schreiben:
  `docs/planning/in-progress/pilot-validation-<version>-rerun.md`.
- Die seit dem Erstlauf behobenen Blocker **gezielt gegen ihr Original-Repro**
  nachprüfen, nicht nur die Standard-Szenarien.

Dazu den folgenden Block dem Agent-Brief (Abschnitt 4) **voranstellen**:

```text
RE-VALIDIERUNGSLAUF (nicht der Erstlauf)
Dies ist ein Re-Run nach Bugfixes. Der Erst-Report liegt in
docs/planning/in-progress/pilot-validation-<version>.md (NICHT überschreiben).
Schreibe deinen Report in docs/planning/in-progress/pilot-validation-<version>-rerun.md.

Die seit dem Erstlauf behobenen Blocker findest du im Erst-Report
(Befunds-/Behebungsabschnitt) und ggf. im Blocker-Tracker
(docs/planning/in-progress/*-blocker-*-tracker.md).

AUFTRAG ZUSÄTZLICH ZU DEN STANDARD-SZENARIEN
- Verifiziere jeden behobenen Blocker explizit gegen sein Original-Repro aus dem
  Erst-Report — erwartet: ehemals fehlschlagende Pfade laufen jetzt durch ODER
  steigen sauber mit Note/Skip-Code aus (kein invalides DDL, kein stiller Abbruch).
- Markiere je Blocker: BEHOBEN / TEILWEISE / REGRESSION / NEUER BEFUND.
- Neue/P3-Befunde wie gehabt als priorisierte Issue-Liste.
- Beachte bewusste Nicht-Ziele (z. B. ADRs, die Pfade als out of scope fixieren) —
  diese sind KEINE Bugs.
```

## 5. Abnahme-Kriterien (Auftraggeber-Sicht)

Der Lauf gilt als ausreichend, wenn:

- ≥5 Szenarien gefahren wurden, davon mindestens je eine Cross-Dialekt-Richtung
  PG↔MySQL und ein Pfad nach SQLite;
- jedes Szenario die Leitfaden-Checkliste (§10.4) durchläuft oder die Abweichung
  als Befund dokumentiert ist;
- der Report echte Läufe belegt (Befehle, Exit-Codes, Zeilenzahlen) und Befunde
  von erwarteten Dialekt-Grenzen (W103/E053/E056) trennt.

## 6. Report-Ablage

Der Report wandert nach `docs/planning/in-progress/pilot-validation-<version>.md`
(Lebenszyklus: [ADR 0004](../adr/0004-documentation-and-planning-structure.md)).
Gefundene Bugs werden als Folge-Tasks/Issues geführt, nicht im Pilot-Lauf
selbst gefixt.

## 7. Grenzen

Das Playbook liefert **Breite und Reproduzierbarkeit**, nicht die menschliche
Außenperspektive echter Pilotanwender. Die formale Lastenheft-9.2-Anforderung
(≥5 menschliche Tester) bleibt eine separate, nicht-automatisierbare Abnahme;
der Agent-Lauf ist ein starker Vor-Filter, der Bugs vor der menschlichen Runde
ausräumt.

## Verwandte Dokumentation

- [Migrations-Leitfaden](../user/migrations-leitfaden.md) · [Performance-Benchmarks](performance-benchmarks.md)
- [Test-Database-Candidates](../planning/open/test-database-candidates.md) · [`examples/bi-demo/`](../../examples/bi-demo)
- [Roadmap (0.9.9 / 1.0.0-QA-Ziele)](../planning/in-progress/roadmap.md)
