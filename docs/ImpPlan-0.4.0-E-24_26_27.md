# Implementierungsplan: Phase E – Schritte 24, 26, 27 (gebündelt)

## 1. Referenz

- `docs/implementation-plan-0.4.0.md` §3.7 und §6.11
- Fokus: `DataImportCommand`, `NamedConnectionResolver` und Exit-Code-Mapping für `data import`
- Ziel: Schritte 24, 26, 27 als konsistenter Paketplan in `adapters:driving:cli`

## 2. Zielbild

- `data import` vollständig in den CLI-Layer heben, inklusive aller Optionen aus §3.7.1
- `NamedConnectionResolver` für Import/Export um `database.default_source` und `database.default_target` ergänzen
- CLI-Fehlermodi für `data import` auf die Exit-Codes in §6.11 mappen
- Die drei Punkte als ein zusammenhängender Implementierungsblock liefern, damit CLI-Parsing, Verbindungsauflösung und Exit-Verhalten in einem Sprint konsistent sind

## 3. Abgrenzung

- Nicht Teil von Schritt 24/26/27
- `--incremental`/`--since-column` auf der Import-Seite (kein Scope von `data import`)
- Phase-F-E2E-Tests gegen Container
- Vollständige Docs-Überarbeitung in `docs/cli-spec.md` (kommt mit späteren Doku-Schritten)
- Reader/Writer-Implementierungen außerhalb des CLI-Wrappers

## 4. Betroffene Dateien

### 4.1 Primär

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt` (neu/aktualisieren)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommand.kt` (Subcommand-Registrierung falls nötig)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt` (nur falls zur Kompatibilität von Resolver-Signatur/Overload nötig)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/ErrorMapper.kt` oder bestehender CLI-Exit-Dispatcher
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Runner.kt`/Command-Dispatcher (Ort der Exit-Dispatch-Logik je bestehender Struktur)

### 4.2 Tests

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/config/NamedConnectionResolverTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportCommandTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportCommandParserTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/ErrorMapperTest.kt` oder entsprechender Exit-Mapping-Test

## 5. Umsetzungsschritte

## 5.1 Schritt 24 – `DataImportCommand` mit allen Flags aus §3.7.1

1. Import-Command in einen klaren Helper/Runner-Fluss aufteilen (CLI-Parsing + Laufvorbereitung + Runner-Aufruf).
2. Optionen vollständig nach §3.7.1 implementieren: `--target`, `--source` (required), `--format`, `--table`, `--tables`, `--schema`, `--on-error`, `--on-conflict`, `--trigger-mode`, `--truncate`, `--disable-fk-checks`, `--reseed-sequences` (inkl. `--no-reseed-sequences`), `--encoding`, `--csv-no-header`, `--csv-null-string`, `--chunk-size`.
3. `--format`:
   - nur `json|yaml|csv` akzeptieren; kein Content-Sniffing
   - bei Datei-Quelle: bei unbekannter/nicht erkennbarer Endung ist `--format` verpflichtend (sonst Exit 2)
   - bei `--source -` (stdin): keine Endung verfügbar, daher ebenfalls `--format` verpflichtend (sonst Exit 2)
4. `--table`/`--tables`-Matrix implementieren:
   - `-` braucht `--table`
   - Datei (`--source`) braucht `--table`
   - Verzeichnis verwendet `--tables`
   - Mischformen führen mit klaren Fehlern zu Exit 2
5. `ImportInput`-Objekt bauen:
   - `ImportInput.Stdin(table, System.in)` für `-`
   - `ImportInput.SingleFile(table, path)` für Datei
   - `ImportInput.Directory(path, tables, tableOrder?)` für Verzeichnis
6. `DataCommand` um `DataImportCommand` ergänzen, sofern noch nicht geschehen.
7. `Source`-Pfadprüfung:
   - nicht existierende Datei -> Exit 2
   - nicht direkt lesbare Quelle (Datei, Verzeichnis, Nicht-Datei) -> deterministischer Fehler mit präziser Meldung

## 5.2 Schritt 26 – `NamedConnectionResolver` aktiviert Defaults

1. `resolveSource(source: String?)` um `database.default_source` erweitern.
2. `resolveTarget(target: String?)` implementieren/ergänzen für `database.default_target`.
3. Auflösungstabelle aus §3.7.3 im Code strikt abbilden:
   - `resolveTarget` bei `null` und gesetztem Default: als URL direkt nutzen oder über `database.connections` auflösen
   - bei Default als Connection-Name: Auflösung über `connections`-Map
   - fehlende Default-Zuweisung -> Exit 2 für `--target`-Pflichtbruch
4. Resolver-Fehler sauber als Config-Fehler (Exit 7) oder CLI-Miss-Konfiguration (Exit 2) aufbereiten.
5. Rückwärtskompatibilität erhalten: bestehende Wrapper-Signaturen (z. B. `resolve(source: String)` oder bestehende `resolveTarget(source: String)`-Varianten) unverändert lassen und intern auf die neuen `resolveSource(null)`/`resolveTarget(null)`-Pfad delegieren.
6. `NamedConnectionResolver.kt` KDoc an neue Rollen von `default_source`/`default_target` und Auflösungstabelle binden (drift-frei, siehe L10).

## 5.3 Schritt 27 – Exit-Code-Mapping für `data import`

1. CLI/Runner-Fehlerpfad an Exit-Matrix §6.11 anbinden.
2. Wichtige Abbildungen:
   - CLI-Fehler und ungültige Optionenkombinationen -> Exit 2
   - `UnsupportedTriggerModeException` bei MySQL/SQLite im `--trigger-mode disable` -> Exit 2
   - `--disable-fk-checks` auf PG -> Exit 2
3. Pre-Flight-Fehler (Header/Schema/Schema-Validierung, strict-trigger pre-flight, etc.) -> Exit 3.
4. Streaming-Fehler-Matrix:
   - `--on-error abort` -> Exit 5
   - `--on-error skip|log` -> keine Exit-5-Regression durch reine Chunk-Write-Fehler; Lauf endet regulär (Exit 0) und trägt `rowsFailed`/`chunkFailures` im Report.
   - `--truncate` + explizit gesetztes `--on-conflict abort` -> Exit 2 (bewusstes Fehlkonzept)
5. `failedFinish`-Fehler (z. B. reseed/trigger-reenable nach erfolgreichem Schreiben) -> Exit 5.
6. Verbindungsfehler -> Exit 4, unerwartete interne Ausnahmen -> Exit 1.
7. Exit-Tests je Klasse:
   - Fehlende Pflichtoptionen
   - `--table`/`--tables`-Regeln
   - Resolver-Defaultpfade
   - Trigger-Mode-Unsupported-Pfade
   - `--truncate --on-conflict abort` -> Exit 2
   - `--source -` ohne `--format` -> Exit 2
   - Datei ohne Endung ohne `--format` -> Exit 2
   - `--on-error skip|log` + Chunk-Write-Fehler bleibt Exit 0

## 6. Akzeptanzkriterien

- `data import` liefert einen funktionalen CLI-Entry mit allen Flags aus §3.7.1.
- `DataImportCommand` löst `ImportInput` für stdin/file/directory korrekt auf.
- `--target` ist optional in Clikt, Pflicht wird nur über Resolver-Logik entschieden.
- `database.default_source` und `database.default_target` sind aktiv und konsistent auflösbar.
- Vorhandene Resolver-Wrapper bleiben kompatibel und delegieren auf die neuen Null-basierten Pfade.
- `UnsupportedTriggerModeException` wird für nicht unterstützte Trigger-Disable-Kombinationen deterministisch auf Exit 2 gemappt.
- `--disable-fk-checks` auf PG liefert Exit 2.
- `--on-error skip|log` mit Chunk-Write-Fehlern bleibt Exit 0.
- `--source -` erfordert zwingend `--format` (sonst Exit 2).
- `--truncate --on-conflict abort` führt zu Exit 2.
- `Unknown extension`/kein `--format` bei Dateiquelle führt zu Exit 2.
- Exit-Mapping entspricht Tabelle §6.11 für die im Scope liegenden Pfade.
- `NamedConnectionResolver`-KDoc beschreibt die tatsächliche Resolve-Logik.
- [ ] `docker build -t d-migrate:dev .` baut erfolgreich.

## 7. Verifikation

1. `NamedConnectionResolverTest`
2. `DataImportCommandTest` (Parsing, Source/Table-Typen, Flags, `ImportInput`-Ableitung)
3. Exit-Mapping-Tests für die in §6.11 genannten `data import`-Triggerfälle
4. Optionaler CLI-Integrationstest-Smoke:
   - `data import` mit Directory + `--table[s]`-Auflösung
   - `data import` mit `--trigger-mode disable` auf SQLite/MySQL
   - `data import` mit `--disable-fk-checks` auf PG
   - `data import` mit `--source -` und ohne `--format` (erwartet Exit 2)
   - `data import` mit `--source`-Datei ohne Endung und ohne `--format` (erwartet Exit 2)
   - `data import` mit `--truncate --on-conflict abort` (erwartet Exit 2)
5. Keine Standard-CI-Umgebungsänderung für die neuen Tests nötig (keine neuen externen Dependencies).
