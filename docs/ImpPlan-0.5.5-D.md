# Implementierungsplan: Phase D - Generator-Optionen und CLI-Pfad

> **Milestone**: 0.5.5 - Erweitertes Typsystem
> **Phase**: D (Generator-Optionen und CLI-Pfad)
> **Status**: Done (2026-04-13)
> **Referenz**: `docs/implementation-plan-0.5.5.md` Abschnitt 2,
> Abschnitt 4.5 bis 4.8, Abschnitt 5 Phase D, Abschnitt 6.1,
> Abschnitt 6.4, Abschnitt 6.10, Abschnitt 7, Abschnitt 8, Abschnitt 10;
> `docs/ImpPlan-0.5.5-A.md`; `docs/ImpPlan-0.5.5-B.md`;
> `docs/ImpPlan-0.5.5-C.md`; `docs/change-request-spatial-types.md`;
> `docs/cli-spec.md`; `docs/ddl-generation-rules.md`

---

## 1. Ziel

Phase D fuehrt den fehlenden Optionspfad zwischen `schema generate` und den
DDL-Generatoren ein. Ergebnis der Phase ist, dass das CLI ein Spatial-Profil
frueh und eindeutig aufloesen kann und dieselbe aufgeloeste Option sowohl an
den Up- als auch an den Rollback-Generator uebergibt.

Die Phase liegt bewusst zwischen den bereits vorbereiteten Modell-/Codec-
Schritten aus Phase B/C und der eigentlichen Dialektimplementierung aus
Phase E. Sie liefert noch keine Spatial-DDL-Regeln pro Dialekt, macht diese
aber technisch erst moeglich.

Nach Phase D soll klar und testbar gelten:

- `schema generate` akzeptiert optional `--spatial-profile`
- Profil-Defaults werden zentral aus dem Zieldialekt abgeleitet
- ungueltige Profil-/Dialekt-Kombinationen scheitern frueh mit Exit-Code `2`
- `DdlGenerator.generate(...)` und `generateRollback(...)` sehen denselben
  aufgeloesten Optionssatz
- generatorseitige Spatial-Ergebnisse wie `E052` und `W120` bleiben ueber den
  bestehenden `DdlResult`-Pfad in stderr, JSON-Ausgabe und Sidecar-Report
  sichtbar
- es entsteht kein zweiter Report- oder Nebenkanal fuer Spatial-Hinweise

---

## 2. Ausgangslage

Aktueller Stand in `hexagon:ports`, `hexagon:application` und
`adapters:driving:cli`:

- `DdlGenerator` kennt heute nur:
  - `generate(schema: SchemaDefinition): DdlResult`
  - `generateRollback(schema: SchemaDefinition): DdlResult`
- Es existiert noch kein Generator-Optionsobjekt und kein kanonischer
  `SpatialProfile`-Typ.
- `SchemaGenerateRequest` enthaelt aktuell:
  - `source`
  - `target`
  - `output`
  - `report`
  - `generateRollback`
  - `outputFormat`
  - `verbose`
  - `quiet`
- `SchemaGenerateCommand` bietet derzeit:
  - `--source`
  - `--target`
  - `--output`
  - `--report`
  - `--generate-rollback`
- `SchemaGenerateRunner.execute(...)` arbeitet heute in dieser Reihenfolge:
  1. Zieldialekt aus `request.target` parsen
  2. Schema lesen
  3. Schema validieren
  4. `generator.generate(schema)` aufrufen
  5. Notes und Skips ausgeben
  6. stdout-/Datei-/JSON-Pfad routen
- Der Rollback-Pfad ruft spaeter separat `generator.generateRollback(schema)`
  auf und sieht ebenfalls noch keine Optionen.
- `CliContext` transportiert bereits globale CLI-Flags wie `outputFormat`,
  `verbose` und `quiet`; fuer Spatial existiert daneben noch kein eigener
  Anwendungs-Kontext.
- Die Root-CLI erlaubt `--output-format plain|json|yaml`, aber
  `SchemaGenerateRunner` behandelt aktuell nur `json` explizit. `yaml` faellt
  derzeit auf denselben Plain-/Dateipfad wie `plain` zurueck.
- `SchemaGenerateHelpers.formatJsonOutput(...)` und
  `TransformationReportWriter` serialisieren heute `DdlResult`.
- `DdlResult.notes` wird aus Statement-Notes abgeleitet.
- `SkippedObject` traegt aktuell nur `type`, `name` und `reason`, aber keinen
  expliziten Fehlercode und keinen Hint.

Konsequenz fuer Phase D:

- Ohne neuen Optionsvertrag kann Phase E das Spatial-Profil nicht sauber in
  die Generatoren hineinreichen.
- Ohne zentrale Profilmatrix drohen doppelte Default- und Kombinationsregeln
  in CLI, Runner und spaeteren Generator-Tests.
- Ohne schaerferen `DdlResult`-/Skip-Vertrag bleibt `E052` fuer komplett
  uebersprungene Tabellen oder Objekte im Ausgabe- und Reportpfad nur
  indirekt erkennbar.
- Der bestehende stdout-/Datei-/JSON-/Report-Pfad ist bereits breit getestet
  und darf durch Phase D nicht neu aufgespalten werden.

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- kleines, typisiertes Generator-Optionsobjekt im Port-Vertrag
- kanonischer Typ fuer Spatial-Profile
- zentrale Default- und Zulaessigkeitsmatrix pro Zieldialekt
- Erweiterung von `SchemaGenerateRequest` um das rohe CLI-Feld
  `spatialProfile`
- Erweiterung von `SchemaGenerateCommand` um `--spatial-profile`
- fruehe Profilvalidierung im `schema generate`-Pfad vor Schema-Read und
  vor Generatoraufruf
- identischer Optionspfad fuer `generate(...)` und `generateRollback(...)`
- expliziter Output-/Report-Vertrag fuer generatorseitige Spatial-Codes
  `E052` und `W120`
- Tests fuer Defaults, Fehlfaelle, Rollback-Symmetrie und Output-Transport

### 3.2 Bewusst nicht Teil von Phase D

- eigentliche Spatial-DDL pro Dialekt
- PostGIS-/MySQL-/SpatiaLite-Mappingregeln
- `SchemaValidator`-Regeln aus Phase B
- neue Schema-Codecs oder `SchemaCodec.write(...)`
- generische Ueberarbeitung von `--output-format yaml` fuer
  `schema generate`
- neuer Report-Typ oder separater Spatial-Ausgabekanal
- Feinheiten der Dialektgeneratoren, die erst in Phase E/F konkret werden

---

## 4. Architekturentscheidungen

### 4.1 Generator-Optionen werden typisiert und im Port-Vertrag verankert

Phase D fuehrt kein loses Map- oder String-Buendel ein. Der Generatorvertrag
bekommt ein kleines, explizites Optionsobjekt, das in `hexagon:ports` neben
`DdlGenerator` lebt.

Verbindliche Zielrichtung:

- rohe CLI-Texte bleiben an der Eingangsgrenze
- Generatoren sehen einen aufgeloesten, typisierten Wert
- spaetere programmgesteuerte Aufrufer koennen denselben Vertrag benutzen
- der Vertrag bleibt klein und auf 0.5.5 fokussiert

### 4.2 Spatial-Profilmatrix wird einmal zentral definiert

Die Dialektabhaengigkeit des Spatial-Profils darf nicht mehrfach kodiert
werden.

Phase D braucht deshalb genau eine kanonische Matrix fuer:

- erlaubte Profile pro Dialekt
- Default-Profil pro Dialekt
- textuelle CLI-Darstellung der Profile

Fuer 0.5.5 gilt als verbindliche Matrix:

- PostgreSQL:
  - erlaubt `postgis`
  - erlaubt `none`
  - Default `postgis`
- MySQL:
  - erlaubt `native`
  - erlaubt `none`
  - Default `native`
- SQLite:
  - erlaubt `spatialite`
  - erlaubt `none`
  - Default `none`

### 4.3 Profilvalidierung bleibt im Generate-Pfad, nicht in Clikt und nicht im Validator

Die semantische Pruefung von `--spatial-profile` gehoert nicht in
`SchemaValidator`, weil sie nicht das Schema selbst betrifft, sondern die
Generierbarkeit fuer einen Zieldialekt.

Sie soll aber auch nicht als verteilte Clikt-Speziallogik in
`SchemaGenerateCommand` leben, weil sonst Exit-Codes und Kombinationsregeln
am Command kleben.

Verbindlicher Ablauf:

1. `target` in `DatabaseDialect` aufloesen
2. `spatialProfile` gegen die zentrale Matrix aufloesen oder defaulten
3. bei Fehlern mit Exit-Code `2` abbrechen
4. erst danach Schema lesen und validieren

Damit bleibt das Verhalten konsistent:

- fachlich ungueltiger Dialekt oder Profil -> Exit `2`
- kaputte Schema-Datei -> Exit `7`
- fachlich ungueltiges Schema -> Exit `3`

### 4.4 Up- und Rollback-Pfad muessen denselben Optionssatz sehen

Phase D darf nicht nur den Hauptgenerator erweitern. Sobald Rollback-DDL
aktiviert ist, muss derselbe aufgeloeste Optionssatz erneut verwendet werden.

Nicht akzeptabel waeren:

- zweites Defaulting nur fuer `generateRollback(...)`
- Rollback ohne Profil
- unterschiedliche Profilaufloesung fuer denselben CLI-Aufruf

### 4.5 `DdlResult` bleibt der einzige Generator-Ausgabekanal

`E052` und `W120` entstehen laut Spezifikation im Generator-/Report-Pfad,
nicht im Validator. Phase D fuehrt dafuer keinen neuen Rueckgabekanal ein.

Stattdessen wird der bestehende `DdlResult`-Vertrag so geschaerft, dass
Spatial-Faelle strukturiert transportierbar bleiben:

- `W120` bleibt eine generatorseitige `TransformationNote`
- blockierende `E052`-Faelle bleiben Teil des bestehenden
  Skip-/Action-required-Vertrags
- ein uebersprungenes Objekt muss im Ergebnis strukturiert erkennen lassen,
  welcher Code und welcher Hinweis dazu gehoeren
- ein blockierender `E052`-Fall muss fuer CLI, JSON und Report weiterhin auch
  als `action_required` wahrnehmbar bleiben und darf nicht in einem blossen
  Skip-Freitext "verschwinden"

Minimalanforderung an die Zielstruktur:

- ein Skip-Eintrag transportiert nicht nur Freitext, sondern auch mindestens
  den zugehoerigen Code
- optionale Hinweise koennen ebenfalls erhalten bleiben
- die Semantik fuer `action_required` bleibt konsistent:
  - entweder ein `E052`-Skip traegt zusaetzlich eine passende
    `ACTION_REQUIRED`-Note
  - oder JSON-/Report-/stderr-Zaehler und Ausgabe werden zentral auch aus
    strukturierten `E052`-Skips abgeleitet
- JSON-Ausgabe und Sidecar-Report muessen `E052` aus demselben `DdlResult`
  rekonstruieren koennen, ohne ein separates Spatial-Feld einzufuehren

### 4.6 Der bestehende Output-Rahmen wird konserviert

Phase D erweitert den Optionspfad, nicht das Ausgabeprodukt.

Das bedeutet:

- bestehender stdout-Pfad bleibt erhalten
- bestehender Dateipfad inklusive Sidecar-Report bleibt erhalten
- `--output-format json` bleibt ein Sonderpfad ueber
  `SchemaGenerateHelpers.formatJsonOutput(...)`
- die heute bereits vorhandene, aber fuer `schema generate` nicht weiter
  ausdifferenzierte `yaml`-Option wird durch Phase D weder neu eingefuehrt
  noch stillschweigend umdefiniert

---

## 5. Arbeitspakete

### D.1 Typisierte Generator-Optionen einfuehren

Im Port-Vertrag ist ein kleines Optionsobjekt fuer DDL-Generierung
einzufuehren, z. B. `DdlGenerationOptions`.

Mindestens erforderlich:

- aufgeloestes `SpatialProfile` als typisierter Wert
- keine losen Freitextfelder im Generatorvertrag
- Ablage nahe `DdlGenerator` in `hexagon:ports`

### D.2 `DdlGenerator`-Vertrag auf gemeinsamen Optionspfad umstellen

`DdlGenerator` ist so zu erweitern, dass beide Erzeugungspfade denselben
Optionssatz sehen:

- `generate(schema, options)`
- `generateRollback(schema, options)`

Zusaetzlich ist der Ergebnisvertrag fuer uebersprungene Objekte zu schaerfen,
damit `E052` in JSON und Report nicht nur implizit im `reason`-Freitext
steckt.

Wichtig:

- bestehende nicht-spatiale Generatoren muessen gegen den neuen Vertrag
  weiter kompilieren
- bestehende Golden-Master- und Modelltests duerfen fachlich nicht kippen,
  nur weil der Aufruf jetzt einen expliziten Optionssatz traegt

### D.3 Zentrale Profilaufloesung und Defaults implementieren

Phase D braucht eine wiederverwendbare Profilmatrix, die sowohl Defaulting als
auch Zulaessigkeitspruefung kapselt.

Mindestens noetig:

- `defaultFor(dialect)`
- `allowedFor(dialect)`
- `resolve(dialect, rawProfile)`

Die Aufloesung muss die Masterplan-Defaults abbilden:

- PostgreSQL -> `postgis`
- MySQL -> `native`
- SQLite -> `none`

### D.4 `SchemaGenerateRequest` und Runner erweitern

`SchemaGenerateRequest` ist um ein optionales rohes Feld `spatialProfile` zu
erweitern.

`SchemaGenerateRunner` ist danach so anzupassen, dass er:

1. den Dialekt parst
2. das Profil zentral aufloest oder defaultet
3. bei ungueltigen Kombinationen frueh mit Exit `2` abbricht
4. das Schema erst danach liest
5. denselben aufgeloesten Optionssatz an `generate(...)` und bei Bedarf an
   `generateRollback(...)` uebergibt

Expliziter Fehlfall fuer die Tests:

- `--target mysql --spatial-profile spatialite` scheitert frueh und klar

### D.5 `SchemaGenerateCommand` um `--spatial-profile` erweitern

Das CLI bekommt ein neues optionales Flag `--spatial-profile`.

Verbindliche Anforderungen:

- die Option wird in `SchemaGenerateRequest` durchgereicht
- die Help-/Usage-Texte verwenden die kanonischen Profilnamen
- keine zweite, von der zentralen Matrix abweichende Defaultlogik im
  Clikt-Command

### D.6 Output-, JSON- und Reportvertrag fuer `E052` und `W120` schaerfen

Phase D muss explizit festziehen, wie generatorseitige Spatial-Hinweise den
CLI-Pfad durchlaufen.

Mindestens erforderlich:

- `W120` bleibt als Warnung im Note-Pfad sichtbar
- `E052` bleibt bei blockierten Objekten oder Tabellen strukturiert sichtbar
- `E052` bleibt auf `stderr` als `action_required` mit Code sichtbar und darf
  nicht nur als anonymer Skip erscheinen
- JSON und Sidecar-Report zaehlen blockierende `E052`-Faelle konsistent im
  Feld `action_required`, statt sie nur in `skipped_objects` zu listen
- `SchemaGenerateHelpers.formatJsonOutput(...)` verarbeitet den geschaerften
  `DdlResult`-Vertrag ohne zweiten Nebenkanal
- `TransformationReportWriter` verarbeitet denselben Vertrag konsistent
- Sidecar-Pfad und JSON-Huelle bleiben ansonsten unveraendert

### D.7 Tests fuer Optionspfad und Output-Symmetrie nachziehen

Bestehende Tests sind gezielt zu erweitern, nicht durch neue Ad-hoc-Pfade zu
ersetzen.

Mindestens noetig:

- `SchemaGenerateRunnerTest`
  - validiert fruehe Profilfehler
  - prueft Defaulting pro Dialekt
  - prueft identische Optionen fuer Up- und Rollback-Pfad
- `CliGenerateTest`
  - prueft Help/Parsing von `--spatial-profile`
  - prueft mindestens einen invaliden Profil-/Dialekt-Fall mit Exit `2`
- `SchemaGenerateHelpersTest`
  - prueft JSON-Sichtbarkeit von `W120` und `E052`
- `TransformationReportWriterTest`
  - prueft Sidecar-Report-Sichtbarkeit von `W120` und `E052`

---

## 6. Technische Zielstruktur

Eine moegliche Minimalform fuer den Zielvertrag ist:

```kotlin
data class DdlGenerationOptions(
    val spatialProfile: SpatialProfile,
)

enum class SpatialProfile(val cliName: String) {
    POSTGIS("postgis"),
    NATIVE("native"),
    SPATIALITE("spatialite"),
    NONE("none"),
}

interface DdlGenerator {
    val dialect: DatabaseDialect
    fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult
    fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult
}
```

Ergaenzend braucht der Generate-Pfad eine zentrale Aufloesung, z. B.:

```kotlin
object SpatialProfilePolicy {
    fun defaultFor(dialect: DatabaseDialect): SpatialProfile
    fun allowedFor(dialect: DatabaseDialect): Set<SpatialProfile>
    fun resolve(dialect: DatabaseDialect, raw: String?): SpatialProfile
}
```

Fuer den CLI-Pfad bleibt das Eingangssignal zunaechst roh:

```kotlin
data class SchemaGenerateRequest(
    val source: Path,
    val target: String,
    val spatialProfile: String?,
    ...
)
```

Wichtig ist die Semantik, nicht die exakte Kotlin-Form:

- rohe CLI-Eingaben werden genau einmal aufgeloest
- Generatoren sehen keine unverifizierten Profilstrings
- Up- und Rollback-Pfad teilen sich denselben Optionssatz
- `E052`/`W120` bleiben ueber `DdlResult` strukturiert serialisierbar
- ein blockierender `E052`-Fall bleibt zugleich als `action_required`
  semantisch sichtbar, nicht nur als Skip-Eintrag

Fuer den Skip-Vertrag ist mindestens eine kleine Erweiterung noetig, z. B.:

```kotlin
data class SkippedObject(
    val type: String,
    val name: String,
    val reason: String,
    val code: String? = null,
    val hint: String? = null,
)
```

Damit koennen JSON und Report generatorseitige `E052`-Faelle sichtbar halten,
ohne ein zweites Spatial-spezifisches Rueckgabeobjekt einzufuehren.

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- optional neuer Port-Helfer unter
  `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/`, z. B. fuer
  `DdlGenerationOptions` und `SpatialProfile`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- DDL-Generator-Implementierungen in:
  - `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/`
  - `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/`
  - `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliGenerateTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/TransformationReportWriterTest.kt`
- `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/DdlModelTest.kt`
- `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGeneratorTest.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
- `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGeneratorTest.kt`

Indirekt betroffen:

- bestehende Generator-Factories und Generator-Implementierungen, weil sich
  die Signatur von `DdlGenerator` aendert
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`,
  weil Generatoren kuenftig mit expliziten Optionen aufgerufen werden
- Phase E/F, die auf dem Options- und Outputvertrag aufbauen

---

## 8. Akzeptanzkriterien

- [ ] Ein kleines typisiertes Generator-Optionsobjekt existiert im
      Port-Vertrag.
- [ ] Der Generatorvertrag verwendet keine rohen Profil-Strings mehr.
- [ ] `DdlGenerator.generate(...)` und `generateRollback(...)` akzeptieren
      denselben Optionssatz.
- [ ] `SchemaGenerateRequest` traegt ein optionales Feld `spatialProfile`.
- [ ] `schema generate` akzeptiert `--spatial-profile`.
- [ ] Wird kein Profil uebergeben, greifen zentral die Defaults:
      PostgreSQL `postgis`, MySQL `native`, SQLite `none`.
- [ ] Ungueltige Profilnamen oder ungueltige Profil-/Dialekt-Kombinationen
      liefern Exit-Code `2`.
- [ ] Profilfehler werden vor Schema-Read, Validator und Generator abgefangen.
- [ ] `SchemaValidator` bekommt durch Phase D kein Profilwissen.
- [ ] Derselbe aufgeloeste Optionssatz wird fuer Haupt- und Rollback-Generierung
      verwendet.
- [ ] Der bestehende stdout-/Datei-/JSON-/Sidecar-Pfad bleibt funktional
      unveraendert; Phase D fuehrt keinen zweiten Spatial-Ausgabekanal ein.
- [ ] `DdlResult` transportiert `E052` und `W120` so strukturiert, dass JSON
      und Sidecar-Report sie ohne Spatial-Sondermodell ausgeben koennen.
- [ ] Uebersprungene Objekte transportieren `E052` nicht nur noch implizit im
      Freitext-`reason`.
- [ ] Blockierende `E052`-Faelle bleiben in CLI, JSON und Sidecar-Report auch
      als `action_required` semantisch sichtbar und tauchen nicht nur in
      `skipped_objects` auf.
- [ ] `stderr` zeigt blockierende `E052`-Faelle weiterhin mit Code und
      gegebenenfalls Hint als `action_required`.
- [ ] `--output-format json` zeigt generatorseitige `W120`-Warnungen und
      `E052`-Faelle weiterhin ueber den regulaeren Ergebnisvertrag, inklusive
      konsistenter `action_required`-Zaehler.
- [ ] Der Sidecar-Report zeigt generatorseitige `W120`-Warnungen und
      `E052`-Faelle weiterhin ueber denselben Ergebnisvertrag, inklusive
      konsistenter `action_required`-Zaehler.
- [ ] Bestehende nicht-spatiale Generator- und Golden-Master-Tests bleiben mit
      expliziten Optionen semantisch stabil.
- [ ] `SchemaGenerateRunnerTest`, `CliGenerateTest`,
      `SchemaGenerateHelpersTest` und `TransformationReportWriterTest` decken
      den neuen Vertrag ab.
- [ ] Die Signaturaenderung ist durch bestehende DDL-Modell- und
      DDL-Generator-Tests in `driver-common`, PostgreSQL, MySQL und SQLite
      mit abgesichert.

---

## 9. Verifikation

Phase D wird ueber gezielte Anwendungs- und Port-Tests verifiziert.

Mindestumfang:

1. Port-, Application- und CLI-Tests:

```bash
./gradlew :hexagon:ports:test :hexagon:application:test :adapters:driving:cli:test
```

2. DDL-Modell-, Generator- und Output-/Report-Tests:

```bash
./gradlew \
  :adapters:driven:driver-common:test \
  :adapters:driven:driver-postgresql:test \
  :adapters:driven:driver-mysql:test \
  :adapters:driven:driver-sqlite:test \
  :adapters:driven:formats:test
```

3. Inhaltliche Gegenpruefung der Tests auf folgende Faelle:

- PostgreSQL defaultet ohne Flag auf `postgis`
- MySQL defaultet ohne Flag auf `native`
- SQLite defaultet ohne Flag auf `none`
- `--target mysql --spatial-profile spatialite` scheitert mit Exit `2`
- Profilfehler treten vor Schema-Read und vor Validierung auf
- derselbe Optionssatz wird an `generate(...)` und `generateRollback(...)`
  uebergeben
- blockierende `E052`-Faelle erscheinen auf `stderr` weiterhin als
  `action_required` mit Code
- JSON-Ausgabe zeigt `W120` und blockierende `E052`-Faelle ueber den
  regulaeren `DdlResult`-Vertrag und mit konsistentem `action_required`-Zaehler
- Sidecar-Report zeigt `W120` und blockierende `E052`-Faelle ueber denselben
  Vertrag und mit konsistentem `action_required`-Zaehler
- `DdlModelTest` deckt den geschaerften `SkippedObject`-/`DdlResult`-Vertrag
  mit ab
- `PostgresDdlGeneratorTest`, `MysqlDdlGeneratorTest` und
  `SqliteDdlGeneratorTest` laufen nach der Signaturaenderung weiter gruen
- bestehende nicht-spatiale Golden-Master laufen mit expliziten Optionen
  unveraendert durch

4. Statische Code-Review der Zielstruktur:

- Profilmatrix ist nur einmal definiert
- Clikt enthaelt keine zweite fachliche Default- oder Kombinationslogik
- `SchemaValidator` bleibt frei von Dialekt-/Profilwissen
- `SkippedObject` oder ein aequivalenter bestehender DdlResult-Baustein
  transportiert `E052` strukturiert statt nur als Freitext
- `schema generate` fuehrt keine neue YAML-Sonderbehandlung ein, die nichts
  mit dem Spatial-Optionspfad zu tun hat

---

## 10. Risiken und offene Punkte

### R1 - Doppelte Profilmatrix fuehrt zu Drift

Wenn Defaults und erlaubte Profile sowohl in Clikt als auch im Runner oder in
den Generatoren separat kodiert werden, laufen Fehlermeldungen und Verhalten
schnell auseinander.

### R2 - Rollback kann unbemerkt einen anderen Optionspfad sehen

Wird der Optionssatz fuer `generateRollback(...)` separat gebaut oder gar
nicht uebergeben, sind Spatial-Up- und Down-Pfad zwangslaeufig inkonsistent.

### R3 - Freitext-Skips reichen fuer Spatial nicht mehr aus

Solange uebersprungene Objekte keinen strukturierten Code transportieren, ist
`E052` in JSON und Report nur indirekt oder gar nicht nachweisbar. Phase D
muss diese Luecke schliessen, bevor Phase E echte Spatial-Skips produziert.

### R4 - Root-Flag `--output-format yaml` bleibt ein separater Altpunkt

Die Root-CLI akzeptiert `yaml`, der Generate-Runner behandelt heute aber nur
`json` gesondert. Phase D sollte diesen Altpunkt dokumentieren, aber nicht
versehentlich zusammen mit dem Spatial-Optionspfad neu interpretieren.

### R5 - Signaturaenderung strahlt in bestehende Generator-Tests aus

Sobald `DdlGenerator` einen Optionssatz erwartet, muessen bestehende Tests und
Factories mitgezogen werden. Das ist erwartbar, darf aber nicht zu
fachlichen Aenderungen ausserhalb von Phase D fuehren.

---

## 11. Abschlussdefinition

Phase D ist abgeschlossen, wenn `schema generate` ein Spatial-Profil
frueh, zentral und testbar aufloest, denselben Optionssatz an Up- und
Rollback-Generierung weitergibt und generatorseitige Spatial-Hinweise ueber
den bestehenden `DdlResult`-/JSON-/Sidecar-Pfad sichtbar bleiben, ohne dass
bereits Dialekt-DDL aus Phase E vorweggenommen wird.
