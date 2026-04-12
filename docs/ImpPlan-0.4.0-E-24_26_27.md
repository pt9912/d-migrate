# Implementierungsplan: Phase E – Schritte 24, 26, 27 (gebündelt)

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade  
> **Phase**: E (CLI-Integration)  
> **Schritte**: 24 + 26 + 27  
> **Status**: Abgeschlossen (per Docker verifiziert, 2026-04-12)  
> **Referenz**: `implementation-plan-0.4.0.md` §3.7, §6.11

---

## 1. Ziel

- `data import` vollständig in den CLI/Runner-Layer heben, inklusive aller Optionen aus §3.7.1.
- `NamedConnectionResolver` für Import/Export um `database.default_source` und `database.default_target` ergänzen.
- CLI-Fehlermodi für `data import` auf die Exit-Codes in §6.11 mappen.
- Die drei Punkte als zusammenhängenden Implementierungsblock liefern, damit Parsing, Verbindungsauflösung und Exit-Verhalten konsistent sind.
- Fokus der drei Schritte: `DataImportCommand`, `NamedConnectionResolver`, Exit-Code-Mapping für `data import`.
- Ist-Stand: `DataExportRunner` ist vorhanden und dient als Architekturvorlage; `data import` ist in CLI und Hexagon noch nicht implementiert.

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `docs/implementation-plan-0.4.0.md` §3.7 / §6.11 | Basis | ✅ Referenziert |
| `Hexagon / Streaming`-Kontrakte (`ImportInput`, `StreamingImporter`) | Phase D (19–23) | ✅ Vorhanden |
| Bestehender CLI-Bootstrap (`DataCommand`, `DataExportCommand`) | 0.3.0 / bestehend | ✅ Vorhanden |
| Bestehender `NamedConnectionResolver`-Skeleton | 0.3.0 | ✅ Vorhanden |
| `DataImportRunner` / `DataImportRequest` in Hexagon (`application`) | 0.4.0 | ❌ Neu zu implementieren |
| `DataImportCommand` im CLI | 0.4.0 | ❌ Neu zu implementieren |
| Exit-Mapping im bestehenden CLI-Stil (`ProgramResult`) | 0.3.0 | ✅ Vorhanden |

## 3. Architekturentscheidung

- `DataImportCommand` + `DataImportRunner` bilden die Trennung CLI-Parsing/Domain-Logik analog zu `DataExportCommand` und `DataExportRunner`.
- `ImportInput`-Auflösung und Exit-Mapping bleiben in CLI-Command plus Runner, nicht im Streaming-/Writer-Layer.
- Resolver-Defaults (`default_source`/`default_target`) werden zentral in `NamedConnectionResolver` abgebildet; kein zusätzlicher lokaler Resolver in `DataImportCommand`.
- Exit-Mapping wird im Runner erzeugt und im Command über `ProgramResult(exitCode)` in den Prozess-Exit umgesetzt.
- Rückwärtskompatibilität im Resolver bleibt gewahrt: bestehende `resolve(..)`-Overloads delegieren auf neue `resolveSource(null)` / `resolveTarget(null)`-Signaturen.

## 4. Abgrenzung

- Nicht Teil von Schritt 24/26/27
- `--incremental`/`--since-column` auf der Import-Seite.
- Phase-F-E2E-Tests gegen Container.
- Vollständige Docs-Überarbeitung in `docs/cli-spec.md`.
- Reader-/Writer-Implementierungen außerhalb des CLI-Wrappers.
- Neuer Exit-Dispatcher wie `ErrorMapper.kt`; es wird der bestehende `ProgramResult`-Pfad verwendet.

## 5. Betroffene Dateien

### 5.1 Primär

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommand.kt` (Subcommand-Registrierung)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt` (neu)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt` (falls Strukturentscheidung dort zentral weitergeführt wird)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt` (neu)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRequest.kt` (neu; optional als separates Datenobjekt oder in Runner-Datei)

### 5.2 Tests

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/config/NamedConnectionResolverTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportCommandTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportCommandParserTest.kt` (wenn Parser-Tests für Clikt-Fälle beibehalten werden)
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataImportTest.kt` (optionaler CLI-Smoke)

## 6. Umsetzungsschritte

## 6.1 Schritt 24 – `DataImport` in CLI+Hexagon-Runner vollständig aufbauen

1. `DataImportRunner` + `DataImportRequest` in `hexagon/application` implementieren (parallele Vorlage `DataExportRunner`).
2. `DataImportCommand` mit allen Flags aus §3.7.1 ergänzen: `--target`, `--source` (required in CLI-Definition), `--format`, `--table`, `--tables`, `--schema`, `--on-error`, `--on-conflict`, `--trigger-mode`, `--truncate`, `--disable-fk-checks`, `--reseed-sequences` (inkl. `--no-reseed-sequences`), `--encoding`, `--csv-no-header`, `--csv-null-string`, `--chunk-size`.
3. `--format` strikt auf `json|yaml|csv` einschränken, kein Content-Sniffing; bei fehlendem/unknown Format bei Datei-Source oder stdin -> Exit 2.
4. `--table`/`--tables`-Matrix finalisieren: `-` + Datei benötigt `--table`, Verzeichnis nutzt `--tables`, inkompatible Mischformen liefern deterministische Exit-2-Fehler.
5. `ImportInput`-Ableitung klar trennen:
   - `ImportInput.Stdin(table, System.in)` für `-`
   - `ImportInput.SingleFile(table, path)` für Datei
   - `ImportInput.Directory(path, tables, tableOrder?)` für Verzeichnis
6. `DataCommand` um `data import` ergänzen.
7. Source-Pfadprüfung im Runner/Command: nicht existierende Datei -> Exit 2; nicht lesbare Quelle/Typfehler -> deterministischer Exit-2 mit präziser Meldung.

## 6.2 Schritt 26 – `NamedConnectionResolver` aktiviert Defaults

1. `resolveSource(source: String?)` um `database.default_source` erweitern.
2. `resolveTarget(target: String?)` ergänzen für `database.default_target`.
3. Resolver-Regelwerk aus §3.7.3 vollständig abbilden:
   - `resolveTarget` nutzt bei `null` und gesetztem Default direkt URL oder `database.connections`-Auflösung.
   - Default-Name wird über `database.connections` aufgelöst.
   - fehlt der Default bei zwingendem Ziel (z. B. zwingender Laufkontext) -> Exit 2 über Command/Runner.
4. Resolver-Fehler in zwei Klassen modellieren:
   - Config-/Parse-/Template-Fehler -> Exit 7
   - CLI-missverständlich / Pflichtbruch -> Exit 2
5. Bestehende Wrapper-Signaturen beibehalten (`resolve(String)` etc.), intern delegieren auf neue nullable Varianten.
6. `NamedConnectionResolver`-KDoc aktualisieren (Default-Quelle, Default-Ziel, Resolve-Tabelle).

## 6.3 Schritt 27 – Exit-Code-Mapping für `data import` finalisieren

1. Fehlerpfad im `DataImportRunner` an Exit-Matrix §6.11 binden.
2. Wichtigste Abbildungen:
   - CLI-Fehler und ungültige Optionenkombinationen -> Exit 2
   - `UnsupportedTriggerModeException` bei MySQL/SQLite im `--trigger-mode disable` -> Exit 2
   - `--disable-fk-checks` auf PG -> Exit 2
3. Pre-Flight-Fehler (Header/Schema/Schema-Validierung, strict-trigger pre-flight, etc.) -> Exit 3.
4. Streaming-Fehler-Matrix:
   - `--on-error abort` -> Exit 5
   - `--on-error skip|log` -> Chunk-Write-/Read-Fehler verlieren nur Zeilenstatistik; Lauf endet Exit 0 mit `rowsFailed`/`chunkFailures`.
   - `--truncate` + explizit gesetztes `--on-conflict abort` -> Exit 2
5. `failedFinish`-Fehler (z. B. reseed/trigger-reenable nach erfolgreichem Schreiben) -> Exit 5.
6. Verbindungsfehler -> Exit 4.
7. Unerwartete interne Ausnahmen -> Exit 1.

## 7. Akzeptanzkriterien

- [x] `data import` ist als CLI-Entry mit allen Flags aus §3.7.1 nutzbar.
- [x] `DataImportCommand` löst `ImportInput` für stdin/file/directory korrekt auf.
- [x] `DataImportRunner` existiert und kapselt die Exit-Entscheidung für `data import`.
- [x] `--target` ist in Clikt optional; Pflicht wird über Resolver-Logik entschieden.
- [x] `database.default_source` und `database.default_target` sind aktiv und konsistent auflösbar.
- [x] `NamedConnectionResolver`-Wrapper bleiben kompatibel.
- [x] `UnsupportedTriggerModeException` bei unsupported `disable` führt deterministisch zu Exit 2.
- [x] `--disable-fk-checks` auf PG liefert Exit 2.
- [x] `--on-error skip|log` mit Chunk-Write/Read-Fehlern bleibt Exit 0.
- [x] `--source -` erfordert zwingend `--format` (sonst Exit 2).
- [x] Dateiquelle ohne erkennbare Endung ohne `--format` liefert Exit 2.
- [x] `--truncate --on-conflict abort` liefert Exit 2.
- [x] `failedFinish`-Pfad liefert Exit 5.
- [x] Exit-Mapping entspricht im Scope Tabelle §6.11.
- [x] KDoc von `NamedConnectionResolver` beschreibt die reale Resolve-Logik.

## 8. Verifikation

- [x] `NamedConnectionResolverTest`
- [x] `DataImportRunnerTest` (Parsing-Fälle, Resolver-Defaultpfade, Pre-Flight, Exit-Matrix)
- [x] `DataImportCommandTest` / `DataImportCommandParserTest`-Scope über `CliDataImportSmokeTest`, `DataImportRunnerTest` und `docker run ... data import --help` verifiziert
- [x] Optionaler CLI-Smoke per Docker-Image `d-migrate:dev`:
- [x] `data import` mit Directory + `--table[s]`
- [x] `data import` mit `--trigger-mode disable` auf SQLite/MySQL -> Exit 2
- [x] `data import` mit `--disable-fk-checks` auf PG -> Exit 2
- [x] `data import` mit `--source -` ohne `--format` -> Exit 2
- [x] `data import` mit `--source`-Datei ohne Endung und ohne `--format` -> Exit 2
- [x] `data import` mit `--truncate --on-conflict abort` -> Exit 2
- [x] Keine Standard-CI-Umgebungsänderung für die neuen Tests nötig; keine neuen externen Dependencies.
- [x] Lokal verifiziert via `docker build -t d-migrate:dev .` als Build-Smoke gemäß README.
