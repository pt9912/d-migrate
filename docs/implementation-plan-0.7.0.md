# Implementierungsplan: Milestone 0.7.0 - Tool-Integrationen

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.7.0. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage waehrend der Umsetzung.
>
> Status: Draft (2026-04-14)
> Referenzen: `docs/roadmap.md` Milestone 0.7.0, `docs/lastenheft-d-migrate.md`
> LF-011 / LF-014, `docs/cli-spec.md` Abschnitt `export flyway` /
> `export liquibase` / `export django` / `export knex`, `docs/design.md`
> Abschnitt Integrations-Export / Migrations-Rollback,
> `docs/ddl-generation-rules.md`, `docs/architecture.md`.

---

## 1. Ziel

Milestone 0.7.0 oeffnet d-migrate in Richtung bestehender
Migrations-Toolchains. Das Ziel ist nicht, die Kernfunktionalitaet an
Flyway, Liquibase, Django oder Knex zu koppeln, sondern aus dem neutralen
Schema heraus reproduzierbare Artefakte fuer diese Tools zu erzeugen.

0.7.0 liefert:

- optionale Output-Pfade fuer:
  - Flyway
  - Liquibase
  - Django
  - Knex.js
- eine neue CLI-Gruppe `d-migrate export ...`
- einen gemeinsamen, tool-neutralen Migrations-Bundle-Vertrag fuer
  Versionskennung, Up/Down-Inhalt, Notes und Datei-Artefakte
- Wiederverwendung der bestehenden DDL-Generatoren statt eines zweiten
  parallelen SQL-Erzeugungspfads
- einen expliziten Rollback-Vertrag fuer Tool-Exporte auf Basis von LF-014
- echte Ausfuehrungstests fuer die generierten Artefakte in fokussierten
  Tool-/Dialekt-Kombinationen

Das Ergebnis von 0.7.0 ist damit:

- d-migrate bleibt im Kern tool-agnostisch,
- kann sich aber in gaengige Migrations-Workflows einklinken,
- ohne dass Benutzer ihre Schemas manuell in vier verschiedene
  Toolformate uebertragen muessen.

---

## 2. Ausgangslage

Stand im aktuellen Repo:

- `schema generate` kann heute bereits:
  - ein neutrales Schema lesen,
  - validieren,
  - dialektspezifische DDL erzeugen,
  - optional `generateRollback(...)` nutzen,
  - Notes und `skippedObjects` reporten.
- Die DDL-Erzeugung ist pro Dialekt bereits testseitig breit abgesichert.
- `DdlGenerator.generate(...)` und `generateRollback(...)` existieren als
  driverseitige Primitive, aber noch nicht als expliziter
  Migrations-Bundle-Vertrag fuer externe Tool-Integrationen.
- Die aktuelle CLI-Hierarchie kennt nur:
  - `schema`
  - `data`
- Ein Top-Level-Command `export` existiert noch nicht.
- Ein Integrations-Adaptermodul fuer externe Tools existiert noch nicht.
- `docs/cli-spec.md` beschreibt `export flyway` / `export liquibase` /
  `export django` / `export knex` bisher nur als knappen Stub mit:
  - `--source`
  - `--output`
  - `--target`
  - `--version`
- Dieser Stub ist fuer die reale Umsetzung zu duenn, weil er noch nicht
  klaert:
  - wie Rollback materialisiert wird,
  - wie Versions- und Dateinamen deterministisch gebildet werden,
  - wie bestehende Generate-Optionen wie `--spatial-profile` vererbt werden,
  - wie Warnings / `skippedObjects` in einem Tool-Export sichtbar bleiben,
  - ob bestehende Projektdateien wie Liquibase-Master-Changelogs oder
    Django-/Knex-Scaffolds mutiert werden duerfen.

Wichtig fuer 0.7.0:

- LF-011 fordert optionale Ausgabepfade fuer bestehende Tools.
- LF-014 fordert Rollback-Unterstuetzung.
- Das heutige `generateRollback(...)` ist dafuer eine wichtige Grundlage,
  aber noch nicht dasselbe wie ein belastbarer Tool-Export-Vertrag.

0.7.0 startet also nicht bei null. Die groesste Luecke ist nicht die
SQL-Erzeugung selbst, sondern:

- die Kapselung als migrationsfaehiges Artefakt-Bundle,
- die tool-spezifische Verpackung,
- die neue CLI-Oberflaeche,
- und die Validierung gegen echte Tool-Runtimes.

---

## 3. Scope

### 3.1 In Scope fuer 0.7.0

- neue Top-Level-CLI:
  - `d-migrate export flyway`
  - `d-migrate export liquibase`
  - `d-migrate export django`
  - `d-migrate export knex`
- Export aus einer neutralen Schema-Datei (`yaml` / `json`) in
  tool-spezifische Migrationsartefakte
- Wiederverwendung von `DdlGenerator.generate(...)` als Up-Pfad
- Wiederverwendung von `DdlGenerator.generateRollback(...)` als
  Down-/Rollback-Grundlage
- ein gemeinsamer Migrations-Bundle-Vertrag fuer:
  - Tool
  - Dialekt
  - Migrations-ID / Version
  - Slug / Dateinamenbasis
  - Up-Artefakt(e)
  - optionale Down-Artefakt(e)
  - Notes / `skippedObjects`
- tool-spezifische Renderpfade fuer:
  - Flyway-SQL
  - Liquibase-Changesets
  - Django-Migrationsdateien
  - Knex-Migrationsdateien
- deterministische Dateinamen und Kollisionsschutz im Output-Verzeichnis
- vererbte relevante Generate-Optionen fuer Tool-Exporte, mindestens:
  - `--target`
  - `--spatial-profile`
  - `--generate-rollback`
  - `--report`
- Ausfuehrungstests gegen echte Tool-Runtimes in einer fokussierten Matrix
- Doku-Abgleich fuer CLI, Design, Architektur und Release-Smokes

### 3.2 Bewusst nicht Teil von 0.7.0

- generisches `schema migrate` oder `schema rollback` gegen Live-Datenbanken
- diff-basierte inkrementelle Migration zwischen zwei Schemas oder zwei
  Datenbanken
- Mutation bestehender Nutzerdateien wie:
  - Liquibase-Master-Changelog
  - Django-App-Initialisierung
  - Knex-Projektkonfiguration
- Tool-Installation oder Projekt-Bootstrapping auf dem Host des Nutzers
- ORM-native Modellierungslogik jenseits von SQL-Wrapping
- neue Datenbankdialekte
- KI-/Procedure-Arbeit aus 1.1.0
- Profiling-Arbeit aus 0.7.5

Praezisierung:

0.7.0 exportiert Migrationsartefakte aus einem bereits vorliegenden neutralen
Schema. Es fuehrt keinen zweiten, impliziten `schema migrate`-Milestone durch
die Hintertuer ein.

---

## 4. Leitentscheidungen

### 4.1 Tool-Integrationen bleiben optionale Output-Pfade

LF-011 sagt explizit, dass diese Integrationen optionale Ausgabepfade sind
und keine Voraussetzung fuer die Kernfunktionen sein duerfen.

Verbindliche Konsequenz:

- Das neutrale Schema bleibt die kanonische Quelle.
- Die Kernpfade `schema validate`, `schema generate`, `schema reverse`,
  `schema compare`, `data export` und `data import` bleiben ohne externe
  Tool-Abhaengigkeiten nutzbar.
- Externe Tools werden nur fuer Tests, Smokes oder die Nutzung der
  generierten Artefakte benoetigt, nicht fuer deren Erzeugung.

### 4.2 0.7.0 exportiert aus Schema-Dateien, nicht aus Live-DB-Diffs

Der aktuelle CLI-Sollpfad fuer 0.7.0 ist dateibasiert:

```bash
d-migrate export flyway --source <path> --output <dir>
```

Das passt zu den vorhandenen Codepfaden und verhindert, dass 0.7.0 heimlich
einen diff-basierten `schema migrate`-Scope uebernimmt.

Verbindliche Konsequenz:

- `--source` ist in 0.7.0 eine Schema-Datei im neutralen Format.
- `--target` ist ein Dialekt, keine Live-Verbindung.
- DB-zu-DB-, file/db- oder db/db-Migrations-Exporte sind nicht Teil dieses
  Milestones.

### 4.3 Die SQL-Basis kommt aus dem bestehenden `DdlGenerator`

0.7.0 fuehrt keinen zweiten SQL-Generator fuer Tool-Integrationen ein.

Verbindliche Konsequenz:

- Up-Inhalt wird ueber `generator.generate(schema, options)` erzeugt.
- Down-/Rollback-Inhalt wird ueber `generator.generateRollback(schema, options)`
  erzeugt, wenn der Exportpfad dies anfordert.
- Tool-Adapter wrappen diese SQL-Bausteine nur in tool-spezifische Artefakte.

Nicht akzeptabel ist:

- pro Tool erneut SQL-Regeln zu implementieren,
- oder tool-spezifische SQL-Erzeugung von der bestehenden
  DDL-Regelbasis abzukoppeln.

### 4.4 0.7.0 fuehrt einen kanonischen Migrations-Bundle-Vertrag ein

Die heutige DDL-Erzeugung liefert `DdlResult`, aber kein explizites,
tool-neutrales Migrationspaket fuer Version, Artefakte und Reports.

0.7.0 fuehrt deshalb einen gemeinsamen Vertrag ein, z. B. in der Form von:

- `MigrationIdentity`
- `MigrationBundle`
- `MigrationArtifact`
- `ToolExportResult`

Mindestens enthalten:

- `tool`
- `dialect`
- `version`
- `slug`
- `upResult`
- optional `downResult`
- `notes`
- `skippedObjects`
- die zu schreibenden Artefakte als Liste relativer Dateien

Die konkrete Typform ist Implementierungsdetail. Entscheidend ist:

- Application und CLI arbeiten gegen einen gemeinsamen Bundle-Vertrag.
- Tool-Adapter rendern diesen Vertrag, statt lose Strings
  durch mehrere Schichten zu schieben.

### 4.5 Rollback ist tool-spezifisch, aber auf derselben Down-Grundlage

LF-014 ist in 0.7.0 nicht als universelle Live-Ausfuehrung gemeint, sondern
als reversible Exportartefakte.

Verbindliche Konsequenz:

- Flyway:
  - Up-Datei als `V...__....sql`
  - optional Undo-/Rollback-Datei als `U...__....sql`
  - klare Dokumentation, dass die Ausfuehrung von Undo-Dateien von der
    verwendeten Flyway-Edition bzw. Projektpraxis abhaengen kann
- Liquibase:
  - Changeset mit SQL-Body
  - Rollback-Block mit inverser SQL
- Django:
  - reversible `migrations.RunSQL(sql=..., reverse_sql=...)`
- Knex:
  - `exports.up`
  - `exports.down`

Nicht akzeptabel ist:

- fuer jedes Tool einen anderen fachlichen Rollback-Inhalt zu berechnen,
- oder Rollback fuer einzelne Tools still wegzulassen, obwohl
  `--generate-rollback` gesetzt ist.

### 4.6 Relevante Generate-Optionen werden vererbt

Tool-Export ist fachlich ein spezieller Generate-Pfad. Deshalb muessen
relevante Optionen aus `schema generate` erhalten bleiben.

Fuer 0.7.0 verbindlich:

- `--target` bleibt Pflicht oder fachlich gleichwertig aufloesbar
- `--spatial-profile` wird genauso validiert wie im bestehenden
  Generate-Pfad
- `--generate-rollback` steuert, ob Down-Artefakte materialisiert werden
- `--report` schreibt einen separaten Report fuer Notes / `skippedObjects`
- globales `--output-format` steuert nur CLI-Meldungen und Fehler, nicht die
  generierten Tool-Artefakte

### 4.7 Versions- und Dateinamen muessen deterministisch sein

Migrationen sind Build-Artefakte, keine Wegwerfdateien. Deshalb sind
deterministische Namen wichtiger als bequeme Timestamp-Autogenerierung.

Verbindliche Konsequenz:

- Primare Quelle fuer die Migrations-ID ist `--version`, falls gesetzt.
- Fallback ist `schema.version`.
- Der Dateisuffix-/Slug-Anteil wird aus `schema.name` abgeleitet.
- Wenn keine stabile Version aufloesbar ist, scheitert der Export mit
  einem CLI-Fehler statt einen impliziten Timestamp zu erfinden.
- Bestehende Dateien mit derselben Migrations-ID werden nicht still
  ueberschrieben.

### 4.8 Tool-Export mutiert keine bestehenden Nutzerdateien

Das automatische Umschreiben vorhandener Tool-Projekte waere fuer 0.7.0 zu
riskant und zu tool-spezifisch.

Verbindliche Konsequenz:

- `export liquibase` erzeugt einen neuen versionierten Changelog statt einen
  bestehenden Master-Changelog still zu editieren
- `export django` erzeugt eine Migration-Datei, aber nicht automatisch
  Projekt-/App-Scaffolding
- `export knex` erzeugt eine Migrationsdatei, aber keine `knexfile.*`
- Nutzer koennen die Artefakte bewusst in ihre Toolchains uebernehmen

Das reduziert Seiteneffekte und haelt 0.7.0 fokussiert.

### 4.9 Die Ausfuehrungs-Testmatrix ist fokussiert, nicht voll kartesisch

Roadmap 0.7.0 fordert die Ausfuehrung und Validierung generierter
Migrations-Skripte. Ein voller Test ueber alle 4 Tools x 3 Dialekte waere
fuer den Milestone unverhaeltnismaessig schwer.

Verbindliche Konsequenz:

- Renderer- und Bundle-Tests decken alle Tools breit ab
- echte Tool-Runtime-Tests laufen in einer fokussierten Matrix:
  - Flyway -> PostgreSQL
  - Liquibase -> PostgreSQL
  - Django -> SQLite
  - Knex -> SQLite

Wenn diese Matrix gruen ist, ist der Milestone fachlich belastbar, ohne
CI unnoetig aufzublaehen.

---

## 5. Arbeitspakete

### 5.1 CLI-Vertrag fuer `export` schaerfen

Die heutige CLI-Skizze in `docs/cli-spec.md` reicht fuer die reale
Umsetzung nicht aus.

Mindestens noetig:

- neue Top-Level-Gruppe `export`
- Subcommands:
  - `flyway`
  - `liquibase`
  - `django`
  - `knex`
- finaler Flag-Vertrag fuer 0.7.0, mindestens:
  - `--source`
  - `--output`
  - `--target`
  - `--version`
  - `--spatial-profile`
  - `--generate-rollback`
  - `--report`
- klare Exit-Code-Matrix:
  - `0` Erfolg
  - `2` ungueltige CLI-Argumente / unzulaessige Kombinationen
  - `3` Schema-Validierungsfehler
  - `7` Parse-, I/O-, Render- oder Dateikollisionsfehler

### 5.2 Gemeinsames Migrations-Bundle im Application-/Port-Layer einfuehren

Vor den Tool-Adaptern braucht 0.7.0 einen stabilen Zwischenvertrag.

Mindestens noetig:

- Typen fuer:
  - Tool-Enum
  - Migrations-ID / Slug
  - Up-/Down-Bundle
  - einzelne Artefakte
  - Ergebnis / Report-Metadaten
- Normalisierung von:
  - `--version`
  - `schema.version`
  - `schema.name`
- Kollisionspruefung gegen bereits vorhandene Zieldateien
- ein expliziter Port fuer tool-spezifische Renderer / Writer

### 5.3 Tool-Adapter fuer Flyway, Liquibase, Django und Knex bauen

Die eigentlichen Integrationen sitzen in einem neuen driven Adaptermodul.

Mindestens noetig:

- Flyway-Renderer:
  - `V...__....sql`
  - optional `U...__....sql`
- Liquibase-Renderer:
  - versionierter Changelog mit SQL-Block
  - optional Rollback-Block
- Django-Renderer:
  - Python-Datei mit `migrations.RunSQL`
  - optional `reverse_sql`
- Knex-Renderer:
  - JS-Datei mit `exports.up`
  - optional `exports.down`

Alle Renderer muessen:

- deterministische Dateinamen erzeugen
- mehrere SQL-Statements sauber quoten/escapen
- Notes / `skippedObjects` nicht verlieren
- ihre Artefakte nur unterhalb des angegebenen Output-Verzeichnisses anlegen

### 5.4 Runner- und Report-Pfad fuer Tool-Export aufbauen

Analog zu `SchemaGenerateRunner` braucht 0.7.0 einen dedizierten
Application-Runner.

Mindestens noetig:

- `ToolExportRequest`
- `ToolExportRunner`
- Schema-Read + Validation
- Dialekt- und Spatial-Profile-Aufloesung
- Erzeugung von Up-/Down-`DdlResult`
- Bundle-Aufbau
- Delegation an den passenden Tool-Adapter
- Report-Sidecar fuer Notes / `skippedObjects`
- sauberes Exit-Code-Mapping

### 5.5 CLI-Wiring und Help-/Smoke-Pfade erweitern

Die Root-CLI kennt heute nur `schema` und `data`.

Mindestens noetig:

- neue `ExportCommand`-Gruppe in `buildRootCommand()`
- Help-Texte fuer:
  - `export --help`
  - `export flyway --help`
  - `export liquibase --help`
  - `export django --help`
  - `export knex --help`
- Command-Tests fuer:
  - Pflichtflags
  - Version-/Slug-Aufloesung
  - Kollisionen im Output
  - `--generate-rollback`
  - `--spatial-profile`
  - `--report`

### 5.6 Echte Tool-Runtime-Validierung aufbauen

Roadmap 0.7.0 fordert nicht nur Renderer-Tests, sondern Ausfuehrung der
generierten Artefakte.

Mindestens noetig:

- Flyway-Test gegen PostgreSQL:
  - versionierte SQL-Datei wird angewendet
  - erzeugte Tabellen / Objekte existieren
- Liquibase-Test gegen PostgreSQL:
  - Changeset wird angewendet
  - optional Rollback-Block wird ausgefuehrt
- Django-Test gegen SQLite:
  - Migration wird ueber minimales Django-Projekt geladen
  - `migrate` funktioniert
  - optional Reverse-Pfad funktioniert
- Knex-Test gegen SQLite:
  - `migrate:latest`
  - `migrate:rollback`

### 5.7 Doku, Smokes und Release-Pfade nachziehen

0.7.0 ist erst abgeschlossen, wenn CLI-Spec, Design und Release-Smokes
denselben Vertrag beschreiben.

Mindestens noetig:

- `docs/cli-spec.md` fuer finalen Export-Vertrag nachziehen
- `docs/design.md` fuer Integrations-Export und Rollback-Zielbild
  nachziehen
- `docs/architecture.md` um neuen Integrations-Adapter und CLI-Pfad
  ergaenzen
- `docs/releasing.md` um mindestens einen Tool-Export-Smoke erweitern

---

## 6. Technische Zielstruktur

0.7.0 braucht eine neue, aber schmale Export-Achse.

Bevorzugte Struktur:

- `hexagon/ports`
  - tool-neutrale Export-Typen / Portvertrag
  - z. B.:
    - `MigrationTool`
    - `MigrationIdentity`
    - `MigrationBundle`
    - `MigrationArtifact`
    - `ToolMigrationExporter`
- `hexagon/application`
  - `ToolExportRequest`
  - `ToolExportRunner`
  - helper fuer:
    - Versionsauflosung
    - Slug-Normalisierung
    - Kollisionspruefung
    - Exit-Code-Mapping
- `adapters/driven/integrations`
  - `FlywayMigrationExporter`
  - `LiquibaseMigrationExporter`
  - `DjangoMigrationExporter`
  - `KnexMigrationExporter`
  - optional gemeinsamer Lookup / Registry
- `adapters/driving/cli`
  - `ExportCommand`
  - `ExportFlywayCommand`
  - `ExportLiquibaseCommand`
  - `ExportDjangoCommand`
  - `ExportKnexCommand`

Reused statt neu erfunden:

- Schema-Lesen weiter ueber `SchemaFileResolver`
- Validation weiter ueber `SchemaValidator`
- SQL-Erzeugung weiter ueber `DatabaseDriverRegistry.get(...).ddlGenerator()`
- Spatial-Profil-Aufloesung weiter ueber `SpatialProfilePolicy`

Nicht Zielstruktur von 0.7.0:

- eigener zweiter SQL-Generator
- automatisches Projekt-Patching fuer bestehende Tool-Repositories
- diff-basierter `schema migrate`-Pfad

---

## 7. Betroffene Artefakte

Direkt betroffen oder neu einzufuehren:

- `settings.gradle.kts`
- neues Modul:
  - `adapters/driven/integrations`
- Port-/Application-Typen unter:
  - `hexagon/ports/src/main/kotlin/...`
  - `hexagon/application/src/main/kotlin/...`
- CLI-Wiring unter:
  - `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
  - `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/`
- Tests unter:
  - `hexagon/application/src/test/kotlin/...`
  - `adapters/driven/integrations/src/test/kotlin/...`
  - `adapters/driving/cli/src/test/kotlin/...`
- Doku:
  - `docs/cli-spec.md`
  - `docs/design.md`
  - `docs/architecture.md`
  - `docs/releasing.md`

Wahrscheinlich wiederverwendete bestehende Artefakte:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- Fixtures unter:
  - `adapters/driven/formats/src/test/resources/fixtures/schemas/`

---

## 8. Akzeptanzkriterien

- [ ] `d-migrate export flyway`, `export liquibase`, `export django` und
      `export knex` sind ueber die CLI erreichbar.
- [ ] Tool-Exporte arbeiten aus einer neutralen Schema-Datei und nicht aus
      Live-DB-Verbindungen oder impliziten Diffs.
- [ ] 0.7.0 fuehrt einen gemeinsamen Migrations-Bundle-Vertrag fuer
      Tool-Exporte ein.
- [ ] Alle Tool-Exporte nutzen denselben bestehenden `DdlGenerator`-Pfad
      fuer Up- und optional Down-Inhalt.
- [ ] `--spatial-profile` wird fuer Tool-Exporte genauso validiert wie fuer
      `schema generate`.
- [ ] `--generate-rollback` materialisiert tool-spezifische Down-Artefakte:
      Flyway Undo, Liquibase Rollback, Django `reverse_sql`, Knex
      `exports.down`.
- [ ] Tool-Exporte verlieren Notes / `skippedObjects` nicht; diese sind
      mindestens ueber `stderr` und Report-Sidecar sichtbar.
- [ ] Dateinamen und Migrations-IDs sind deterministisch aus `--version`
      bzw. `schema.version` plus `schema.name` ableitbar.
- [ ] Bestehende Dateien mit derselben Migrations-ID werden nicht still
      ueberschrieben.
- [ ] Tool-Export mutiert keine bestehenden Nutzerdateien ausser den
      explizit erzeugten Artefakten im Output-Verzeichnis.
- [ ] Renderer-/Bundle-Tests decken alle vier Tools ab.
- [ ] Echte Tool-Runtime-Tests laufen mindestens fuer:
      Flyway->PostgreSQL, Liquibase->PostgreSQL, Django->SQLite,
      Knex->SQLite.
- [ ] `docs/cli-spec.md`, `docs/design.md`, `docs/architecture.md` und
      `docs/releasing.md` beschreiben denselben 0.7.0-Vertrag.

---

## 9. Verifikation

Mindestumfang fuer die Umsetzung:

1. Modul-Testlauf fuer Ports, Application, Integrations-Adapter und CLI:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:ports:test :hexagon:application:test :adapters:driven:driver-common:test :adapters:driven:formats:test :adapters:driven:integrations:test :adapters:driving:cli:test" \
  -t d-migrate:phase-070-build .
```

2. Fokussierte Integrations- und Tool-Runtime-Tests ueber das bestehende
Docker-Skript:

```bash
./scripts/test-integration-docker.sh -PintegrationTests :adapters:driven:integrations:test
```

3. Finale Runtime-Stage fuer CLI-Smokes bauen:

```bash
DOCKER_BUILDKIT=1 docker build \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist" \
  -t d-migrate:phase-070-runtime .
```

4. Fixture-basierte Export-Smokes gegen das minimale Schema:

```bash
SMOKE_DIR="$(mktemp -d)"
mkdir -p "${SMOKE_DIR}/flyway" "${SMOKE_DIR}/liquibase" "${SMOKE_DIR}/django" "${SMOKE_DIR}/knex"

docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/schemas:ro" \
  -v "${SMOKE_DIR}:/out" \
  d-migrate:phase-070-runtime \
  export flyway \
  --source /schemas/minimal.yaml \
  --target postgresql \
  --version 1.0.0 \
  --output /out/flyway \
  --generate-rollback

docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/schemas:ro" \
  -v "${SMOKE_DIR}:/out" \
  d-migrate:phase-070-runtime \
  export liquibase \
  --source /schemas/minimal.yaml \
  --target postgresql \
  --version 1.0.0 \
  --output /out/liquibase \
  --generate-rollback

docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/schemas:ro" \
  -v "${SMOKE_DIR}:/out" \
  d-migrate:phase-070-runtime \
  export django \
  --source /schemas/minimal.yaml \
  --target sqlite \
  --version 0001_initial \
  --output /out/django \
  --generate-rollback

docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/schemas:ro" \
  -v "${SMOKE_DIR}:/out" \
  d-migrate:phase-070-runtime \
  export knex \
  --source /schemas/minimal.yaml \
  --target sqlite \
  --version 0001_initial \
  --output /out/knex \
  --generate-rollback
```

Dabei explizit pruefen:

- Output-Verzeichnisse enthalten deterministisch benannte Artefakte
- Flyway erzeugt `V...` und optional `U...`
- Liquibase enthaelt SQL- und Rollback-Block
- Django enthaelt `RunSQL(..., reverse_sql=...)`
- Knex enthaelt `exports.up` und `exports.down`
- Report-Dateien dokumentieren Notes / `skippedObjects`

5. Tool-Runtime-Validierung gegen die fokussierte Matrix:

```bash
# Beispielhaft:
# - Flyway/Liquibase gegen PostgreSQL-Testcontainer
# - Django/Knex gegen SQLite in Python-/Node-Containern
# Der konkrete Testaufbau sitzt in :adapters:driven:integrations:test.
```

---

## 10. Risiken und offene Punkte

### R1 - Tool-spezifische Wrapper koennen zu viel "Native-Semantik" versprechen

Wenn 0.7.0 versucht, fuer alle vier Tools vollwertig ORM- oder
Framework-native Migrationslogik zu imitieren, wird der Milestone unnoetig
gross und fragil.

Gegenmassnahme:

- 0.7.0 bleibt bewusst bei SQL-basierten, aber reversiblem Wrapping auf
  Tool-Ebene.

### R2 - Flyway-Rollback ist editions- bzw. praxisabhaengig

Undo-Skripte sind in Flyway nicht ueberall gleich nutzbar. Das ist kein Grund,
Rollback fuer Flyway wegzulassen, muss aber explizit dokumentiert werden.

Gegenmassnahme:

- Artefakt trotzdem generieren
- Nutzung im Report und in der CLI-Doku klar einordnen

### R3 - Dateikollisionen in echten Migrationsverzeichnissen sind teuer

Stilles Ueberschreiben eines bestehenden Changelogs oder einer vorhandenen
Migration waere deutlich schaedlicher als ein frueher Fehler.

Gegenmassnahme:

- harte Kollisionspruefung vor dem Schreiben
- kein implizites `--force` in 0.7.0

### R4 - Tool-Runtime-Tests koennen CI spuerbar aufblaehen

Python-, Node-, Flyway- und Liquibase-Runtimes bringen eigene Laufzeit- und
Image-Kosten mit.

Gegenmassnahme:

- fokussierte Matrix statt Vollkartesik
- Renderer-Tests breit, echte Runtime-Tests gezielt

### R5 - Der heutige CLI-Stub fuer 0.7.0 ist unterdefiniert

Die aktuelle Spezifikation in `docs/cli-spec.md` reicht fuer die reale
Umsetzung nicht aus.

Gegenmassnahme:

- 0.7.0 beginnt mit einer expliziten Nachschaerfung des Export-Vertrags
  in CLI-Spec und Design, statt diese Luecke erst waehrend der
  Implementierung zu entdecken.

