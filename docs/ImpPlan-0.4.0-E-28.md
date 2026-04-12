# Implementierungsplan: Phase E — Schritt 28

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade  
> **Phase**: E (CLI-Integration)  
> **Schritt**: 28  
> **Status**: Geplant  
> **Referenz**: `implementation-plan-0.4.0.md` §4 Phase E Schritt 28, §6.11, §6.14, §6.12.2

---

## 1. Ziel

- Die CLI-Importpfade gegen echte SQLite-DBs vollständig über `d-migrate data import` verifizieren.
- JSON/YAML/CSV-Round-Trips, `--truncate`, `--on-conflict update` und `--trigger-mode disable` auf SQLite in einem E2E-Block absichern.
- Klare, testbare Exit-2-Assertion bei `--trigger-mode disable` auf SQLite verankern.

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| CLI-Import-Command + Runner-Integration | 24 | ⚠ Muss vorhanden sein |
| Import-Exit-Matrix inkl. Trigger-Mode Mapping | 27 | ⚠ Muss aktiv sein |
| SQLite-Treiber und DB-Lifecycle im Testlauf | E.0.4.0 Baseline | ✅ Vorhanden |

## 3. Architekturentscheidung

- Tests nutzen die vorhandene Command-Struktur (`DMigrate().subcommands(SchemaCommand(), DataCommand())`) und keine externen Container.
- Temporäre SQLite-Dateien pro Test, sauberer Delete-Cleanup, keine globalen Dateien.
- Round-Trip-Fälle bauen auf realem Dateiexport/Dateiimport auf, nicht auf direkten API-Calls, um den vollständigen CLI-Pfad abzudecken.

## 4. Abgrenzung

- Nicht Teil von Schritt 28: Container-E2E mit MySQL/PostgreSQL (Phase F), Performance-Gate (Schritt 22) oder Import-Parser-Einzeltests.
- Keine Architekturänderung in Writer/Reader-Implementierungen; nur Verhalten gegen bestehende API-Verträge.

## 5. Betroffene Dateien

### 5.1 Primär (Tests)

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataImportTest.kt` (neu)
- `adapters/driving/cli/src/test/resources` (bei Bedarf: feste JSON/YAML/CSV-Files)

### 5.2 Begleitend

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt` (falls `subcommands(DataImportCommand())` noch offen)
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt` (optional Reuse kleiner Hilfsfunktionen)

## 6. Umsetzungsschritte

1. Neue Testklasse `CliDataImportTest` einführen und eine lokale SQLite-Helferfunktion für Source/Target-DB + Seed/Query-Assertion bereitstellen.
2. Round-Trip je Format:
   - JSON-Export aus `source.db` → `data import` in `target.db`
   - YAML-Export/-Import derselben Datenmenge
   - CSV-Export/-Import (inkl. `--csv-no-header`/`--csv-null-string`, falls im CLI-Flow nötig)
   - Danach SELECT-basierter Row-Äquivalenzcheck.
3. `--truncate`-Pfad:
   - Target vorab mit Seed-Daten befüllen.
   - Import mit `--truncate` aus Datei durchführen.
   - Vorherige Zielzeilen dürfen nicht erhalten bleiben, neue Daten müssen ersetzt werden.
4. `--on-conflict update`:
   - Target mit abweichenden Versionen derselben PK befüllen.
   - Delta importieren (`--on-conflict update`).
   - Erwartung: bestehende Zeilen werden aktualisiert, PK-kollisionsfrei ergänzt.
5. `--trigger-mode disable` auf SQLite:
   - Tabelle mit Trigger anlegen.
   - Import mit `--trigger-mode disable` ausführen.
   - CLI liefert Exit `2`; Stderr enthält eine klare `triggerMode=disable`-/`not supported for SQLite in 0.4.0`-Meldung.
6. Exit und Robustheit:
   - Erfolgsfälle liefern Exit `0`.
   - Fehlschläge aus Schritt 5 liefern Exit `2` inkl. deterministischer Fehlermeldung.
7. Testdaten/Fixture-Aufräumlogik so gestalten, dass Reihenfolge- und Wiederholbarkeit stabil bleiben.

## 7. Akzeptanzkriterien

- [ ] `docker build -t d-migrate:dev .` baut erfolgreich.
- [ ] JSON, YAML und CSV je mindestens einmal über den vollen CLI-Importpfad auf SQLite verifiziert.
- [ ] `--truncate` auf SQLite importseitig funktional getestet (bestehende Zieldaten werden in dem Lauf ersetzt).
- [ ] `--on-conflict update` ist auf SQLite per CLI testabgedeckt.
- [ ] `--trigger-mode disable` auf SQLite liefert Exit `2` mit klarer Meldung `triggerMode=disable is not supported for SQLite in 0.4.0` (oder äquivalent).
- [ ] Tests laufen ohne Testcontainer (lokale SQLite-Dateien).

## 8. Verifikation

1. `./gradlew :adapters:driving:cli:test --tests "*CliDataImportTest*"`
2. Bestehende `CliDataExportTest` als Negativ-/Vorbereitungsreferenz beibehalten.
3. Optionaler zusätzlicher Smoke: `--truncate --on-conflict`-Explizitfall dokumentieren, falls im Implementierungsstand vorhanden.
