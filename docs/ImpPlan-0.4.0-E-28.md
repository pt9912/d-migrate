# Implementierungsplan: Phase E — Schritt 28

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade  
> **Phase**: E (CLI-Integration)  
> **Schritt**: 28  
> **Status**: Abgeschlossen  
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

### 5.2 Referenz (bestehendes Muster)

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt` — Hilfsfunktionen und CLI-Aufrufmuster

## 6. Umsetzungsschritte

### 6.1 Testklasse und Hilfsfunktionen

Neue Testklasse `CliDataImportTest` (Kotest FunSpec) mit folgenden Hilfsfunktionen,
angelehnt an `CliDataExportTest`:

- `cli()` = `DMigrate().subcommands(SchemaCommand(), DataCommand())`
- `createSampleDatabase()` — SQLite mit `users(id INTEGER PK, name TEXT NOT NULL)`, 3 Rows (alice, bob, charlie)
- `createTargetDatabase()` — leere SQLite mit demselben Schema `users` (Import-Ziel)
- `captureStdout {}`, `captureStderr {}` — System.out/err Umleitung
- `queryAll(db, table)` — `SELECT *` als `List<Map<String, Any?>>` für Assertions
- `beforeSpec { registerDrivers() }` / `afterSpec { DatabaseDriverRegistry.clear() }`

### 6.2 Round-Trip je Format

Drei Tests, jeweils:

1. **JSON-Round-Trip**: Export source.db → temp JSON-Datei → Import in target.db → SELECT-Vergleich (3 Rows)
2. **YAML-Round-Trip**: Export → temp YAML → Import → SELECT-Vergleich
3. **CSV-Round-Trip**: Export → temp CSV → Import → SELECT-Vergleich

CLI-Invokation (Import):
```
data import --target sqlite:///target.db --source data.json --format json --table users
```

Kein `-c` Config nötig — `--target` akzeptiert direkte URLs.

### 6.3 `--truncate`-Pfad

- Target vorab mit Seed-Daten befüllen (id=10, name="pre-existing")
- Import mit `--truncate` aus JSON-Datei durchführen
- Assertion: pre-existing weg, nur die 3 importierten Rows vorhanden

### 6.4 `--on-conflict update`

- Target mit abweichenden Versionen befüllen: (id=1, "OLD_ALICE"), (id=2, "OLD_BOB")
- Delta importieren mit `--on-conflict update`
- Assertion: id=1 → "alice" (aktualisiert), id=2 → "bob" (aktualisiert), id=3 → "charlie" (neu eingefügt)

### 6.5 `--trigger-mode disable` auf SQLite

- Import-Versuch mit `--trigger-mode disable` gegen SQLite
- `shouldThrow<ProgramResult> { ... }.statusCode shouldBe 2`
- stderr `shouldContain "not supported"`
- Kein Trigger-Setup nötig — die Prüfung erfolgt im Runner vor dem eigentlichen Import (Zeile 239-247 in `DataImportRunner.kt`)

### 6.6 Directory Import

- Export mit `--split-files` in temp-Verzeichnis
- Import mit `--source <dir>` ohne `--table` (Tabellenerkennung aus Dateinamen)
- SELECT-Vergleich

## 7. Akzeptanzkriterien

- [x] `docker build -t d-migrate:dev .` wird als Build/Test-Workflow gemäß [`README.md`](../README.md) verwendet und baut erfolgreich.
- [x] JSON, YAML und CSV je mindestens einmal über den vollen CLI-Importpfad auf SQLite verifiziert.
- [x] `--truncate` auf SQLite importseitig funktional getestet (bestehende Zieldaten werden in dem Lauf ersetzt).
- [x] `--on-conflict update` ist auf SQLite per CLI testabgedeckt.
- [x] `--trigger-mode disable` auf SQLite liefert Exit `2` mit klarer Meldung (oder äquivalent).
- [x] Tests laufen ohne Testcontainer (lokale SQLite-Dateien).

## 8. Verifikation

1. Gezielter Testlauf:
```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driving:cli:test --tests dev.dmigrate.cli.CliDataImportTest" \
  -t d-migrate:e28 .
```

2. Vollständiger Build inkl. Coverage:
```bash
docker build -t d-migrate:dev .
```

3. Bestehende `CliDataExportTest` als Negativ-/Vorbereitungsreferenz beibehalten.
