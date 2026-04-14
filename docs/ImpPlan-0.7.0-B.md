# Implementierungsplan: Phase B - Gemeinsames Migrations-Bundle und Identitaet

> **Milestone**: 0.7.0 - Tool-Integrationen
> **Phase**: B (Gemeinsames Migrations-Bundle und Identitaet)
> **Status**: Draft (2026-04-14)
> **Referenz**: `docs/implementation-plan-0.7.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.3 bis 4.8, Abschnitt 5 Phase B, Abschnitt 6,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.7.0-A.md`; `docs/roadmap.md` Milestone 0.7.0;
> `docs/lastenheft-d-migrate.md` LF-011 / LF-014; `docs/architecture.md`;
> `docs/ddl-generation-rules.md`

---

## 1. Ziel

Phase B schafft den adapterfreien Kernvertrag fuer 0.7.0 im Hexagon:
Bevor Flyway-, Liquibase-, Django- oder Knex-Renderer gebaut werden, muss klar
sein, in welcher Form die Application einen Tool-Export fachlich beschreibt,
wie Migrations-IDs und Slugs deterministisch aufgeloest werden und wie spaetere
Adapter ihre Artefakte liefern, ohne den bestehenden Generate-Pfad zu
unterlaufen.

Der Teilplan liefert bewusst noch keine Tool-Renderer, keine CLI-Kommandos und
keine Dateischreiblogik. Er schafft die Port- und Application-Grundlage, auf
der die spaeteren Phasen C bis F aufsetzen.

Nach Phase B soll im Hexagon klar und testbar gelten:

- es gibt einen tool-neutralen Exportvertrag statt losem String-Shuffling
  zwischen CLI, Runner und Adapter
- eine `MigrationIdentity` loest `--version`, `schema.version` und
  `schema.name` deterministisch und tool-spezifisch auf
- ein `MigrationBundle` kapselt den bestehenden `DdlGenerator`-Output, statt
  einen zweiten SQL-Zwischenvertrag einzufuehren
- Tool-Adapter bekommen einen klaren Render-Port und liefern relative
  Artefakte, nicht freie Host-Pfade
- Kollisionen gegen vorhandene Zieldateien sind vor dem ersten Write
  pruefbar
- Generator-Notes und `skippedObjects` bleiben im Exportvertrag erhalten und
  werden nicht in ein unscharfes neues Diagnosemodell umgegossen
- Source-, Output- und Report-Kontext bleiben ausserhalb des Bundles und werden
  spaeter im Application-/CLI-Layer gefuehrt

---

## 2. Ausgangslage

Aktueller Stand der Codebasis in `hexagon:ports`, `hexagon:application` und
den bestehenden DDL-Pfaden:

- `DdlGenerator` liefert heute bereits den fachlichen Kern fuer 0.7.0:
  - `generate(schema, options)`
  - `generateRollback(schema, options)`
  - Rueckgabe jeweils als `DdlResult`
- `DdlResult` ist generatornah und strukturiert:
  - `statements`
  - abgeleitete `notes`
  - `skippedObjects`
  - `render()`
- `DdlGenerationOptions` ist heute bewusst klein und generatornah; aktuell
  transportiert es nur `spatialProfile`.
- `SchemaDefinition` liefert bereits die Metadaten, aus denen 0.7.0 spaeter
  Identitaet und Dateinamensbestandteile ableiten muss:
  - `name`
  - `version`
- `SchemaGenerateRunner` zeigt den heutigen Sollpfad fuer 0.7.0:
  - Schema lesen
  - validieren
  - Dialekt und Spatial-Profil aufloesen
  - `DdlGenerator` aufrufen
  - Ergebnis in Datei/stdout/report weiterreichen
- Seit 0.6.0 gibt es in `hexagon:ports` bereits ein gutes Muster fuer typed
  Envelopes neben dem eigentlichen Payload:
  - `SchemaReadResult`
  - `SchemaReadNote`
  - `SchemaReadReportInput`
- Dieses Muster trennt bewusst:
  - fachliches Ergebnis
  - strukturierte Diagnostik
  - I/O- bzw. Herkunftskontext
- Ein entsprechender Exportvertrag existiert fuer 0.7.0 heute noch nicht:
  - kein `MigrationTool`
  - keine `MigrationIdentity`
  - kein `MigrationBundle`
  - kein `MigrationArtifact`
  - kein `ToolExportResult`
  - kein `ToolMigrationExporter`
- Es existiert auch noch keine eigene Export-/Integrations-Namespace-Struktur in
  `hexagon:ports`; der bestehende Port-Bestand ist auf `driver`, `format` und
  `streaming` zugeschnitten.
- `DatabaseDriverRegistry` ist heute ausschliesslich Lookup fuer
  DB-dialektbezogene Ports und darf nicht still zum Tool-Exporter-Registry
  umgedeutet werden.
- Der DDL-Header enthaelt laut `docs/ddl-generation-rules.md` weiterhin einen
  Laufzeit-Timestamp; Phase A hat bereits festgezogen, dass 0.7.0 fuer
  Tool-Artefakte byte-deterministisch bleiben muss.

Konsequenz fuer Phase B:

- Der groesste 0.7.0-Gap ist aktuell nicht die SQL-Erzeugung, sondern der
  fehlende Zwischenvertrag zwischen `DdlResult` und spaeteren Tool-Artefakten.
- Ohne Phase B droht 0.7.0 entweder:
  - pro Tool eigene Ad-hoc-Identitaetslogik zu bauen,
  - Kollisionen erst beim Schreiben zu entdecken,
  - oder `DdlResult` roh in CLI-/Adapter-Code zu verteilen.

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Einfuehrung eines tool-neutralen Exportvertrags in `hexagon:ports`
- Definition der Kern-Typen fuer:
  - Tool
  - Identitaet
  - Bundle
  - Artefakt
  - Export-Ergebnis
- Festlegung eines expliziten Render-Ports fuer spaetere Tool-Adapter
- Definition der Regeln fuer:
  - Version-Aufloesung aus `--version` bzw. `schema.version`
  - tool-spezifische Versionsvalidierung
  - Slug-Normalisierung aus `schema.name`
  - Kollisionserkennung gegen vorhandene Zieldateien
  - relative Artefaktpfade innerhalb des Output-Verzeichnisses
- Zielverortung der rein adapterfreien Identitaets- und Kollisionslogik in
  `hexagon:application`
- Port-/Application-Tests fuer den neuen Vertrag

### 3.2 Bewusst nicht Teil von Phase B

- konkrete Flyway-, Liquibase-, Django- oder Knex-Renderer
- neues Gradle-Modul `adapters/driven/integrations`
- CLI-Wiring oder `ExportCommand`
- Dateischreiblogik, stdout-/stderr-Rendering oder Report-Serialisierung
- Tool-Runtime-Tests
- Aenderungen an `DdlGenerator`-SQL-Regeln selbst
- Diff-basiertes `schema migrate` oder spaeteres allgemeines Migrationsmodell

---

## 4. Leitentscheidungen fuer Phase B

### 4.1 Das Bundle bleibt generatornah und kapselt `DdlResult`, statt rohe SQL-Strings zu kanonisieren

Phase B fuehrt keinen zweiten SQL-Zwischenvertrag ein.

Verbindliche Folge:

- `MigrationBundle` kapselt den bestehenden DDL-Pfad mit mindestens:
  - `identity`
  - `schema`
  - `options`
  - `upResult: DdlResult`
  - einem expliziten Rollback-Vertrag statt blossem nullable
    `downResult: DdlResult?`
- Der Bundlevertrag unterscheidet typed zwischen:
  - Rollback nicht angefordert
  - Rollback angefordert und mit `DdlResult` vorhanden
- Ein angeforderter Rollback ohne erzeugtes Down-Result ist kein valides
  `MigrationBundle`, sondern eine fruehe Vertragsverletzung vor oder beim
  Bundle-Aufbau.
- `MigrationBundle` arbeitet damit bewusst auf strukturiertem
  Generator-Output und nicht auf bereits final gerenderten Tool-Dateien.
- Tool-Adapter rendern spaeter aus dem Bundle heraus ihre Artefakte.
- Die SQL-Basis bleibt dadurch weiterhin eindeutig an `DdlGenerator` gebunden.

Nicht akzeptabel ist:

- `MigrationBundle` nur als Sammlung freier `String`-Felder zu definieren
- `DdlResult` in Tool-Adapter zu kopieren und dort erneut fachlich umzudeuten
- einen zweiten Generatorvertrag neben `DdlResult` einzufuehren

### 4.2 Der Exportvertrag bekommt einen eigenen Namespace und bleibt aus `driver` und `format` herausgeloest

Tool-Integrationen sind weder DB-Driver noch Dateicodecs.

Verbindliche Folge:

- Die neuen Bundle-/Identitaets-Typen liegen in einem eigenen Namespace unter
  `hexagon:ports`, z. B. `dev.dmigrate.migration`.
- Sie werden nicht in `dev.dmigrate.driver` zwischen JDBC-Ports gemischt.
- Sie werden auch nicht in `dev.dmigrate.format` einsortiert, weil sie nicht
  bloss Serialisierung beschreiben.
- `DatabaseDriverRegistry` bleibt ausschliesslich Registry fuer
  datenbankdialektbezogene Driver-Ports.
- Ein spaeterer Tool-Exporter-Lookup ist ein eigener Vertrag und kein Anbau an
  die Driver-Registry.

### 4.3 Identitaet ist ein eigener typed Vertrag und nicht nur `version + schema.name`

Phase B zieht die aus Phase A abgeleitete Export-Identitaet in einen expliziten
Typ.

`MigrationIdentity` enthaelt mindestens:

- `tool`
- `dialect`
- `version`
- `versionSource`
- `slug`

Verbindliche Folge:

- Die Herkunft der Version bleibt sichtbar:
  - `CLI`
  - `SCHEMA`
- Flyway/Liquibase koennen `schema.version` nur nutzen, wenn der Wert nach den
  dokumentierten Regeln tool-tauglich normalisierbar ist.
- Django/Knex akzeptieren fuer 0.7.0 nur eine explizite CLI-Version.
- `schema.name` wird ueber eine stabile Slug-Regel in einen
  dateinamensfaehigen Bestandteil ueberfuehrt.
- Es gibt keinen impliziten Timestamp-Fallback fuer fehlende oder ungueltige
  Versionen.

### 4.4 Die Identitaetsauflosung bleibt adapterfrei und sitzt in `hexagon:application`

Die spaetere CLI muss dieselbe Version-/Slug-Regel verwenden wie Tests und
moegliche andere Driving Adapter.

Verbindliche Folge:

- Phase B fuehrt helper bzw. Services in `hexagon:application` ein, z. B. fuer:
  - `MigrationIdentityResolver`
  - `MigrationSlugNormalizer`
  - `MigrationVersionValidator`
- Diese Logik ist:
  - I/O-frei
  - tool-spezifisch testbar
  - unabhaengig von konkreten Tool-Adaptern
- Die Tool-Adapter bekommen spaeter bereits eine fertig aufgeloeste
  `MigrationIdentity` und muessen keine eigenen Default-Regeln mehr erfinden.

### 4.5 Artefakte bleiben relative Write-Intents und duerfen das Output-Wurzelverzeichnis nicht verlassen

Phase B fixiert den Sicherheits- und Determinismusvertrag fuer spaetere
Datei-Artefakte.

`MigrationArtifact` enthaelt mindestens:

- `relativePath` als typed, normalisierter relativer Pfadvertrag
- `content`
- `kind`

Verbindliche Folge:

- `relativePath` ist kein roher `String`, sondern ein expliziter
  Relativpfad-Typ auf Basis von `Path`, z. B. `ArtifactRelativePath`.
- Der Pfadvertrag arbeitet auf normalisierten relativen Pfadsegmenten und
  nicht auf plattformabhaengigen Stringvergleichen.
- Absolute Pfade sind im Artefaktvertrag nicht zulaessig.
- Parent-Escapes wie `../` sind nicht zulaessig.
- Separatorvarianten wie `a/b.sql` und `a\\b.sql` werden vor Vergleich und
  Kollisionspruefung in dieselbe kanonische Relativpfadform ueberfuehrt.
- Die spaetere Application kann dadurch vor jedem Write pruefen:
  - welche Dateien erzeugt werden sollen
  - ob eine Kollision mit bestehenden Dateien vorliegt
  - ob zwei Artefakte desselben Laufs denselben Pfad beanspruchen

### 4.6 Kollisionserkennung ist Teil des Application-Vertrags und passiert vor dem ersten Write

Phase B darf Kollisionen nicht erst dem spaeteren Filesystem-Write ueberlassen.

Verbindliche Folge:

- Die Application bekommt einen adapterfreien Kollisionstest auf Basis von:
  - Output-Wurzelpfad
  - geplanter Artefaktliste
- Kollisionen werden auf der Ebene geplanter relativer Pfade erkannt.
- Phase B fuehrt dafuer einen kleinen Vertrag ein, z. B.:
  - `ArtifactCollision`
  - `MigrationWritePlan`
  - oder einen aequivalenten pure-helper
- Die spaetere CLI mappt solche Kollisionen auf den in Phase A festgezogenen
  Lokalfailure-Pfad.

### 4.7 Generator-Diagnostik bleibt kanonisch; Export-spezifische Hinweise werden additiv und getrennt gehalten

0.7.0 darf Notes und `skippedObjects` nicht verlieren, aber auch nicht
generatorfremde Hinweise in `TransformationNote` hineinzwengen.

Verbindliche Folge:

- `DdlResult.notes` und `DdlResult.skippedObjects` bleiben der kanonische
  Generator-Diagnostikpfad.
- `MigrationBundle` verliert diese Informationen nicht.
- Tool-spezifische Hinweise, die nicht aus dem Generator kommen
  (z. B. Wrapper-/Edition-/Projektintegrationshinweise), bekommen bei Bedarf
  einen eigenen kleinen Export-Note-Typ, z. B. `ToolExportNote`, statt
  `TransformationNote` zu missbrauchen.
- `ToolExportResult` traegt diese export-spezifischen Hinweise explizit in
  einem eigenen Feld, statt sie still zu verlieren oder in Generator-Notes
  umzudeuten.
- Spaetere Report- oder CLI-Schichten duerfen diese Pfade zusammen sichtbar
  machen, der Bundlevertrag selbst haelt sie aber semantisch sauber getrennt.

### 4.8 Source-, Output- und Report-Kontext gehoeren nicht in das Bundle

Die Exportidentitaet ist nicht dasselbe wie der spaetere Laufkontext.

Verbindliche Folge:

- `MigrationBundle` enthaelt keine:
  - Source-Dateipfade
  - Output-Verzeichnisse
  - Report-Pfade
  - CLI-Flags fuer Ausgabeformate
- Solcher Kontext wird spaeter im Application-Runner bzw. in einem eigenen
  Report-/Write-Envelope gehalten.
- Das folgt derselben Trennung wie bei `SchemaReadResult` und
  `SchemaReadReportInput`.

### 4.9 Der Port fuer Tool-Adapter bleibt render-orientiert und side-effect-frei

Phase B beschreibt spaetere Tool-Adapter als producer von Artefakten, nicht als
freie Host-Dateischreiber.

Verbindliche Folge:

- `ToolMigrationExporter` nimmt ein `MigrationBundle` entgegen.
- Der Port liefert ein typed Ergebnis mit Artefaktliste zurueck, z. B.
  `ToolExportResult`.
- Filesystem-Schreiben bleibt spaeter in Application/CLI bzw. in einer kleinen
  expliziten Write-Schicht.
- Dadurch bleiben Adapter-Tests schnell, deterministisch und ohne echte
  Dateisystem-Seiteneffekte moeglich.

---

## 5. Arbeitspakete fuer Phase B

### B.1 Port-Typen fuer Bundle und Identitaet einfuehren

Mindestens noetig:

- `MigrationTool`
- `MigrationVersionSource`
- `MigrationIdentity`
- `MigrationRollback`
- `ArtifactRelativePath`
- `MigrationBundle`
- `MigrationArtifact`
- `ToolExportNote`
- `ToolExportResult`
- `ToolMigrationExporter`

### B.2 Identitaetsauflosung und Validierung festziehen

Mindestens noetig:

- Resolver fuer `--version` versus `schema.version`
- tool-spezifische Validierung fuer:
  - Flyway
  - Liquibase
  - Django
  - Knex
- stabile Slug-Normalisierung aus `schema.name`
- expliziter Fehlervertrag fuer:
  - fehlende Pflichtversion
  - ungueltige Fallback-Version
  - leeren oder unbrauchbaren Slug

### B.3 Artefaktlayout und Kollisionsvertrag definieren

Mindestens noetig:

- relative Artefaktpfade
- Verbot von absoluten Pfaden und `..`-Escapes
- in-run-Kollisionen zwischen Artefakten
- Kollisionen gegen bereits vorhandene Dateien im Output-Verzeichnis

### B.4 Diagnose- und Ergebnisvertrag schaerfen

Mindestens noetig:

- generatorseitige `notes` / `skippedObjects` bleiben erhalten
- expliziter Export-Note-Pfad ueber eigenen Kern-Typ fuer
  nicht-generatorische Hinweise
- keine Vermischung von Bundle und Laufkontext

### B.5 Adapterfreie Tests fuer Ports und Application-Helper aufbauen

Mindestens noetig:

- Typ-/Smoke-Tests fuer den Bundlevertrag
- Tests fuer Identitaetsauflosung
- Tests fuer Versionsvalidierung
- Tests fuer Slug-Normalisierung
- Tests fuer Artefaktkollisionen
- Tests fuer relative Pfadregeln

Ziel von Phase B:

- Vor dem ersten Tool-Adapter ist klar, in welcher typed Form 0.7.0 DDL,
  Identitaet, Diagnostik und Artefaktlayout durch das Hexagon transportiert.

---

## 6. Technische Zielstruktur fuer Phase B

Phase B fuehrt noch keine Integrationsadapter ein, legt aber deren
Vertragsoberflaeche im Hexagon fest.

Bevorzugte Struktur:

- `hexagon/ports`
  - neuer Namespace fuer Export-/Migrationsvertrag, z. B.
    `hexagon/ports/src/main/kotlin/dev/dmigrate/migration/`
  - Typen:
    - `MigrationTool`
    - `MigrationVersionSource`
    - `MigrationIdentity`
    - `MigrationRollback`
    - `ArtifactRelativePath`
    - `MigrationBundle`
    - `MigrationArtifact`
    - `ToolExportNote`
    - `ToolExportResult`
    - `ToolMigrationExporter`
- `hexagon/application`
  - helper bzw. Services fuer:
    - Versionsauflosung
    - tool-spezifische Versionsvalidierung
    - Slug-Normalisierung
    - Kollisionspruefung
    - eventuelle Ableitung eines Write-Plans

Illustrativer Zielzuschnitt:

```kotlin
import java.nio.file.Path

enum class MigrationTool { FLYWAY, LIQUIBASE, DJANGO, KNEX }

enum class MigrationVersionSource { CLI, SCHEMA }

data class MigrationIdentity(
    val tool: MigrationTool,
    val dialect: DatabaseDialect,
    val version: String,
    val versionSource: MigrationVersionSource,
    val slug: String,
)

sealed interface MigrationRollback {
    data object NotRequested : MigrationRollback
    data class Requested(val downResult: DdlResult) : MigrationRollback
}

@JvmInline
value class ArtifactRelativePath(val path: Path)

data class MigrationBundle(
    val identity: MigrationIdentity,
    val schema: SchemaDefinition,
    val options: DdlGenerationOptions,
    val upResult: DdlResult,
    val rollback: MigrationRollback,
)

data class ToolExportNote(
    val code: String,
    val message: String,
)

data class MigrationArtifact(
    val relativePath: ArtifactRelativePath,
    val kind: String,
    val content: String,
)

data class ToolExportResult(
    val artifacts: List<MigrationArtifact>,
    val exportNotes: List<ToolExportNote> = emptyList(),
)

interface ToolMigrationExporter {
    val tool: MigrationTool
    fun render(bundle: MigrationBundle): ToolExportResult
}
```

Nicht Teil der Zielstruktur von Phase B:

- `ExportCommand`
- `ToolExportRunner`
- `adapters/driven/integrations`
- Dateisystem-Writer im Tool-Adapter
- Report-Writer fuer Tool-Export

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `docs/implementation-plan-0.7.0.md`
- `docs/ImpPlan-0.7.0-A.md`
- neue Port-Typen unter:
  - `hexagon/ports/src/main/kotlin/...`
- neue adapterfreie Helper/Services unter:
  - `hexagon/application/src/main/kotlin/...`
- Tests unter:
  - `hexagon/ports/src/test/kotlin/...`
  - `hexagon/application/src/test/kotlin/...`

Indirekt als Ist-Referenz relevant:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriverRegistry.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/SchemaReadReportInput.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SchemaDefinition.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`

Noch nicht Teil von Phase B, aber als Folgeartefakte vorzubereiten:

- `adapters/driven/integrations`
- `ToolExportRunner`
- `ExportCommand`
- Report- und Write-Pfad fuer Export-Artefakte

---

## 8. Akzeptanzkriterien

- [ ] `hexagon:ports` enthaelt einen expliziten tool-neutralen Exportvertrag
      fuer 0.7.0 statt loser Tool-spezifischer Hilfstypen.
- [ ] `MigrationIdentity` macht Tool, Dialekt, Version, Versionsquelle und Slug
      explizit sichtbar.
- [ ] Flyway/Liquibase koennen `schema.version` nur ueber den dokumentierten
      Fallback nutzen; Django/Knex verlangen weiterhin eine explizite
      CLI-Version.
- [ ] Es gibt keinen impliziten Timestamp- oder sonstigen Default fuer
      fehlende Versionen.
- [ ] `MigrationBundle` kapselt `DdlResult` fuer Up und einen expliziten
      Rollback-State, statt den Rollback-Vertrag auf `null` zu reduzieren.
- [ ] Angeforderter Rollback ohne Down-`DdlResult` ist kein valider
      Bundlezustand.
- [ ] Generator-Notes und `skippedObjects` bleiben im Bundlevertrag erhalten.
- [ ] Export-spezifische Hinweise werden semantisch getrennt von
      `TransformationNote` gehalten und explizit in `ToolExportResult`
      modelliert.
- [ ] `MigrationArtifact` beschreibt kanonische relative Pfade unterhalb des
      spaeteren Output-Verzeichnisses nicht als rohen `String`, sondern als
      typed Relativpfadvertrag.
- [ ] Kollisionen zwischen geplanten Artefakten bzw. gegen bestehende
      Zieldateien sind vor dem ersten Write pruefbar.
- [ ] `DatabaseDriverRegistry` bleibt unberuehrt und wird nicht fuer
      Tool-Exporter wiederverwendet.
- [ ] Die Identitaets-, Slug- und Kollisionslogik ist adapterfrei in
      `hexagon:application` testbar.

---

## 9. Verifikation

Mindestumfang fuer die Phase-B-Umsetzung:

1. Port- und Application-Typen pruefen:

```bash
rg -n "MigrationTool|MigrationVersionSource|MigrationIdentity|MigrationRollback|ArtifactRelativePath|MigrationBundle|MigrationArtifact|ToolExportNote|ToolExportResult|ToolMigrationExporter" \
  hexagon/ports/src/main/kotlin hexagon/application/src/main/kotlin
```

2. Sicherstellen, dass die neue Typfamilie nicht in `dev.dmigrate.driver`
landet:

```bash
find hexagon/ports/src/main/kotlin/dev/dmigrate -maxdepth 3 -type f | sort
```

3. Identitaets- und Kollisions-Helper pruefen:

```bash
rg -n "IdentityResolver|VersionValidator|SlugNormalizer|Collision" \
  hexagon/application/src/main/kotlin hexagon/application/src/test/kotlin
```

4. Modul-Testlauf fuer Ports und Application:

```bash
./gradlew :hexagon:ports:test :hexagon:application:test
```

Dabei explizit pruefen:

- dieselbe Eingabe erzeugt dieselbe `MigrationIdentity`
- Django/Knex scheitern ohne explizites `--version`
- Flyway/Liquibase akzeptieren nur tool-taugliche `schema.version`-Fallbacks
- angeforderter Rollback ohne Down-`DdlResult` ist nicht als valider
  Bundlezustand darstellbar
- Artefakte koennen keine absoluten oder ausbrechenden Pfade tragen
- semantisch gleiche Pfade kollidieren ueber dieselbe kanonische
  Relativpfadform statt ueber rohe Stringvergleiche
- Kollisionen werden ohne echten Tool-Adapter bzw. ohne Dateischreiben erkannt
- `MigrationBundle` behaelt `DdlResult` inklusive Notes und `skippedObjects`
- export-spezifische Hinweise bleiben getrennt von Generator-Diagnostik

---

## 10. Risiken und offene Punkte

### R1 - Ein zu grober Bundlevertrag macht Tool-Adapter spaeter zwangslaufig untestbar

Wenn Phase B nur freie Strings oder unstrukturierte Maps definiert, muessen
spaetere Tool-Adapter Identitaet, Kollisionen und Diagnostik erneut selbst
interpretieren.

### R2 - Ein zweites Note-Modell kann generatorische und tool-spezifische Hinweise vermischen

Wenn `TransformationNote` und spaetere Tool-Hinweise unscharf zusammengelegt
werden, verlieren Reports und Tests die klare Herkunft ihrer Diagnostik.

### R3 - Zu spaete Kollisionserkennung fuehrt zu halbfertigen Output-Verzeichnissen

Wenn Kollisionen erst beim eigentlichen Dateischreiben erkannt werden, sind
partielle Artefaktausgaben und schwer reproduzierbare Fehlerpfade vorprogrammiert.

### R4 - Ein falscher Namespace oder Registry-Zuschnitt koppelt Tool-Export an Driver-Semantik

Wenn der neue Vertrag unter `driver` oder sogar in `DatabaseDriverRegistry`
landet, vermischt 0.7.0 zwei fachlich verschiedene Achsen:
DB-Dialektzugriff und Tool-Artefakt-Export.
