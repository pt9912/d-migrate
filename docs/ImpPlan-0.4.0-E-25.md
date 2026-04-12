# Implementierungsplan: Phase E – Schritt 25

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade  
> **Phase**: E (CLI-Integration)  
> **Schritt**: 25  
> **Status**: Geplant  
> **Referenz**: `implementation-plan-0.4.0.md` §3.7.2, §6.7, §6.12.1

---

## 1. Ziel

- `DataExportCommand` vollständig für LF-013 (`--since-column` + `--since`) absichern.
- Die Inkrement-Logik in CLI + Runner auf `ParameterizedClause` und parametrisierter Typkonvertierung vereinheitlichen.
- Exit-, Validierungs- und Sicherheitsverhalten deterministisch für Export + Test-Layer bereitstellen.

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `DataExportRunner` im CLI-Hexagon | 24 | ✅ Vorhanden |
| `DataFilter.ParameterizedClause` inkl. Compound-Support | 0.4.0 Kern | ✅ Vorhanden |
| `AbstractJdbcDataReader` unterstützt positionales Parameter-Binding | 0.4.0 Kern | ✅ Vorhanden |
| `DataExportCommand` + Runner-Test-Skelett | 24 | ✅ Vorhanden |

## 3. Architekturentscheidung

- Die Validierung und Filter-Synthese bleibt in `DataExportRunner`, `DataExportCommand` bleibt ein dünnes CLI-Mapping.
- `--since` wird ausschließlich über `DataExportHelpers.resolveFilter(...)` in typisierte Parameter umgewandelt.
- `sinceColumn`/`since` werden nur gemeinsam akzeptiert; bei Missbrauch sofort CLI/Runner-Exit mit `2`.
- `--filter` + `--since` wird nur erlaubt, wenn der rohe Filter kein unverarbeitetes Literal `?` enthält.

## 4. Abgrenzung

- Nicht Teil von Schritt 25: `DataExportCommand`-Datei-Output-Flow, `--tables`-Parsing, `--split-files`, `--format`-Registry.
- Keine neuen Streaming- oder Treiber-Features, nur CLI/Runnable-Kontrakt und Tests.

## 5. Betroffene Dateien

### 5.1 Primär

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/AbstractJdbcDataReader.kt`

### 5.2 Tests

- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataExportHelpersTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/AbstractJdbcDataReaderTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`

## 6. Umsetzungsschritte

1. `DataExportCommand` zeigt bereits `--since-column`/`--since` explizit in der CLI-Optionenliste; für Schritt 25 gilt, dass beide Felder in den Request durchgereicht werden.
2. In `DataExportRunner` `validate` erweitert auf exakt zwei Symmetrie-Fälle: beide gesetzt = gültig, eins fehlt = Exit-2 + Präzisionsmeldung.
3. `DataExportHelpers` bleibt die zentrale Stelle für:
   - Identifier-Validierung von `--since-column` gegen `TABLE_IDENTIFIER_PATTERN`
   - `parseSinceLiteral(...)` für Datums-/Zeit-/Zahlen-Typen
   - `resolveFilter(... sinceColumn, since, dialect )` mit `DataFilter.ParameterizedClause("<quoted col> >= ?", [typedSince])` inkl. dialekt-spezifischer Quotierung (`quoteQualifiedIdentifier`)
4. `containsLiteralQuestionMark` bleibt aktiv für den Kombipfad `--filter` + `ParameterizedClause` (M-R5). In diesem Fall Exit 2 vor Connection-Aufbau.
5. `AbstractJdbcDataReader` bleibt als einzige Schicht, die `DataFilter.Compound`-Baum in parametrisierten Statement-Parametern flacht; neue oder geänderte Fälle werden als Regressionstest ergänzt.
6. In CLI-Integration und Runner-Tests Coverage aufbauen/halten für:
   - `since`-Pair erfolgreich gefiltert
   - falsche Paare (`only since` / `only since-column`) -> Exit 2
   - ungültiger Identifier in `--since-column`
   - kombinierter `--filter` mit `?`

## 7. Akzeptanzkriterien

- [ ] `docker build -t d-migrate:dev .` baut erfolgreich.
- [ ] `--since-column` und `--since` sind nur gemeinsam zulässig und liefern bei fehlender Kombination Exit `2`.
- [ ] `--since` wird typisiert gebunden, nicht string-concateniert (kein SQL-Injection-Vektor).
- [ ] `--filter + --since` mit Literal `?` wird vor Datenbankzugriff mit Exit `2` hart abgelehnt.
- [ ] Runner liefert bei gültigem Filter ein `DataFilter.ParameterizedClause` oder `DataFilter.Compound(..., ParameterizedClause)`.
- [ ] `CliDataExportTest` enthält mindestens einen End-to-End-Fall, der `--since-column`/`--since` auf SQLite verifiziert.

## 8. Verifikation

1. `DataExportHelpersTest`: Typkonvertierung (`LocalDate`, `LocalDateTime`, `OffsetDateTime`, `Long`, `BigDecimal`) + invalides Pattern.
2. `DataExportRunnerTest`: Exit-Pfade 0/2 für LF-013-Preflight plus M-R5.
3. `AbstractJdbcDataReaderTest`: `Compound`-Param-Bindings inklusive `null`/mehrere Parameter.
4. `CliDataExportTest`: funktionaler Smoke mit SQLite: `--since` filtert Rows und `--filter`-/`?`-Abbruch.
