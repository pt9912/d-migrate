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

Wichtig:

- 0.7.0 ist kein Ersatz fuer den spaeteren diff-basierten
  `schema migrate`-/`schema rollback`-Pfad aus `docs/design.md` und
  `docs/cli-spec.md`.
- Der Milestone exportiert baseline-/full-state-Artefakte aus genau einem
  neutralen Schema und nicht inkrementelle `DiffResult`-Migrationsschritte
  zwischen zwei Versionen.

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
- Diese DDL-Pfade arbeiten heute auf einem einzelnen Zielschema und nicht auf
  einem `DiffResult` zwischen Alt- und Neu-Zustand.
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
- Gleichzeitig ist es auch nicht dasselbe wie das in `docs/design.md`
  beschriebene diff-basierte Migrations-/Rollback-Zielbild.

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
  tool-spezifische baseline-/full-state-Migrationsartefakte
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

Praeziser:

- 0.7.0 erzeugt initiale bzw. full-state-basierte Tool-Artefakte aus einem
  neutralen Soll-Schema.
- Ein spaeterer echter inkrementeller Migrationspfad bleibt an
  `DiffResult`-basierte Semantik gebunden und ist nicht Teil dieses
  Milestones.

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

### 4.2 0.7.0 exportiert baseline-/full-state-Artefakte aus Schema-Dateien, nicht inkrementelle DiffResult-Migrationen

Der aktuelle CLI-Sollpfad fuer 0.7.0 ist dateibasiert:

```bash
d-migrate export flyway --source <path> --target <dialect> --output <dir>
```

Das passt zu den vorhandenen Codepfaden und verhindert, dass 0.7.0 heimlich
einen diff-basierten `schema migrate`-Scope uebernimmt.

Verbindliche Konsequenz:

- `--source` ist in 0.7.0 eine Schema-Datei im neutralen Format.
- `--target` ist ein Dialekt, keine Live-Verbindung.
- DB-zu-DB-, file/db- oder db/db-Migrations-Exporte sind nicht Teil dieses
  Milestones.
- Die erzeugten Tool-Artefakte repraesentieren den aus einem einzelnen Schema
  abgeleiteten Zielzustand; sie sind nicht als spaetere allgemeine
  Schritt-fuer-Schritt-Migrationen zwischen zwei neutralen Versionen zu
  lesen.
- Der in `docs/design.md` beschriebene diff-basierte Migrations-/Rollback-Pfad
  auf Basis von `DiffResult` bleibt ein spaeterer, getrennter Milestone.

### 4.3 Die SQL-Basis kommt aus dem bestehenden full-state-`DdlGenerator`

0.7.0 fuehrt keinen zweiten SQL-Generator fuer Tool-Integrationen ein.

Verbindliche Konsequenz:

- Up-Inhalt wird ueber `generator.generate(schema, options)` erzeugt.
- Down-/Rollback-Inhalt wird ueber `generator.generateRollback(schema, options)`
  erzeugt, wenn der Exportpfad dies anfordert.
- Tool-Adapter wrappen diese SQL-Bausteine nur in tool-spezifische Artefakte.
- 0.7.0 behauptet damit nicht, bereits den spaeteren diff-basierten
  Migrationsalgorithmus zu liefern; die Artefakte sind baseline-/full-state-
  Exporte auf Basis des aktuellen Generate-Vertrags.

Nicht akzeptabel ist:

- pro Tool erneut SQL-Regeln zu implementieren,
- oder tool-spezifische SQL-Erzeugung von der bestehenden
  DDL-Regelbasis abzukoppeln.
- oder den Exportpfad dokumentarisch als vollwertigen Ersatz fuer
  `DiffResult`-basierte inkrementelle Migrationen auszugeben.

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

Einordnung:

- Die Down-Artefakte von 0.7.0 invertieren den exportierten full-state-Pfad.
- Sie sind damit nicht identisch mit dem spaeteren, aus `DiffResult`
  abgeleiteten Down-Pfad eines echten inkrementellen `schema migrate`.

Nicht akzeptabel ist:

- fuer jedes Tool einen anderen fachlichen Rollback-Inhalt zu berechnen,
- oder Rollback fuer einzelne Tools still wegzulassen, obwohl
  `--generate-rollback` gesetzt ist.

### 4.6 Relevante Generate-Optionen werden vererbt

Tool-Export ist fachlich ein spezieller Generate-Pfad. Deshalb muessen
relevante Optionen aus `schema generate` erhalten bleiben.

Fuer 0.7.0 verbindlich:

- `--target` ist fuer alle Export-Subcommands Pflicht
- Es gibt keinen impliziten Default wie `postgresql`
- `--target` wird nicht aus Config, Profilen oder anderen Defaults
  nachgeladen; der Nutzer benennt den Dialekt explizit
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

- Es gibt eine tool-spezifische Versionsstrategie statt eines globalen
  Einheits-Fallbacks.
- Flyway und Liquibase:
  - primaere Quelle fuer die Migrations-ID ist `--version`, falls gesetzt
  - Fallback ist `schema.version`, sofern diese in eine stabile tool-taugliche
    ID normalisierbar ist
- Django und Knex:
  - `--version` ist Pflicht
  - der Wert muss bereits tool-tauglich sein, z. B. `0001_initial`
  - `schema.version` bleibt dort Metadatum bzw. Report-Kontext und wird nicht
    still als Dateiname oder Modulkennung recycelt
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

## 5. Geplante Arbeitspakete

0.7.0 wird in sechs Phasen geschnitten. Die Reihenfolge ist absichtlich
streng, damit nicht zuerst Tool-Renderer gebaut werden und erst danach der
gemeinsame Export-Vertrag oder die CLI-Semantik korrigiert werden muessen.

Phasenfolge:

- Phase A - Spezifikationsbereinigung und Export-Vertrag
- Phase B - Migrations-Bundle und Identitaet
- Phase C - Tool-Adapter und Integrationsmodul
- Phase D - CLI- und Runner-Pfad
- Phase E - Tool-Runtime-Testmatrix
- Phase F - Doku, Smokes und Release-Pfade

### Phase A - Spezifikationsbereinigung und Export-Vertrag

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
  - `2` ungueltige CLI-Argumente / unzulaessige Kombinationen /
    fehlendes Pflicht-`--target` / fehlendes tool-pflichtiges `--version`
  - `3` Schema-Validierungsfehler
  - `7` Parse-, I/O-, Render- oder Dateikollisionsfehler

Dabei explizit festziehen:

- `--target` ist fuer alle vier Export-Subcommands Pflicht; die bisherige
  Stub-Default-Annahme in `docs/cli-spec.md` wird fuer 0.7.0 verworfen.
- `--version` ist fuer Flyway/Liquibase optional mit `schema.version`-
  Fallback, fuer Django/Knex aber Pflicht.

Ziel von Phase A: Vor der Code-Umsetzung ist klar, dass 0.7.0 einen
baseline-/full-state-Export fuer externe Tools liefert und keinen
inkrementellen `schema migrate`-Ersatz.

### Phase B - Gemeinsames Migrations-Bundle und Identitaet

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
- tool-spezifische Validierung von:
  - Flyway-/Liquibase-kompatiblen Versionen
- Django-/Knex-pflichtigen expliziten Versionsbezeichnern
- Kollisionspruefung gegen bereits vorhandene Zieldateien
- ein expliziter Port fuer tool-spezifische Renderer / Writer

Ziel von Phase B: Die Application arbeitet gegen einen einheitlichen
Migrations-Bundle-Vertrag, bevor ein einziger Tool-Adapter Dateien rendert.

### Phase C - Tool-Adapter und Integrationsmodul

Die eigentlichen Integrationen sitzen in einem neuen driven Adaptermodul.

Mindestens noetig:

- Flyway-Renderer:
  - `V...__....sql`
  - optional `U...__....sql`
- Liquibase-Renderer:
  - versionierter XML-Changelog mit SQL-Block
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

Ziel von Phase C: Alle vier Tool-Formate sind renderer-seitig vorhanden,
ohne dass CLI und Runtime-Tests schon vorausgesetzt werden.

### Phase D - CLI- und Runner-Pfad fuer Tool-Export

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
  - fehlendes `--target`
  - fehlendes `--version` bei Django/Knex trotz vorhandener `schema.version`

Ziel von Phase D: `d-migrate export ...` ist voll verdrahtet, reportet
konsistent und traegt denselben Exit-Code- und Flag-Vertrag wie die
Spezifikation aus Phase A.

### Phase E - Echte Tool-Runtime-Validierung aufbauen

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

Ziel von Phase E: Die generierten Artefakte sind nicht nur formal korrekt,
sondern werden in einer fokussierten Matrix von echten Tool-Runtimes
akzeptiert und ausgefuehrt.

### Phase F - Doku, Smokes und Release-Pfade nachziehen

0.7.0 ist erst abgeschlossen, wenn CLI-Spec, Design und Release-Smokes
denselben Vertrag beschreiben.

Mindestens noetig:

- `docs/cli-spec.md` fuer finalen Export-Vertrag nachziehen
- `docs/design.md` fuer Integrations-Export und Rollback-Zielbild
  nachziehen
- `docs/architecture.md` um neuen Integrations-Adapter und CLI-Pfad
  ergaenzen
- `docs/releasing.md` um mindestens einen Tool-Export-Smoke erweitern

Ziel von Phase F: Specs, Architektur, Living Design und Release-Smokes
sprechen nach der Umsetzung denselben 0.7.0-Vertrag.

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
    - tool-spezifische Versionsvalidierung
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
- implizite Fortschreibung des spaeteren `DiffResult`-Migrationsmodells unter
  dem Label "Tool-Export"

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
- [ ] 0.7.0 beschreibt diese Artefakte explizit als baseline-/full-state-
      Exporte und nicht als Ersatz fuer spaetere `DiffResult`-basierte
      inkrementelle Migrationen.
- [ ] 0.7.0 fuehrt einen gemeinsamen Migrations-Bundle-Vertrag fuer
      Tool-Exporte ein.
- [ ] Alle Tool-Exporte nutzen denselben bestehenden `DdlGenerator`-Pfad
      fuer Up- und optional Down-Inhalt.
- [ ] `--spatial-profile` wird fuer Tool-Exporte genauso validiert wie fuer
      `schema generate`.
- [ ] `--target` ist fuer alle Export-Subcommands Pflicht; es gibt keinen
      impliziten Default-Dialekt.
- [ ] `--generate-rollback` materialisiert tool-spezifische Down-Artefakte:
      Flyway Undo, Liquibase Rollback, Django `reverse_sql`, Knex
      `exports.down`.
- [ ] Tool-Exporte verlieren Notes / `skippedObjects` nicht; diese sind
      mindestens ueber `stderr` und Report-Sidecar sichtbar.
- [ ] Dateinamen und Migrations-IDs sind deterministisch aus `--version`
      bzw. tool-spezifisch zulaessigen Quellen plus `schema.name`
      ableitbar.
- [ ] Flyway/Liquibase koennen `schema.version` als Fallback nutzen; Django
      und Knex verlangen dagegen ein explizites, tool-taugliches
      `--version`.
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
  --output /out/flyway \
  --generate-rollback

docker run --rm \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/schemas:ro" \
  -v "${SMOKE_DIR}:/out" \
  d-migrate:phase-070-runtime \
  export liquibase \
  --source /schemas/minimal.yaml \
  --target postgresql \
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
- Flyway/Liquibase nutzen ohne explizites `--version` den dokumentierten
  `schema.version`-Fallback
- Flyway erzeugt `V...` und optional `U...`
- Liquibase enthaelt SQL- und Rollback-Block
- Django/Knex scheitern ohne explizites tool-taugliches `--version` mit Exit
  `2`, auch wenn das Schema bereits `schema.version` traegt
- Django enthaelt `RunSQL(..., reverse_sql=...)`
- Knex enthaelt `exports.up` und `exports.down`
- fehlendes `--target` scheitert fuer alle Export-Subcommands mit Exit `2`
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
