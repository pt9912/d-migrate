# Implementierungsplan: Phase D - CLI- und Runner-Pfad fuer Tool-Export

> **Milestone**: 0.7.0 - Tool-Integrationen
> **Phase**: D (CLI- und Runner-Pfad fuer Tool-Export)
> **Status**: Implemented (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.2 bis 4.8, Abschnitt 5 Phase D, Abschnitt 6,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.7.0-A.md`; `docs/ImpPlan-0.7.0-B.md`;
> `docs/ImpPlan-0.7.0-C.md`; `docs/cli-spec.md`; `docs/design.md`;
> `docs/architecture.md`; `docs/lastenheft-d-migrate.md` LF-011 / LF-014

---

## 1. Ziel

Phase D verdrahtet die in Phase B und Phase C vorbereiteten Bausteine zu einem
ausfuehrbaren 0.7.0-Exportpfad: aus einer neutralen Schema-Datei, ueber
Validation und DDL-Generierung, bis hin zu geschriebenen Tool-Artefakten und
einem optionalen Report-Sidecar.

Der Teilplan liefert bewusst noch keine echte Tool-Runtime-Validierung gegen
Flyway, Liquibase, Django oder Knex. Er schafft den produktiven
Application-/CLI-Pfad, auf dem Phase E anschliessend die generierten Artefakte
gegen echte Tool-Runtimes prueft.

Nach Phase D soll klar und testbar gelten:

- es gibt einen dedizierten `ToolExportRequest`
- es gibt einen dedizierten `ToolExportRunner`
- `d-migrate export flyway|liquibase|django|knex` ist ueber die Root-CLI
  erreichbar
- der Runner liest und validiert ein neutrales Schema, loest Dialekt und
  `--spatial-profile` auf, erzeugt Up-/Down-DDL, baut das `MigrationBundle`
  und delegiert an den passenden Tool-Exporter
- die Export-Artefakte werden unterhalb eines expliziten Output-Verzeichnisses
  geschrieben, nicht nach stdout
- Dateikollisionen werden vor dem ersten Write hart erkannt
- Notes / `skippedObjects` und export-spezifische Hinweise bleiben ueber
  `stderr` und optionalen Report sichtbar
- Exit-Codes, Pflichtflags und Help-Texte folgen exakt dem Vertrag aus
  Phase A und `docs/cli-spec.md`

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und der Dokumentation:

- `hexagon:application` enthaelt mit `SchemaGenerateRunner` bereits ein
  belastbares Muster fuer einen dateibasierten Generate-Pfad:
  - Schema lesen
  - validieren
  - Dialekt und Spatial-Profil aufloesen
  - DDL erzeugen
  - Notes / `skippedObjects` ausgeben
  - Datei- und Report-Write koordinieren
- `adapters:driving:cli` enthaelt mit `SchemaGenerateCommand` und
  `DataExportCommand` bereits das gewollte Clikt-Muster fuer Phase D:
  - duenne CLI-Schale
  - Request-Mapping
  - Delegation an einen testbaren Runner
  - command-lokales Bootstrap der Runner-Kollaboratoren
  - Exit via `ProgramResult`
- `buildRootCommand()` haengt `schema`, `data` und `export` ein.
- `ExportCommand` ist als CLI-Gruppe mit vier Subcommands
  (`flyway`, `liquibase`, `django`, `knex`) verdrahtet.
- Phase B ist implementiert:
  - tool-neutraler Exportvertrag unter `hexagon/ports`
  - adapterfreie Helper in `hexagon/application/src/main/kotlin/dev/dmigrate/cli/migration/`
- Phase C ist implementiert:
  - Modul `adapters:driven:integrations`
  - `FlywayMigrationExporter`, `LiquibaseMigrationExporter`,
    `DjangoMigrationExporter`, `KnexMigrationExporter`
- `ToolExportRunner` und `ToolExportRequest` bilden den produktiven
  Ende-zu-Ende-Pfad:
  - Schema lesen und validieren
  - `MigrationBundle` bauen
  - passenden Exporter waehlen
  - Kollisionen pruefen (rekursiv unterhalb des Output-Verzeichnisses)
  - Artefakte schreiben
  - Report und CLI-Exit konsistent materialisieren
- `docs/cli-spec.md` fixiert fuer 0.7.0 bereits den finalen CLI-Vertrag:
  - `--source` ist eine Schema-Datei
  - `--output` ist Pflicht und bezeichnet ein Ausgabeverzeichnis
  - `--target` ist Pflicht
  - Flyway/Liquibase duerfen `schema.version` als Fallback nutzen
  - Django/Knex verlangen explizites `--version`
  - `--generate-rollback` materialisiert tool-spezifische Down-Artefakte
  - `--report` schreibt einen separaten Report
  - Exit-Codes sind `0`, `2`, `3`, `7`

Konsequenz fuer Phase D:

- Der groesste 0.7.0-Gap liegt nicht mehr im Vertrags- oder Renderer-Layer,
  sondern in der fehlenden produktiven Verdrahtung zwischen CLI,
  Application-Runner und Dateisystem.
- Ohne Phase D bleiben die in Phase B und C gebauten Bausteine fuer Nutzer
  nicht erreichbar.

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- Einfuehrung von `ToolExportRequest`
- Einfuehrung von `ToolExportRunner`
- produktiver Schema-Read-, Validation- und DDL-Generate-Pfad fuer
  Tool-Export
- Dialekt- und `--spatial-profile`-Aufloesung analog zu `schema generate`
- Bundle-Aufbau aus den in Phase B eingefuehrten Typen
- Lookup und Aufruf des passenden Tool-Exporters
- Kollisionserkennung vor dem ersten Write
- Schreiben der von den Exportern gelieferten Artefakte unterhalb des
  Output-Verzeichnisses
- optionaler Report-Sidecar fuer Notes / `skippedObjects` und
  export-spezifische Hinweise
- neue `ExportCommand`-Gruppe in `buildRootCommand()`
- CLI-Subcommands fuer:
  - Flyway
  - Liquibase
  - Django
  - Knex
- Help-Texte und Command-Tests fuer Pflichtflags, Versionsregeln, Kollisionen,
  Rollback, Reports und Exit-Codes

### 3.2 Bewusst nicht Teil von Phase D

- neue Tool-Renderer oder Aenderungen an ihren Render-Vertraegen
- neue Tool-Runtime-Abhaengigkeiten im Produktionscode
- echte Flyway-/Liquibase-/Django-/Knex-Ausfuehrungstests
- automatische Mutation bestehender Tool-Projektdateien
- implizites `--force`, stilles Ueberschreiben oder sonstige
  Konfliktauflockerung
- diff-basierter `schema migrate`- oder `schema rollback`-Pfad
- Release-/Smoke-Dokumentation aus Phase F

---

## 4. Leitentscheidungen fuer Phase D

### 4.1 `ToolExportRunner` ist der eine produktive Orchestrator fuer 0.7.0-Tool-Export

Phase D fuehrt keinen verstreuten Exportpfad ein, bei dem Teile in Commands,
Teile in Helpern und Teile in Renderern liegen.

Verbindliche Folge:

- `ToolExportRunner` bildet den produktiven Pfad analog zu
  `SchemaGenerateRunner`.
- Die Orchestrierung sitzt im Runner, nicht in den einzelnen Clikt-Commands.
- Der Runner bleibt ueber injizierte Kollaboratoren unit-testbar.
- Die CLI reicht nur einen vollstaendigen Request hinein und mappt den
  Rueckgabecode auf `ProgramResult`.

### 4.2 Die CLI bleibt eine duenne Schale ueber dem Runner

Phase D soll keinen zweiten fachlichen Exportpfad in `adapters:driving:cli`
einbauen.

Verbindliche Folge:

- `ExportFlywayCommand`, `ExportLiquibaseCommand`, `ExportDjangoCommand` und
  `ExportKnexCommand` parsen nur CLI-Flags und bauen einen
  `ToolExportRequest`.
- Gemeinsame Help-Texte und Flag-Definitionen werden soweit sinnvoll
  zusammengehalten, ohne die tool-spezifischen Versionsregeln zu verwischen.
- `buildRootCommand()` erweitert den Root um eine neue `ExportCommand`-Gruppe.
- Phase D fuehrt keine neue globale Bootstrap- oder DI-Schicht ein, solange
  das bestehende Repo-Muster traegt:
  - Commands duerfen wie `SchemaGenerateCommand` und `DataExportCommand`
    die fuer den Runner noetigen Kollaboratoren lokal verdrahten
  - die fachliche Orchestrierung bleibt trotzdem vollstaendig im Runner
- Nicht zulaessig ist ein paralleler Direktpfad, bei dem Commands selbst
  Schema lesen, DDL generieren, Exporter waehlen oder Artefakte schreiben.

### 4.3 Der Request bleibt tool-neutral genug fuer einen gemeinsamen Runner

Die Tool-Subcommands unterscheiden sich in 0.7.0 hauptsaechlich durch das
Zieltool und die Versionsregeln, nicht durch einen komplett anderen
Ausfuehrungspfad.

Verbindliche Folge:

- `ToolExportRequest` enthaelt mindestens:
  - `tool`
  - `source`
  - `output`
  - `target`
  - optionale `version`
  - optionale `spatialProfile`
  - `generateRollback`
  - optionale `report`
  - `verbose`
  - `quiet`
- Der Request transportiert nur Eingaben und Laufkontext, nicht bereits
  gelesene Schemata, DDL-Ergebnisse oder Exporter-Instanzen.
- Die tool-spezifische Version-Validierung erfolgt im Runner ueber die in
  Phase B eingefuehrten Helper.

### 4.4 Der Runner konsumiert den bestehenden full-state-Generate-Pfad und den Phase-B/C-Vertrag

Phase D erfindet keinen Sonderpfad fuer Tool-Export neben dem bestehenden
Generate-Modell.

Verbindliche Folge:

- Schema-Read und Validation folgen demselben Grundpfad wie bei
  `schema generate`.
- Dialekt und `--spatial-profile` werden nach denselben Regeln aufgeloest.
- Up-Inhalt kommt aus `generate(schema, options)`.
- Down-Inhalt kommt bei `--generate-rollback` aus
  `generateRollback(schema, options)`.
- `MigrationIdentity`, `MigrationDdlPayload`, `MigrationRollback` und
  `MigrationBundle` werden zentral im Runner aufgebaut.
- Der Runner uebergibt das Bundle an genau einen passenden Tool-Exporter.

### 4.5 Dateischreiben ist in Phase D erlaubt, aber nur auf Basis geplanter Artefakte

Phase C war bewusst side-effect-frei. Phase D fuehrt den ersten kontrollierten
Write-Pfad fuer Tool-Artefakte ein.

Verbindliche Folge:

- Die Exporter liefern weiterhin nur relative `MigrationArtifact`s.
- Der Runner bildet daraus einen konkreten Write-Plan unterhalb von
  `request.output`.
- Kollisionen mit bestehenden Zieldateien werden vor dem ersten Write geprueft.
- Zwei Artefakte desselben Laufs duerfen nicht auf denselben relativen Pfad
  zeigen.
- Es gibt in 0.7.0 kein stilles Ueberschreiben und kein implizites `--force`.

### 4.6 Report und `stderr` muessen generatorische und export-spezifische Diagnostik gemeinsam sichtbar machen

Phase B hat Generator- und Export-Diagnostik bewusst getrennt modelliert.
Phase D ist der erste produktive Pfad, der beide sichtbar zusammenfuehren muss.

Verbindliche Folge:

- Generatorische Notes und `skippedObjects` bleiben der kanonische
  Diagnosepfad.
- Exporter-spezifische Hinweise bleiben `ToolExportNote`.
- Der Runner wirft keinen dieser Kanaele weg.
- Bei `--report` wird ein Sidecar geschrieben, das mindestens den in
  Masterplan und `docs/cli-spec.md` beschriebenen Mindestvertrag erfuellt:
  - Notes
  - `skippedObjects`
- Wenn der Report in Phase D darueber hinaus auch Export-Notes, Source-/Target-
  /Tool-Kontext oder geschriebene Artefaktpfade aufnimmt, ist das zulaessig,
  aber kein eigenstaendiges Phase-D-Abnahmekriterium ohne upstream
  synchronisierte Spezifikation.
- Unabhaengig vom Report werden relevante Hinweise auch im CLI-Pfad sichtbar:
  - Fehler ueber den ueblichen Fehlerkanal
  - Warnungen / Action-Required-Hinweise ueber `stderr`

### 4.7 `--output-format` ist in Phase D nicht Teil des Export-Runners

Tool-Artefakte haben ihr eigenes festes Format pro Exporter. Der globale
`--output-format`-Switch betrifft andere CLI-Kommandos, nicht den
Tool-Export.

Verbindliche Folge:

- Flyway-, Liquibase-, Django- und Knex-Dateien bleiben immer in ihrem
  tool-spezifischen Textformat.
- Der Runner kennt in Phase D kein `outputFormat`-Feld und gibt
  CLI-Meldungen ausschliesslich als Plain-Text auf `stderr` aus.
- Ein spaeterer JSON-/YAML-Modus fuer Export-CLI-Meldungen waere ein
  eigener Folge-Scope.

### 4.8 Versions- und Exit-Code-Regeln werden nicht pro Command neu erfunden

Die fachlichen Regeln wurden in Phase A beschrieben und in Phase B
vorbereitet. Phase D setzt sie nur produktiv um.

Verbindliche Folge:

- Flyway/Liquibase duerfen `schema.version` nur ueber den dokumentierten
  Fallback nutzen.
- Django/Knex scheitern ohne explizites `--version` mit Exit `2`, auch wenn
  das Schema bereits `schema.version` traegt.
- fehlendes `--target` scheitert mit Exit `2`.
- Schema-Validierungsfehler bleiben Exit `3`.
- Parse-, I/O-, Render- und Kollisionsfehler bleiben Exit `7`.

---

## 5. Arbeitspakete fuer Phase D

### D.1 Gemeinsamen Request- und Runner-Pfad in `hexagon:application` einfuehren

Mindestens noetig:

- `ToolExportRequest`
- `ToolExportRunner`
- injizierbare Kollaboratoren fuer:
  - Schema-Read
  - Validation
  - Generator-Lookup
  - Exporter-Lookup
  - Dateisystem-Write
  - Report-Write
  - Fehler- und Hinweis-Ausgabe

### D.2 Bundle-Aufbau und Exporter-Delegation verdrahten

Mindestens noetig:

- Dialekt- und Spatial-Profile-Aufloesung
- Versions- und Slug-Aufloesung ueber die Phase-B-Helper
- DDL-Normalisierung und Bundle-Aufbau
- Delegation an den passenden Tool-Exporter
- konsistentes Handling von Up-only versus Up+Down

### D.3 Write-Plan, Kollisionspruefung und Artefakt-Write einfuehren

Mindestens noetig:

- Aufloesung relativer Artefakte unter `request.output`
- harte Kollisionspruefung vor dem ersten Write
- Schreiben aller Artefakte mit reproduzierbarem Textinhalt
- Erfolgsmeldungen fuer geschriebene Artefakte

### D.4 Report-Sidecar und Diagnosekanal integrieren

Mindestens noetig:

- Ausgabe von Notes / `skippedObjects` auf `stderr`
- Ausgabe von `ToolExportNote`s auf `stderr`
- optionaler Report unter `--report`
- kein impliziter Report-Write ohne `--report`, solange die
  CLI-Spezifikation fuer `export` keinen Default-Sidecar definiert

### D.5 Neue CLI-Gruppe `export` in `adapters:driving:cli` einfuehren

Mindestens noetig:

- `ExportCommand`
- `ExportFlywayCommand`
- `ExportLiquibaseCommand`
- `ExportDjangoCommand`
- `ExportKnexCommand`
- Einhaengen in `buildRootCommand()`

### D.6 Help-Texte und Command-Validierung absichern

Mindestens noetig:

- `export --help`
- `export flyway --help`
- `export liquibase --help`
- `export django --help`
- `export knex --help`
- klare Fehlermeldungen fuer:
  - fehlendes `--target`
  - fehlendes `--version` bei Django/Knex
  - ungueltigen Dialekt
  - ungueltiges Spatial-Profil

### D.7 Runner- und CLI-Tests fuer den gesamten Pfad aufbauen

Mindestens noetig:

- Runner-Tests fuer:
  - Erfolgspfad
  - Validation-Failure
  - Parse-Fehler
  - Kollisionsfehler
  - Report an/aus
  - Rollback an/aus
- CLI-Tests fuer:
  - Root-Wiring
  - Help-Texte
  - Pflichtflags
  - tool-spezifische Versionsregeln
  - Exit-Code-Mapping

Ziel von Phase D:

- `d-migrate export ...` ist ueber die Root-CLI erreichbar, schreibt
  deterministische Artefakte und reportet konsistent auf Basis des in
  Phase A bis C definierten Vertrags.

---

## 6. Technische Zielstruktur fuer Phase D

Bevorzugte Struktur:

- `hexagon/application`
  - `src/main/kotlin/dev/dmigrate/cli/commands/`
    - `ToolExportRequest.kt`
    - `ToolExportRunner.kt`
    - optionale kleine Export-Helper fuer Report-/Write-Koordination
- `adapters/driving/cli`
  - `src/main/kotlin/dev/dmigrate/cli/commands/`
    - `ExportCommands.kt`
    - `ExportFlywayCommand.kt`
    - `ExportLiquibaseCommand.kt`
    - `ExportDjangoCommand.kt`
    - `ExportKnexCommand.kt`
- bestehendes Modul `adapters/driven/integrations`
  - unveraendert als Render-Layer, nur konsumiert vom Runner

Illustrativer Zielzuschnitt:

```kotlin
package dev.dmigrate.cli.commands

data class ToolExportRequest(
    val tool: MigrationTool,
    val source: Path,
    val output: Path,
    val target: String,
    val version: String? = null,
    val spatialProfile: String? = null,
    val generateRollback: Boolean,
    val report: Path? = null,
    val verbose: Boolean,
    val quiet: Boolean,
)

class ToolExportRunner(
    private val schemaReader: (Path) -> SchemaDefinition,
    private val validator: (SchemaDefinition) -> ValidationResult,
    private val generatorLookup: (DatabaseDialect) -> DdlGenerator,
    private val exporterLookup: (MigrationTool) -> ToolMigrationExporter,
) {
    fun execute(request: ToolExportRequest): Int {
        TODO("Read schema, validate, build bundle, render artifacts, check collisions, write output")
    }
}
```

Gradle-/Modulzuschnitt:

- `hexagon:application` kapselt die Orchestrierung
- `adapters:driving:cli` kapselt nur Clikt-Wiring
- `adapters:driven:integrations` bleibt Renderer-Modul und bekommt keine
  CLI-Verantwortung
- keine Tool-Runtime-Abhaengigkeiten in Phase D

Nicht Teil der Zielstruktur von Phase D:

- echte Flyway-/Liquibase-/Python-/Node-Runtime-Setups
- Release-Smokes
- diff-basierte Migrations-Runner

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `docs/implementation-plan-0.7.0.md`
- `docs/ImpPlan-0.7.0-A.md`
- `docs/ImpPlan-0.7.0-B.md`
- `docs/ImpPlan-0.7.0-C.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/...`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/...`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/...`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/...`

Indirekt als Ist-Referenz relevant:

- `docs/cli-spec.md`
- `docs/design.md`
- `docs/architecture.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/migration/...`
- `adapters/driven/integrations/src/main/kotlin/dev/dmigrate/integration/...`

Noch nicht Teil von Phase D, aber Folgeartefakte vorzubereiten:

- Runtime-Tests in Phase E
- Release-/Smoke-Pfade in Phase F

---

## 8. Akzeptanzkriterien

- [x] `ToolExportRequest` und `ToolExportRunner` existieren im
      Application-Layer.
- [x] `d-migrate export flyway`, `export liquibase`, `export django` und
      `export knex` sind ueber `buildRootCommand()` erreichbar.
- [x] `export --help`, `export flyway --help`, `export liquibase --help`,
      `export django --help` und `export knex --help` sind erreichbar und
      beschreiben Pflichtflags, Versionsregeln und Rollback-Verhalten
      konsistent zu `docs/cli-spec.md`.
- [x] Alle vier Export-Subcommands lesen ein neutrales Schema aus `--source`
      und keinen Live-DB-Operand.
- [x] `--output` ist fuer alle vier Export-Subcommands Pflicht und wird als
      Ausgabeverzeichnis behandelt.
- [x] `--target` ist fuer alle vier Export-Subcommands Pflicht.
- [x] Flyway/Liquibase nutzen ohne explizites `--version` nur den
      dokumentierten `schema.version`-Fallback.
- [x] Django/Knex scheitern ohne explizites `--version` mit Exit `2`.
- [x] `--spatial-profile` wird fuer Tool-Export analog zu
      `schema generate` validiert.
- [x] `--generate-rollback` fuehrt zu tool-spezifischen Down-Artefakten,
      sofern der Exporter sie fuer das Tool materialisiert.
- [x] Der Runner baut `MigrationBundle` und delegiert an genau einen
      passenden `ToolMigrationExporter`.
- [x] Artefakte werden nur unterhalb des Output-Verzeichnisses geschrieben.
- [x] Dateikollisionen werden vor dem ersten Write erkannt und fuehren zu
      Exit `7`.
- [x] Exporter-Notes, Generator-Notes und `skippedObjects` bleiben sichtbar und
      gehen weder im CLI-Pfad noch im Report verloren.
- [x] `--report` schreibt mindestens den dokumentierten Report fuer Notes /
      `skippedObjects`; weitergehende Reportfelder sind optional, solange sie
      dem Mindestvertrag nicht widersprechen.
- [x] Parse-/I/O-/Render-/Kollisionsfehler enden mit Exit `7`.
- [x] Schema-Validierungsfehler enden mit Exit `3`.
- [x] Erfolgreiche Runs enden mit Exit `0`.

---

## 9. Verifikation

Mindestumfang fuer die Phase-D-Umsetzung:

1. Modul-Testlauf (via Docker, kein lokales JDK noetig):

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:application:test :adapters:driving:cli:test" \
  -t d-migrate:phase-d .
```

2. Help-Texte und Command-Wiring pruefen (im Build-Container):

```bash
docker build -t d-migrate:dev .
docker run --rm d-migrate:dev export --help
docker run --rm d-migrate:dev export flyway --help
docker run --rm d-migrate:dev export liquibase --help
docker run --rm d-migrate:dev export django --help
docker run --rm d-migrate:dev export knex --help
```

Dabei explizit pruefen:

- `export --help` ist erreichbar
- `export flyway --help` und `export liquibase --help` markieren `--version`
  nicht als Pflichtflag
- `export django --help` und `export knex --help` markieren `--version` als
  Pflichtflag
- alle Export-Help-Texte nennen `--target`, `--output`, `--report` und
  `--generate-rollback` konsistent zum CLI-Vertrag

3. Runner- und Request-Einfuehrung pruefen (im Build-Container):

```bash
docker run --rm d-migrate:phase-d \
  grep -rn "ToolExportRequest\|ToolExportRunner" \
  hexagon/application/src/main/kotlin hexagon/application/src/test/kotlin
```

4. Runner-/CLI-Tests fuer Pflichtfaelle pruefen:

```bash
docker run --rm d-migrate:phase-d \
  grep -rn "report\|collision\|rollback\|exit" \
  hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/ToolExportRunnerTest.kt
```

Dabei explizit pruefen:

- `buildRootCommand()` enthaelt die neue `export`-Gruppe
- `export --help`, `export flyway --help`, `export liquibase --help`,
  `export django --help` und `export knex --help` laufen erfolgreich und
  spiegeln die dokumentierten Pflichtflags
- Flyway/Liquibase akzeptieren den dokumentierten `schema.version`-Fallback
- Django/Knex scheitern ohne explizites `--version` mit Exit `2`
- fehlendes `--target` scheitert fuer alle Export-Subcommands mit Exit `2`
- ungueltige Schema-Dateien liefern Exit `7`
- Schema-Validierungsfehler liefern Exit `3`
- Kollisionen im Output-Verzeichnis liefern Exit `7`
- `--generate-rollback` fuehrt zum Schreiben der erwarteten Down-Artefakte
- `--report` schreibt mindestens einen Report-Sidecar fuer Notes /
  `skippedObjects`
- `--output-format` veraendert nicht die Tool-Artefakte selbst

---

## 10. Risiken und offene Punkte

### R1 - CLI- und Runner-Logik koennen wieder auseinanderlaufen

Wenn einzelne Export-Commands beginnen, Dialekt-, Versions- oder
Kollisionslogik lokal zu replizieren, verliert 0.7.0 den gemeinsamen
Anwendungspfad und wird schwer testbar.

### R2 - Report und `stderr` koennen einen Teil der Diagnostik verschlucken

Phase B trennt Generator- und Export-Hinweise bewusst. Phase D muss diese
beide Kanaele sichtbar machen, ohne sie in ein loses Freitextprotokoll
umzudeuten.

### R3 - Dateikollisionen in echten Migrationsverzeichnissen sind teuer

Stilles Ueberschreiben eines bestehenden Changelogs oder einer vorhandenen
Migration waere deutlich schaedlicher als ein frueher Fehler.

### R4 - Phase D koennte zu frueh in Runtime-Themen aus Phase E hineinwachsen

Sobald Phase D beginnt, Tool-Projekte anzulegen, externe Tools zu starten oder
CI-Runtime-Fragen mitzuloesen, wird der Milestone unscharf. Phase D endet beim
produktiven Exportpfad, nicht bei der Ausfuehrung der Artefakte.
