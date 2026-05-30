# Implementierungsplan: Factory-Port-Schnitt für die sechs eager-konstruierten CLI-Wirings

> Status: In Progress (2026-05-30)
> Workstream: Folge-Tranche zu
> `docs/planning/done/refactoring-cli-testability.md` Closure-Sektion
> „Folge-Tranche: §11-style Coverage"
> Vorbedingungen:
> - Strukturelles CLI-Wiring-Refactoring abgeschlossen (Commits
>   `4476acca`, `bc092096`, `2541b48a`, `acae00f0`, `741f6ad7`).
> - `DataImportSchemaPreflight`-Hex-Boundary geschlossen
>   (`88c813f6`).
> - Kover-Snapshot des CLI-Moduls vor Start der ersten Phase
>   dokumentieren, damit „Vor Refactor"-Werte in §1 nicht geraten
>   sind.
> Referenzen:
> - `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeWiring.kt`
>   (Vorbild: `ServerStateFactory`/`ServerStateBundle`-Pattern)
> - `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/{DataImport,DataProfile,ToolExport,SchemaCompare,SchemaGenerate,SchemaReverse}Wiring.kt`
> - `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/{DataExport,DataTransfer}WiringTest.kt`
>   (Pre-Runner-Pfad-Pin als Coverage-Referenz für die zwei nicht
>   adressierten Wirings)
> - `spec/lastenheft-d-migrate.md` LN-045 (Coverage messbar/überprüfbar)

---

## 1. Ziel

Die sechs eager-konstruierten CLI-Wirings (`DataImport`, `DataProfile`,
`ToolExport`, `SchemaCompare`, `SchemaGenerate`, `SchemaReverse`) auf
75–88% Modul-isolierte Coverage heben, analog zur Plan-Lessons-§11-
Tabelle aus `refactoring-cli-testability.md`:

| Klasse | Vor Refactor (zu messen) | Ziel nach Folge-Tranche |
| --- | --- | --- |
| `McpServeWiring` | 88% | — (bereits abgehakt) |
| `DataImportWiring` | unerreichbar (eager Hikari) | 75–80% |
| `DataProfileWiring` | unerreichbar | 75–80% |
| `ToolExportWiring` | unerreichbar | 75–80% |
| `SchemaCompareWiring` | unerreichbar | 80–88% |
| `SchemaGenerateWiring` | unerreichbar | 75–80% |
| `SchemaReverseWiring` | unerreichbar | 75–80% |

Die „Vor Refactor"-Spalte ist vor Phase A aus dem aktuellen Kover-HTML
zu befüllen — heute liegt sie nicht vor. Klassifizierung „unerreichbar"
bedeutet: jede `execute()`-Probe ohne Live-DB schlägt am ersten
`HikariConnectionPoolFactory.create(...)` oder
`DatabaseDriverRegistry.get(...).<dataReader/dataWriter/…>()` fehl
(Hikari eager-connect, siehe Lessons §9).

`DataExportWiring` und `DataTransferWiring` sind nicht Teil dieser
Tranche — ihre Filter-Pfade sind in `741f6ad7` gepinnt, die
Hikari-Konstruktion ist eigene Folge-Tranche (siehe §7).

## 2. In-/Out-of-Scope

### 2.1 In Scope

- Factory-Interface pro Wiring, das die Pool-/Adapter-/Resolver-
  Konstruktion bündelt. Default-Impl bleibt im Wiring-File
  (`internal class Default<Name>Factory`) und reproduziert das
  heutige Verhalten 1:1.
- Konstruktor-Injection der Factory in das Wiring; Default-
  Konstruktor ruft `Default<Name>Factory()` auf, sodass die
  Production-Aufrufer (`<Name>Command`) unverändert bleiben.
- Tests substituieren die Factory mit einem Fake-Bundle (analog
  `ServerStateBundle`), das in-memory-Adapter und einen No-Op-Pool
  liefert.
- Per-Wiring-Coverage über `koverVerify` pro Modul geprüft; Wert
  wird in eine Closure-Update-Tabelle im erledigten Plan-Doc
  gespiegelt.

### 2.2 Out of Scope

- Integration-Tests gegen Live-DB — bleiben bei `make integration`
  und sind nicht Teil der pro-Wiring-Coverage-Ziele.
- `McpServeCommand` / `McpServeWiring` — bereits 88% per
  `ServerStateFactory`-Pattern, kein Re-Refactor.
- `DataExportWiring` / `DataTransferWiring` Hikari-Anteil — Filter-
  Pfade existieren, Pool-Construction ist eigene Folge-Tranche
  (siehe §7).
- `SchemaMigrateCommand`, `SchemaRollbackCommand`,
  `SchemaValidateCommand` — in der CLI-Testability-Nacharbeit bereits
  als `Schema*Migrate/Rollback/ValidateWiring`-Schnitt entkoppelt;
  keine Factory-Port-Phase in diesem Plan.

## 3. Vorbild: `ServerStateFactory` in `McpServeWiring`

```kotlin
internal data class ServerStateBundle(
    val phaseCWithPersistence: McpRuntimeWiring,
    val idempotencyStore: IdempotencyStore,
    val jobStartTransaction: JobStartTransaction,
    val quotaReservationOwnerStore: QuotaReservationOwnerStore,
    val ownerAwareQuotaService: OwnerAwareQuotaService,
    val cleanup: AutoCloseable,
)

internal fun interface ServerStateFactory {
    fun build(state: McpServerStateConfig, phaseC: McpRuntimeWiring): ServerStateBundle
}
```

Das Pattern jedes Wirings adoptiert — Namensvorbild bleibt McpServe
(kein „Infrastructure"-Suffix):

- `<Name>Bundle` — die Aggregat-Klasse mit den konstruierten
  Adaptern/Pools (Beispiel: `DataProfileBundle`).
- `<Name>Factory` (`fun interface`) — die Konstruktor-Injection-Surface.
- `Default<Name>Factory` — Production-Default, konstruiert Hikari +
  Registry-Lookups identisch zur heutigen Inline-Logik.

## 4. Phasen (1 Slice pro Wiring)

Reihenfolge nach steigender Komplexität: Phase A setzt das Pattern,
B–F kopieren es; D zieht die höhere Coverage-Latte wegen
Exit-Code-Verzweigung.

### Phase A — `DataProfileWiring`

> Status: umgesetzt (2026-05-30). Commit-Hinweis:
> `wiring: add data profile factory port` (Phase-A-Commit).
> Nachweis: `make docker-check MODULES=":adapters:driving:cli"`.

- Kleinster Wiring-Block; gute Vorlage für das Pattern.
- Bundle trägt: `connectionResolver`, `dialectResolver`,
  `poolFactory`, `adapterLookup` (3 Profiling-Adapter-Tripel),
  `reportWriter`.
- DoD:
  - [x] ≥ 75% `koverVerify` auf `DataProfileWiring`
  - [x] Fakes für Profiling-Adapter + In-Memory-Pool im Test-Bundle
  - [x] 5–8 Test-Fälle: Happy-Path pro Dialekt, Pool-Construction-
        Fehler, leere Tabellen-Liste

### Phase B — `ToolExportWiring`

> Status: umgesetzt (2026-05-30). Commit-Hinweis:
> `wiring: add tool export factory port` (Phase-B-Commit).
> Nachweis: `make docker-check MODULES=":adapters:driving:cli"`.

- Vier Subcommands teilen sich das Wiring; Tests durchlaufen jeden
  `MigrationTool`-Wert.
- Bundle: `schemaReader`, `generatorLookup`,
  `preGenerationValidatorLookup`, `exporterLookup`,
  `existingPathsScanner`.
- DoD:
  - [x] ≥ 75% `koverVerify` auf `ToolExportWiring`
  - [x] Ein Fake-Exporter pro `MigrationTool`-Wert
  - [x] In-Memory-Dateisystem fuer `existingPaths`-Scanner

### Phase C — `SchemaReverseWiring`

> Status: umgesetzt (2026-05-30). Commit-Hinweis:
> `wiring: add schema reverse factory port` (Phase-C-Commit).
> Nachweis: `make docker-check MODULES=":adapters:driving:cli"`.

- Bundle: `sourceResolver`, `urlParser`, `poolFactory`,
  `driverLookup`, `schemaWriter`, `reportWriter`, `sidecarPath`,
  `formatValidator`, `urlScrubber`, `printError`.
- DoD:
  - [x] ≥ 75% `koverVerify` auf `SchemaReverseWiring`
  - [x] 6–8 Test-Faelle: Format-Validation, Sidecar-Default,
        `--include-all`, Driver-Lookup-Fehler

### Phase D — `SchemaCompareWiring`

> Status: umgesetzt (2026-05-30). Commit-Hinweis:
> `wiring: add schema compare factory port` (Phase-D-Commit).
> Nachweis: `make docker-check MODULES=":adapters:driving:cli"`.

- Wegen `dbLoader`-Inline-Lambda mit Phase-1/2-Exception-Routing
  einer der wertvollsten Pfade.
- Bundle muss `fileLoader` + `dbLoader` separat führen, damit Tests
  beide Pfade ohne Live-DB pinnen können.
- DoD:
  - [x] ≥ 80% `koverVerify` auf `SchemaCompareWiring`
        (hoehere Latte wegen Exit-Code-Verzweigung)
  - [x] Phase-1-Block: Config-Resolve-Fehler → Exit 7
  - [x] Phase-2-Block: Connection-Fehler → Exit 4
  - [x] File-/File-, File-/DB- und DB-/DB-Permutationen gepinnt

### Phase E — `SchemaGenerateWiring`

> Status: umgesetzt (2026-05-30). Commit-Hinweis:
> `wiring: add schema generate factory port` (Phase-E-Commit).
> Nachweis: `make docker-check MODULES=":adapters:driving:cli"`.

- Bundle: `schemaReader`, `generatorLookup`,
  `preGenerationValidatorLookup`, `reportWriter`, plus
  `formatJsonOutput`/`sidecarPath`/`rollbackPath`/`splitPath`
  Method-Refs (bleiben statisch, weil reine Pfad-Berechnungen).
- DoD:
  - [x] ≥ 75% `koverVerify` auf `SchemaGenerateWiring`
  - [x] Coverage-Branchen gepinnt: `--split pre-post` vs `single`,
        `--generate-rollback`, `--deterministic`,
        `--mysql-named-sequences`, `--sqlite-named-sequences`

### Phase F — `DataImportWiring`

- Größtes Wiring (Hikari + Preflight + Streaming-Importer).
- Bundle: `targetResolver`, `urlParser`, `poolFactory`,
  `writerLookup`, `schemaCodec` (heute inline
  `YamlSchemaCodec()` an `DataImportSchemaPreflight` übergeben —
  ins Bundle anheben), `preflightFactory` als Funktions-Hülle für
  `(SchemaCodec) -> DataImportSchemaPreflight`, `importExecutor`,
  `progressReporter`, `checkpointStoreFactory`,
  `checkpointConfigResolver`.
- DoD:
  - [ ] ≥ 75% `koverVerify` auf `DataImportWiring`
  - [ ] Factory-Injection-Pfade gepinnt (Bundle-Felder werden vom
        Wiring tatsaechlich konsumiert)
  - [ ] Default-Konstruktor-Pin: `DataImportWiring()` ohne Argumente
        ruft `DefaultDataImportFactory()` auf
  - [ ] Preflight-Construction mit Fake-Codec ohne Live-DB lauffaehig

  Runner-Verhalten (`--target` ohne Default, Preflight-Fehler-Exit-Codes,
  Stdin + `--resume`-Block) ist bereits durch `DataImportRunnerTest` in
  `application`/`cli` abgedeckt — wird hier nicht dupliziert.

## 5. DoD pro Phase (phasenuebergreifender Closing-Vertrag)

Jede gelandete Phase muss die folgenden Kriterien erfuellen; der
Closing-Haken sitzt auf der jeweiligen Phase und wird beim Phase-
Commit gesetzt. Die Plan-Ebene schliesst, wenn alle Phasen abgehakt
sind und die drei Querschnitts-Kriterien fuer den Gesamtstand gelten:

- [ ] `make docker-coverage-gate` (root `koverVerify` ≥ 90%) bleibt
      nach allen Phasen grün — keine Regression in fremden Modulen.
- [ ] Per-Modul-Coverage des CLI-Moduls ist nach Phase F um den
      Summen-Beitrag aller sechs Wirings gestiegen (Ausgangslage
      laut Kover-HTML 0% pro Wiring ausser Filter-Pfade in
      DataExport/DataTransfer).
- [ ] Coverage-Tabelle in
      `docs/planning/done/refactoring-cli-testability.md` Closure-
      Sektion wurde pro Phase nachgezogen und spiegelt zum
      Plan-Close den finalen Stand.

## 6. Risiken

1. **Test-Boilerplate-Wachstum**: jede Factory bringt 5–10
   Lambdas, die Fakes brauchen. Mitigation: gemeinsame
   `FakePool`/`FakeConnectionPool`-Test-Fixtures in
   `adapters/driving/cli/src/test/kotlin/.../fixtures/` ablegen,
   damit ToolExport/SchemaCompare/etc. sie teilen.
2. **Adapter-Klassen-Explosion**: pro Wiring entsteht ein
   `Default…Factory` + Bundle; die CLI-Modul-Grösse steigt um
   ~60 Zeilen pro Wiring (geschätzt). Mitigation: Bundles als
   `data class` ohne Methoden halten; Factories sind `fun interface`.
3. **Pattern-Drift gegen `McpServeWiring`**: wenn die sechs
   Factories untereinander leicht abweichen (z.B. ob `cleanup`
   im Bundle steckt oder nicht), wird das Pattern undurchsichtig.
   Mitigation: Phase A (DataProfile) setzt das Pattern; nachfolgende
   Phasen kopieren wörtlich.
4. **Coverage-Gate-Flake an der 90%-Grenze**:
   `:adapters:driving:cli:koverVerify` kann knapp unter 90% flaken,
   wenn neue Wiring-Klassen knapp unterhalb der Schwelle liegen.
   Mitigation: lokale Verifikation vor Push, dann CI rerun.

## 7. Out-of-Scope / Folge-Themen

- **`DataExportWiring` / `DataTransferWiring` Hikari-Coverage**:
  Filter-Pfade sind in `741f6ad7` gepinnt; die Pool-/Adapter-/
  Resolver-Konstruktion folgt mit demselben Factory-Port-Pattern,
  ist aber eine eigene Tranche und nicht in den sechs Phasen oben.
- **Integration-Test-Verschiebung**: die heutigen `CliData*Test`-
  Integrations-Pfade gegen SQLite bleiben, sie ergänzen die
  Wiring-Unit-Tests und ersetzen sie nicht.
- **Factory-Port als public API**: die Bundles und Factories
  bleiben `internal`; eine Embedder-API für externe
  Wiring-Substitution ist eigener Scope.

## 8. Reihenfolge-Empfehlung

Phase A (DataProfile) zuerst, weil sie das Pattern etabliert und
am kleinsten ist. Danach Phase B (ToolExport) für die geteilte
Wiring-Erfahrung, dann nach steigender Komplexität C → D → E → F.
Jede Phase ist ein eigener Commit mit eigenem `make docker-check`-
Gate; bei Pattern-Drift wird die jeweilige Phase rückgängig gemacht
und Phase A als Template neu konsultiert.
