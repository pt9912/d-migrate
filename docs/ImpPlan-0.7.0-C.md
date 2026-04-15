# Implementierungsplan: Phase C - Tool-Adapter und Integrationsmodul

> **Milestone**: 0.7.0 - Tool-Integrationen
> **Phase**: C (Tool-Adapter und Integrationsmodul)
> **Status**: Review (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.5 bis 4.9, Abschnitt 5 Phase C, Abschnitt 6,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.7.0-A.md`; `docs/ImpPlan-0.7.0-B.md`;
> `docs/cli-spec.md`; `docs/design.md`; `docs/architecture.md`;
> `docs/lastenheft-d-migrate.md` LF-011 / LF-014

---

## 1. Ziel

Phase C baut den ersten produktionsnahen Codepfad fuer 0.7.0 ausserhalb des
Hexagons: ein neues driven Adaptermodul, das den in Phase B definierten
Bundlevertrag in konkrete Tool-Artefakte fuer Flyway, Liquibase, Django und
Knex uebersetzt.

Der Teilplan liefert bewusst noch keine CLI-Kommandos, keinen
`ToolExportRunner`, keine Report-Sidecars und keine Runtime-Validierung gegen
echte Tool-Projekte. Er schafft die Render- und Modulgrundlage, auf der die
spaeteren Phasen D bis F aufsetzen.

Nach Phase C soll klar und testbar gelten:

- es gibt ein neues Modul `adapters:driven:integrations`
- das Modul implementiert den Portvertrag aus Phase B fuer alle vier Tools
- die Tool-Adapter rendern deterministische Artefakte aus dem Bundlevertrag
- das Modul bleibt side-effect-frei:
  - keine Host-Dateischreiblogik
  - keine Report-Serialisierung
  - keine CLI-Ausgabe
- Rollback wird tool-spezifisch materialisiert:
  - Flyway `U...__...sql`
  - Liquibase `<rollback>`-Block
  - Django `reverse_sql`
  - Knex `exports.down`
- die Adapter verlieren weder generatorische Diagnostik noch export-spezifische
  Hinweise
- die Adapter fuehren keine eigene Versions-, Slug-, Kollisions- oder
  Output-Root-Logik ein, sondern konsumieren den Vertrag aus Phase B

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und der Dokumentation:

- `settings.gradle.kts` kennt heute noch kein Modul
  `adapters:driven:integrations`.
- Unter `adapters/driven/` existieren derzeit:
  - `driver-common`
  - `driver-postgresql`
  - `driver-mysql`
  - `driver-sqlite`
  - `formats`
  - `streaming`
- Die bestehenden driven Adapter zeigen zwei fuer Phase C relevante Muster:
  - klare Modulgrenzen mit eigenem Package-Namespace
  - fokusierte Verantwortungen statt Mischmodulen
- `adapters:driven:formats` enthaelt bereits reine Datei-/Codec-Helfer wie
  `SchemaFileResolver` und `SidecarPath`; diese gehoeren fachlich nicht in das
  neue Integrationsmodul.
- `adapters:driven:streaming` ist Pipeline-/Glue-Code und kein Vorbild fuer
  Tool-Artefakt-Rendering; Phase C braucht keinen Reader/Writer-Orchestrator.
- Phase B definiert fuer 0.7.0 den geplanten Kernvertrag:
  - `MigrationTool`
  - `MigrationIdentity`
  - `MigrationRollback`
  - `MigrationDdlPayload`
  - `MigrationBundle`
  - `ArtifactRelativePath`
  - `MigrationArtifact`
  - `ToolExportNote`
  - `ToolExportResult`
  - `ToolMigrationExporter`
- `docs/cli-spec.md` ist fuer 0.7.0 bereits auf den finalen Exportvertrag
  nachgezogen:
  - `--target` ist Pflicht
  - Django/Knex verlangen explizites `--version`
  - Tool-Artefakte muessen byte-deterministisch sein
  - Rollback ist tool-spezifisch
- `docs/design.md` beschreibt fuer 0.7.0 bereits die gewollten
  Rollback-Artefakte pro Tool.
- Es gibt im Repo noch keinerlei Produktionscode fuer:
  - Flyway-Export
  - Liquibase-Export
  - Django-Export
  - Knex-Export
- Es gibt ebenso noch keine produktiven Runtime-Abhaengigkeiten auf Flyway,
  Liquibase, Django oder Knex; Phase C soll diesen Zustand bewusst
  beibehalten.

Konsequenz fuer Phase C:

- Der groesste 0.7.0-Gap im Aussenring ist aktuell nicht ein fehlender
  Laufzeit-Runner, sondern das komplette Fehlen eines driven Integrationsmoduls.
- Ohne Phase C wuerden spaetere CLI-/Runner-Pfade entweder ins Leere zeigen
  oder Tool-Artefakte ad hoc im Application-/CLI-Code rendern.

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- neues Modul `adapters:driven:integrations`
- Implementierung von `ToolMigrationExporter` fuer:
  - Flyway
  - Liquibase
  - Django
  - Knex
- tool-spezifische Artefaktbildung aus `MigrationBundle`
- deterministische Dateinamens- und Inhaltsbildung innerhalb des bereits aus
  Phase B aufgeloesten Vertrags
- tool-spezifische String-/SQL-Escaping-Regeln fuer:
  - SQL-Dateien
  - XML-Embedding
  - Python-String-Literale
  - JavaScript-String-Literale
- Modul-Unit-Tests fuer Renderer, Artefaktliste und Export-Notes
- Gradle-Wiring des neuen Moduls

### 3.2 Bewusst nicht Teil von Phase C

- `ToolExportRunner`
- `ExportCommand`
- Filesystem-Schreibpfad fuer Output-Verzeichnisse
- Report-Sidecar oder stderr-/stdout-Ausgabe
- Tool-Runtime-Tests gegen PostgreSQL, SQLite, Python oder Node
- Mutation bestehender Nutzerdateien wie:
  - Liquibase-Master-Changelog
  - Django-App-Scaffolding
  - Knex-Projektkonfiguration
- Versions- oder Slug-Aufloesung im Adapter selbst
- Kollisionserkennung gegen echte Dateisystemeintraege

---

## 4. Leitentscheidungen fuer Phase C

### 4.1 `adapters:driven:integrations` ist ein reines Render-Modul

Phase C fuehrt keinen hybriden Adapter aus Rendering, I/O und CLI-Wiring ein.

Verbindliche Folge:

- Das neue Modul implementiert nur die Tool-Exporter aus Phase B.
- Es nimmt `MigrationBundle` entgegen und gibt `ToolExportResult` zurueck.
- Es schreibt nicht selbst auf das Host-Dateisystem.
- Es kennt keine `stdout`-/`stderr`-Ausgabe und keine globalen CLI-Flags.
- Es erzeugt auch keine Report-Sidecars.

### 4.2 Das Integrationsmodul bleibt dependency-arm und fuehrt keine Tool-Runtime-Abhaengigkeiten ein

LF-011 beschreibt diese Integrationen als optionale Output-Pfade, nicht als
neue Pflichtlaufzeit fuer den Kern.

Verbindliche Folge:

- Das Modul haengt im Produktionscode nur von den benoetigten
  Hexagon-Vertraegen ab, primaer `hexagon:ports`.
- Es haengt nicht von `hexagon:application` oder `adapters:driving:cli` ab.
- Es zieht in Phase C keine Laufzeitbibliotheken fuer:
  - Flyway
  - Liquibase
  - Django
  - Knex
- Echte Tool-Runtimes bleiben Thema von Phase E.

### 4.3 Renderer konsumieren den Bundlevertrag und duplizieren keine Upstream-Logik

Version, Slug, Rollback-State, kanonische SQL-Darstellung und
Relativpfadvertrag sind in Phase B bereits geloest.

Verbindliche Folge:

- Die Exporter fuehren keine eigene Versions- oder Slug-Normalisierung ein.
- Sie pruefen keine CLI-Pflichtregeln erneut.
- Sie rechnen keine Output-Root-Pfade aus.
- Sie pruefen keine Host-Dateikollisionen.
- Sie konsumieren den bereits in Phase B definierten Payload aus dem Bundle,
  statt erneut rohe `DdlResult` selbst zu normalisieren oder einen
  Parallelvertrag zu erfinden:
  - Flyway und Liquibase konsumieren fuer single-block-Formate den
    kanonischen Up-/Down-SQL-Block `MigrationDdlPayload.deterministicSql`.
  - Django und Knex konsumieren fuer statement-orientierte Formate die
    generatornahe, geordnete Liste
    `MigrationDdlPayload.result.statements`.
- Django und Knex duerfen `deterministicSql` nicht heuristisch wieder in
  Statements aufspalten; Statement-Grenzen und Reihenfolge kommen aus
  `result.statements` des gemeinsamen Bundlevertrags.

### 4.4 Flyway bekommt genau ein SQL-Artefakt fuer Up und optional eines fuer Undo

Phase C fixiert den minimalen Flyway-Vertrag fuer 0.7.0.

Verbindliche Folge:

- Flyway-Export erzeugt:
  - `V<version>__<slug>.sql`
  - optional `U<version>__<slug>.sql`
- Beide Dateien enthalten direkt SQL und keine weiteren Wrapper-Dateien.
- Das Modul erzeugt keinen Flyway-Konfigurationsfile und keine Projektstruktur.
- Wenn Rollback angefordert ist, aber der Bundlevertrag keinen gueltigen
  Rollback-Payload traegt, ist das ein frueher Fehler vor Artefaktbildung.
- Optional notwendige Hinweise zur Flyway-Undo-Nutzung laufen ueber
  `ToolExportNote`, nicht ueber Generator-Notes.

### 4.5 Liquibase rendert in 0.7.0 ein kanonisches XML-Changeset mit deterministischer interner Identitaet

Liquibase kann mehrere Changelog-Formate lesen. Phase C waehlt bewusst genau
eines, um den Milestone klein und deterministisch zu halten.

Verbindliche Folge:

- Liquibase-Export erzeugt genau eine versionierte XML-Datei, z. B.
  `changelog-<version>-<slug>.xml`.
- Diese XML-Formatwahl ist fuer 0.7.0 keine rein lokale Phase-C-Notiz,
  sondern Teil des uebergeordneten Produktvertrags und muss in
  Masterplan / CLI-Spec / Design deckungsgleich beschrieben sein.
- Die Datei enthaelt genau einen aus dem Bundle abgeleiteten Changeset-Block
  mit eingebettetem SQL.
- `databaseChangeLog`-Root, Namespaces und `schemaLocation` sind
  exporter-seitig fest und reproduzierbar.
- Der einzelne `changeSet` traegt eine deterministische Identitaet aus
  `MigrationIdentity`:
  - `id` wird stabil aus `version`, `slug` und `dialect` abgeleitet
  - `author` ist fuer 0.7.0 ein fixer Exporter-Wert, z. B. `d-migrate`
- Wenn Rollback angefordert ist, enthaelt derselbe Changeset einen
  `<rollback>`-Block mit Down-SQL.
- Das Modul erzeugt keinen Master-Changelog und keine `include`-Kette.
- SQL-Inhalte werden XML-sicher eingebettet, bevorzugt ohne semantische
  Veraenderung des SQL-Texts.

### 4.6 Django bleibt in 0.7.0 bei einer minimalen `RunSQL`-Migration

0.7.0 emuliert keine ORM-native Modellhistorie.

Verbindliche Folge:

- Django-Export erzeugt genau eine Python-Datei
  `<version>_<slug>.py`.
- Die Datei enthaelt eine minimale Migration mit einem `RunSQL`-Wrapper,
  der die geordnete kanonische Statement-Sequenz des Up-Pfads explizit
  traegt und bei angefordertem Rollback eine ebenso geordnete Down-Sequenz
  fuer `reverse_sql`.
- Django bettet damit keinen heuristisch getrennten SQL-Gesamtblock ein.
- Das Modul erzeugt kein App-Scaffolding und keine automatische
  Dependency-Ableitung auf andere Django-Migrationen.
- Python-Code bleibt auf einen kleinen, reproduzierbaren Common-Subset
  beschraenkt:
  - `from django.db import migrations`
  - `class Migration(migrations.Migration):`
  - `dependencies = []`
  - `operations = [...]`

### 4.7 Knex bleibt in 0.7.0 bei CommonJS und `exports.up` / `exports.down`

Phase C fuehrt keinen Variantenraum fuer ESM, TypeScript oder
projektabhaengige Knex-Layouts ein.

Verbindliche Folge:

- Knex-Export erzeugt genau eine JavaScript-Datei
  `<version>_<slug>.js`.
- Die Datei nutzt CommonJS:
  - `exports.up = async function(knex) { ... }`
  - optional `exports.down = async function(knex) { ... }`
- Knex emittiert die kanonische Statement-Sequenz in stabiler Reihenfolge,
  d. h. pro Statement genau einen `await knex.raw(...)`-Aufruf statt einen
  heuristisch aufgespaltenen Gesamtblock.
- Das Modul erzeugt keine `knexfile.*` und keine Verzeichnisstruktur ausser den
  expliziten Artefakten.

### 4.8 Escaping und Textform sind Teil des Adaptervertrags

Die Render-Pfade unterscheiden sich weniger in der SQL-Erzeugung als in der
korrekten Verpackung des SQL in tool-spezifische Texte.

Verbindliche Folge:

- Alle Artefakte werden als UTF-8-Text mit `\n` (LF) erzeugt.
- Flyway-Dateien enthalten direkt die kanonische SQL-Darstellung plus
  Abschluss-Newline.
- Liquibase bettet SQL XML-sicher ein, ohne Mehrstatement-SQL kaputtzuescapen.
- Django escaped Python-kritische Sequenzen reproduzierbar und behaelt die
  vom Bundle vorgegebene Statement-Sequenz bei.
- Knex escaped JavaScript-kritische Sequenzen reproduzierbar und behaelt die
  vom Bundle vorgegebene Statement-Sequenz bei.
- Adapter duerfen SQL-Kommentare, Notes oder Statementreihenfolge nicht
  still veraendern, ausser soweit es der gemeinsame Determinismusvertrag aus
  Phase B ausdruecklich verlangt.

### 4.9 Generator- und Export-Diagnostik bleiben getrennt

Die Integrationsadapter rendern Artefakte; sie werden nicht zum zentralen
Report-Kanal.

Verbindliche Folge:

- Generatorische Notes / `skippedObjects` bleiben im Bundlevertrag.
- Adapter fuegen nur eigene Hinweise als `ToolExportNote` hinzu.
- `ToolExportResult` traegt:
  - Artefaktliste
  - optionale `ToolExportNote`s
- Adapter werfen generatorische Diagnostik weder weg noch duplizieren sie diese
  in eigene Note-Typen.

### 4.10 Das Integrationsmodul kennt kein Output-Wurzelverzeichnis

Der Adapter gibt relative Artefakte zurueck; das Schreiben unterhalb des
Output-Verzeichnisses passiert spaeter.

Verbindliche Folge:

- `MigrationArtifact.relativePath` bleibt relativ.
- Das Modul verwendet keine Hilfen wie `SidecarPath` fuer Reports oder
  Ausgabeverzeichnisse.
- Das Modul trifft keine Aussage darueber, ob Artefakte spaeter:
  - in ein neues Verzeichnis geschrieben,
  - bestehende Dateien ersetzen,
  - oder mit Reports kombiniert werden.

---

## 5. Arbeitspakete fuer Phase C

### C.1 Neues Gradle-Modul `adapters:driven:integrations` einfuehren

Mindestens noetig:

- `settings.gradle.kts` erweitern
- `adapters/driven/integrations/build.gradle.kts` anlegen
- neues Package, z. B. `dev.dmigrate.integration`
- Test-Source-Set anlegen

### C.2 Gemeinsame Render-Helfer fuer Text, Escaping und Artefaktbildung anlegen

Mindestens noetig:

- kleine, testbare Helper fuer:
  - Dateinamensbildung aus `MigrationIdentity`
  - XML-Embedding
  - Python-String-Escaping
  - JavaScript-String-Escaping
- kein Rückfall auf CLI- oder Runner-Helfer

### C.3 Flyway-Exporter implementieren

Mindestens noetig:

- `FlywayMigrationExporter`
- Up-Datei `V...__....sql`
- optional Undo-Datei `U...__....sql`
- optionale `ToolExportNote` fuer Undo-/Edition-Hinweise

### C.4 Liquibase-Exporter implementieren

Mindestens noetig:

- `LiquibaseMigrationExporter`
- kanonische XML-Datei
- deterministischer `databaseChangeLog`-Root
- deterministische Ableitung von `changeSet id` aus `MigrationIdentity`
- fixer Exporter-`author`, z. B. `d-migrate`
- SQL-Changeset
- optional `<rollback>`-Block
- reproduzierbare XML-Struktur und Header

### C.5 Django-Exporter implementieren

Mindestens noetig:

- `DjangoMigrationExporter`
- minimale `RunSQL`-Migration
- geordnete Statement-Sequenz aus
  `MigrationDdlPayload.result.statements` statt heuristischer
  SQL-Wiederaufspaltung
- optional `reverse_sql`
- reproduzierbarer Python-Dateiinhalt

### C.6 Knex-Exporter implementieren

Mindestens noetig:

- `KnexMigrationExporter`
- CommonJS-Datei
- `exports.up`
- sequenzielle `knex.raw(...)`-Emission aus der kanonischen Statementliste
- optional `exports.down`
- reproduzierbarer JavaScript-Dateiinhalt

### C.7 Modul-Unit-Tests fuer alle vier Exporter aufbauen

Mindestens noetig:

- Artefaktanzahl und Artefaktnamen
- Rollback an/aus
- Notes / `ToolExportNote`
- Escaping und Mehrstatement-SQL
- keine absoluten oder ausbrechenden Artefaktpfade

Ziel von Phase C:

- Alle vier Tool-Formate sind renderer-seitig vorhanden und liefern stabile
  Artefaktlisten aus dem Bundlevertrag, ohne dass CLI oder Runtime-Tests schon
  vorausgesetzt werden.

---

## 6. Technische Zielstruktur fuer Phase C

Bevorzugte Struktur:

- neues Modul:
  - `adapters/driven/integrations`
- Produktionscode:
  - `adapters/driven/integrations/src/main/kotlin/dev/dmigrate/integration/`
  - optional Subpackages:
    - `flyway/`
    - `liquibase/`
    - `django/`
    - `knex/`
- Tests:
  - `adapters/driven/integrations/src/test/kotlin/...`

Illustrativer Zielzuschnitt:

```kotlin
package dev.dmigrate.integration

class FlywayMigrationExporter : ToolMigrationExporter {
    override val tool = MigrationTool.FLYWAY

    override fun render(bundle: MigrationBundle): ToolExportResult {
        TODO("Build V/U SQL artifacts from bundle")
    }
}

class LiquibaseMigrationExporter : ToolMigrationExporter {
    override val tool = MigrationTool.LIQUIBASE

    override fun render(bundle: MigrationBundle): ToolExportResult {
        TODO("Build versioned XML changelog from bundle")
    }
}

class DjangoMigrationExporter : ToolMigrationExporter {
    override val tool = MigrationTool.DJANGO

    override fun render(bundle: MigrationBundle): ToolExportResult {
        TODO("Build RunSQL migration file from bundle")
    }
}

class KnexMigrationExporter : ToolMigrationExporter {
    override val tool = MigrationTool.KNEX

    override fun render(bundle: MigrationBundle): ToolExportResult {
        TODO("Build CommonJS migration file from bundle")
    }
}
```

Gradle-Zuschnitt:

- `adapters:driven:integrations` haengt im Produktionscode nur von den
  benoetigten Hexagon-Vertraegen ab
- keine Abhaengigkeit auf `hexagon:application`
- keine Abhaengigkeit auf `adapters:driving:cli`
- keine Tool-Runtime-Libraries in Phase C

Nicht Teil der Zielstruktur von Phase C:

- `ToolExportRunner`
- `ExportCommand`
- Report-Writer
- Filesystem-Writer
- Python-/Node-/Flyway-/Liquibase-Runtime-Setup

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `docs/implementation-plan-0.7.0.md`
- `docs/ImpPlan-0.7.0-B.md`
- `docs/cli-spec.md`
- `docs/design.md`
- `settings.gradle.kts`
- neues Modul:
  - `adapters/driven/integrations`
- Tests unter:
  - `adapters/driven/integrations/src/test/kotlin/...`

Indirekt als Ist-Referenz relevant:

- `docs/architecture.md`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaFileResolver.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SidecarPath.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/...`

Noch nicht Teil von Phase C, aber Folgeartefakte vorzubereiten:

- `ToolExportRunner`
- `ExportCommand`
- Runtime-Tests in Phase E
- Release-/Smoke-Pfade in Phase F

---

## 8. Akzeptanzkriterien

- [ ] `adapters:driven:integrations` ist als neues Modul im Build verdrahtet.
- [ ] Das Modul implementiert `ToolMigrationExporter` fuer Flyway, Liquibase,
      Django und Knex.
- [ ] Die Exporter rendern Artefakte ausschliesslich aus `MigrationBundle` und
      duplizieren keine Version-/Slug-/Kollisionslogik aus Phase B.
- [ ] `docs/implementation-plan-0.7.0.md`, `docs/cli-spec.md` und
      `docs/design.md` beschreiben die fuer 0.7.0 festgelegte
      Liquibase-XML-Formatwahl deckungsgleich:
      genau ein versionierter XML-Changelog mit genau einem deterministischen
      `changeSet`, fixer Exporter-`author`, optionalem `<rollback>`-Block und
      ohne Mutation eines Master-Changelogs.
- [ ] Flyway erzeugt `V<version>__<slug>.sql` und optional
      `U<version>__<slug>.sql`.
- [ ] Liquibase erzeugt einen versionierten XML-Changelog mit SQL-Block,
      deterministischem `databaseChangeLog` / `changeSet id`, fixem
      Exporter-`author` und optionalem `<rollback>`-Block.
- [ ] Django erzeugt eine minimale `RunSQL`-Migration mit optionalem
      `reverse_sql` und explizit erhaltener Statement-Sequenz aus
      `MigrationDdlPayload.result.statements`.
- [ ] Knex erzeugt eine CommonJS-Datei mit `exports.up` und optional
      `exports.down`, jeweils aus
      `MigrationDdlPayload.result.statements`.
- [ ] Alle Artefakte sind byte-deterministisch fuer identische Eingaben.
- [ ] Alle Artefakte nutzen UTF-8 und LF.
- [ ] Exporter fuegen nur eigene `ToolExportNote`s hinzu und verlieren keine
      generatorische Diagnostik.
- [ ] Das Modul fuehrt keine Report-, CLI- oder Dateisystem-Seiteneffekte ein.
- [ ] Das Modul fuehrt in Phase C keine Tool-Runtime-Abhaengigkeiten ein.

---

## 9. Verifikation

Mindestumfang fuer die Phase-C-Umsetzung:

1. Modul- und Klassencheck:

```bash
sed -n '1,40p' settings.gradle.kts

rg -n "FlywayMigrationExporter|LiquibaseMigrationExporter|DjangoMigrationExporter|KnexMigrationExporter|ToolMigrationExporter" \
  adapters/driven/integrations/src/main/kotlin
```

2. Gradle-Zuschnitt pruefen:

```bash
sed -n '1,120p' adapters/driven/integrations/build.gradle.kts
```

3. Renderer- und Escaping-Tests pruefen:

```bash
rg -n "Flyway|Liquibase|Django|Knex|escape|rollback|exports\\.down|reverse_sql|rollback" \
  adapters/driven/integrations/src/test/kotlin
```

4. Modul-Testlauf:

```bash
./gradlew :adapters:driven:integrations:test
```

Dabei explizit pruefen:

- Flyway erzeugt exakt die erwarteten `V...`-/`U...`-Artefakte
- Liquibase enthaelt SQL- und optionalen Rollback-Block sowie
  deterministisches `changeSet id` und fixen Exporter-`author`
- Django enthaelt `RunSQL(..., reverse_sql=...)` mit erhaltener
  Statement-Reihenfolge aus `MigrationDdlPayload.result.statements`
- Knex enthaelt `exports.up` und `exports.down` als sequenzielle
  `knex.raw(...)`-Aufrufe in der Reihenfolge von
  `MigrationDdlPayload.result.statements`
- Renderpfade bleiben relativ und verlassen das Output-Wurzelverzeichnis nicht
- identische Eingaben erzeugen identische Artefaktinhalte
- das Modul hat keine unbeabsichtigten Abhaengigkeiten auf CLI oder
  Tool-Runtimes

---

## 10. Risiken und offene Punkte

### R1 - Renderer ziehen zu viel Verantwortung aus Phase B oder D in sich hinein

Wenn Phase C beginnt, Versionen, Slugs, Reportpfade oder Dateikollisionen selbst
neu zu loesen, verwischt der 0.7.0-Vertrag zwischen Hexagon, Adapter und CLI.

### R2 - Liquibase-/Django-/Knex-Wrapper versprechen mehr Native-Semantik als 0.7.0 tragen kann

0.7.0 basiert bewusst auf SQL-Wrapping, nicht auf ORM- oder Tool-eigener
Migrationslogik. Sobald die Renderer beginnen, mehr als diesen Rahmen zu
simulieren, wird der Milestone schnell gross und fragil.

### R3 - String-Escaping ist der versteckte Hauptfehlerpfad

Die SQL-Erzeugung selbst ist vorhanden; die eigentliche Fehlerquelle in Phase C
liegt im korrekten Verpacken derselben SQL in XML, Python und JavaScript.

### R4 - Tool-Runtime-Abhaengigkeiten koennen zu frueh ins Produktionsmodul einsickern

Wenn Phase C bereits echte Flyway-/Liquibase-/Python-/Node-Libraries in das
Produktionsmodul zieht, wird aus dem Render-Modul unnötig frueh ein schwerer
Runtime-Knoten. Das widerspricht LF-011 als optionalem Output-Pfad.
