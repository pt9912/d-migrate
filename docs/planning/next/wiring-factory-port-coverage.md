# Implementierungsplan: Factory-Port-Schnitt für die sechs eager-konstruierten CLI-Wirings

> Status: Entwurf (2026-05-30)
> Workstream: Folge-Tranche zu
> `docs/planning/done/refactoring-cli-testability.md` Closure-Sektion
> „Folge-Tranche: §11-style Coverage"
> Vorbedingungen:
> - Strukturelles CLI-Wiring-Refactoring abgeschlossen (Commits
>   `4476acca`, `bc092096`, `2541b48a`, `acae00f0`, `741f6ad7`).
> - `DataImportSchemaPreflight`-Hex-Boundary geschlossen
>   (`88c813f6`).
> Referenzen:
> - `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeWiring.kt`
>   (Vorbild: `ServerStateFactory`/`ServerStateBundle`-Pattern)
> - `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/{DataImport,DataProfile,ToolExport,SchemaCompare,SchemaGenerate,SchemaReverse}Wiring.kt`
> - `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/{DataExport,DataTransfer}WiringTest.kt`
>   (Pre-Runner-Pfad-Pin als Coverage-Untergrund)
> - `spec/lastenheft-d-migrate.md` LN-045 (Coverage ≥ 80%)

---

## 1. Ziel

Die sechs eager-konstruierten CLI-Wirings (`DataImport`, `DataProfile`,
`ToolExport`, `SchemaCompare`, `SchemaGenerate`, `SchemaReverse`) auf
75–88% Modul-isolierte Coverage heben, analog zur Plan-Lessons-§11-
Tabelle aus `refactoring-cli-testability.md`:

| Klasse | Vor Refactor | Ziel nach Folge-Tranche |
| --- | --- | --- |
| `McpServeWiring` | 88% | — (bereits abgehakt) |
| `DataExportWiring` | nur Filter-Pfade ~30% | 75–80% |
| `DataTransferWiring` | nur Filter-Pfade ~30% | 75–80% |
| `DataImportWiring` | unerreichbar (eager Hikari) | 75–80% |
| `DataProfileWiring` | unerreichbar | 75–80% |
| `ToolExportWiring` | unerreichbar | 75–80% |
| `SchemaCompareWiring` | unerreichbar | 80–88% |
| `SchemaGenerateWiring` | unerreichbar | 75–80% |
| `SchemaReverseWiring` | unerreichbar | 75–80% |

Heute scheitert jede Wiring-Unit-Coverage über die Filter-Validation
hinaus, weil `execute()` direkt `HikariConnectionPoolFactory.create(...)`
+ `DatabaseDriverRegistry.get(...).<dataReader/dataWriter/…>()`
aufruft — ohne lebenden JDBC-Endpunkt schlägt der Konstruktor (Hikari
eager-connect, siehe Lessons §9) sofort fehl.

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

- `DataExportWiring` + `DataTransferWiring` als zweistufige Phase: die
  Filter-Pfade liegen schon, der Hikari-Teil folgt mit demselben
  Pattern. Keine Sonderrolle.
- Integration-Tests gegen Live-DB — bleiben bei `make integration`
  und sind nicht Teil der pro-Wiring-Coverage-Ziele.
- `McpServeCommand` / `McpServeWiring` — bereits 88% per
  `ServerStateFactory`-Pattern, kein Re-Refactor.
- `SchemaMigrateCommand`, `SchemaRollbackCommand`,
  `SchemaValidateCommand` — nicht Teil der ursprünglichen
  „Betroffene Commands"-Liste; eigene Bewertung in einer späteren
  Tranche.

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

Das Pattern jedes Wirings adoptiert:

- `<Name>InfrastructureBundle` — die Aggregat-Klasse mit den
  konstruierten Adaptern/Pools.
- `<Name>InfrastructureFactory` (`fun interface`) — die
  Konstruktor-Injection-Surface.
- `Default<Name>InfrastructureFactory` — Production-Default,
  konstruiert Hikari + Registry-Lookups identisch zur heutigen
  Inline-Logik.

## 4. Phasen (1 Slice pro Wiring)

Reihenfolge nach steigender Komplexität:

### Phase A — `DataProfileWiring`

- Kleinster Wiring-Block; gute Vorlage für das Pattern.
- Bundle trägt: `connectionResolver`, `dialectResolver`,
  `poolFactory`, `adapterLookup` (3 Profiling-Adapter-Tripel),
  `reportWriter`.
- DoD: ≥ 75% `koverVerify`; Fakes für Profiling-Adapter
  + In-Memory-Pool; 5–8 Test-Fälle (Happy-Path pro Dialekt,
  Pool-Construction-Fehler, leere Tabellen-Liste).

### Phase B — `ToolExportWiring`

- Vier Subcommands teilen sich das Wiring; Tests durchlaufen jeden
  `MigrationTool`-Wert.
- Bundle: `schemaReader`, `generatorLookup`,
  `preGenerationValidatorLookup`, `exporterLookup`,
  `existingPathsScanner`.
- DoD: ≥ 75% `koverVerify`; ein Fake-Exporter pro Tool +
  In-Memory-Dateisystem für `existingPaths`.

### Phase C — `SchemaReverseWiring`

- Bundle: `sourceResolver`, `urlParser`, `poolFactory`,
  `driverLookup`, `schemaWriter`, `reportWriter`, `sidecarPath`,
  `formatValidator`, `urlScrubber`, `printError`.
- DoD: ≥ 75% `koverVerify`; 6–8 Fälle (Format-Validation, Sidecar-
  Default, `--include-all`, Driver-Lookup-Fehler).

### Phase D — `SchemaCompareWiring`

- Wegen `dbLoader`-Inline-Lambda mit Phase-1/2-Exception-Routing
  einer der wertvollsten Pfade.
- Bundle muss `fileLoader` + `dbLoader` separat führen, damit Tests
  beide Pfade ohne Live-DB pinnen können.
- DoD: ≥ 80% (höhere Latte wegen Exit-Code-Verzweigung);
  Phase-1-Block (Config-Resolve-Fehler → Exit 7), Phase-2-Block
  (Connection-Fehler → Exit 4), File-/File-, File-/DB-,
  DB-/DB-Permutationen.

### Phase E — `SchemaGenerateWiring`

- Bundle: `schemaReader`, `generatorLookup`,
  `preGenerationValidatorLookup`, `reportWriter`, plus
  `formatJsonOutput`/`sidecarPath`/`rollbackPath`/`splitPath`
  Method-Refs (bleiben statisch, weil reine Pfad-Berechnungen).
- DoD: ≥ 75%; Coverage-Branchen: `--split pre-post` vs `single`,
  `--generate-rollback`, `--deterministic`,
  `--mysql-named-sequences`, `--sqlite-named-sequences`.

### Phase F — `DataImportWiring`

- Größtes Wiring (Hikari + Preflight + Streaming-Importer).
- Bundle: `targetResolver`, `urlParser`, `poolFactory`,
  `writerLookup`, `schemaPreflight`-Funktionsreferenz,
  `schemaTargetValidator`, `importExecutor`, `progressReporter`,
  `checkpointStoreFactory`, `checkpointConfigResolver`.
- DoD: ≥ 75%; Pflicht-Pin: `--target` ohne Default → `CliUsageException`,
  Preflight-Fehler → Exit 3, Stdin-Source + `--resume` → Exit 2.

### Phase G — `DataExportWiring` + `DataTransferWiring` Hikari-Teil

- Filter-Pfade sind bereits gepinnt (`DataExportWiringTest`,
  `DataTransferWiringTest` aus Commit `741f6ad7`).
- Diese Tranche hebt nur den Runner-Block (Pool-Construction,
  Reader-/Lister-Lookup, ProgressRenderer-Integration) hinter die
  Factory.
- DoD: ≥ 75% für beide Wirings; existierende Filter-Tests
  durchläufig halten, neue Runner-Pfad-Tests ergänzen.

## 5. DoD pro Phase

- `make docker-coverage-gate` (root `koverVerify` ≥ 90%) bleibt
  grün — keine Regression in fremden Modulen.
- Per-Modul-Coverage des CLI-Moduls steigt um den jeweiligen
  Wiring-Beitrag (heute laut Kover-HTML 0% pro Wiring außer
  Filter-Pfade).
- Coverage-Tabelle in
  `docs/planning/done/refactoring-cli-testability.md` Closure-
  Sektion wird pro Phase nachgezogen.

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
4. **Coverage-Gate-Flake** (siehe Memory `feedback_kover_ci_flake`):
   `:adapters:driving:cli:koverVerify` kann knapp unter 90%
   flaken. Mitigation: lokale Verifikation vor Push, dann CI rerun.

## 7. Out-of-Scope / Folge-Themen

- **Coverage-Tabelle für `SchemaMigrate`/`SchemaRollback`/
  `SchemaValidate`**: diese drei Commands waren nicht in der
  ursprünglichen „Betroffene Commands"-Liste. Eigene Bewertung +
  ggf. Plan-Doc nach Abschluss dieser Tranche.
- **Integration-Test-Verschiebung**: die heutigen `CliData*Test`-
  Integrations-Pfade gegen SQLite bleiben, sie ergänzen die
  Wiring-Unit-Tests und ersetzen sie nicht.
- **Factory-Port als public API**: die Bundles und Factories
  bleiben `internal`; eine Embedder-API für externe
  Wiring-Substitution ist eigener Scope.

## 8. Reihenfolge-Empfehlung

Phase A (DataProfile) zuerst, weil sie das Pattern etabliert und
am kleinsten ist. Danach Phase B (ToolExport) für die geteilte
Wiring-Erfahrung, dann nach steigender Komplexität C → D → E → F → G.
Jede Phase ist ein eigener Commit mit eigenem `make docker-check`-
Gate; bei Pattern-Drift wird die jeweilige Phase rückgängig gemacht
und Phase A als Template neu konsultiert.
