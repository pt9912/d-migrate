# Implementierungsplan: Phase F - Testmatrix und Golden Masters

> **Milestone**: 0.5.5 - Erweitertes Typsystem
> **Phase**: F (Testmatrix und Golden Masters)
> **Status**: Draft (2026-04-13)
> **Referenz**: `docs/implementation-plan-0.5.5.md` Abschnitt 2,
> Abschnitt 4.3 bis 4.7, Abschnitt 5 Phase F, Abschnitt 6.2 bis 6.4,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/ImpPlan-0.5.5-B.md`; `docs/ImpPlan-0.5.5-C.md`;
> `docs/ImpPlan-0.5.5-D.md`; `docs/ImpPlan-0.5.5-E.md`;
> `docs/ddl-generation-rules.md`; `docs/cli-spec.md`

---

## 1. Ziel

Phase F haertet die 0.5.5-Umsetzung testseitig ab. Nach Abschluss der Phase ist
das erweiterte Typsystem nicht nur implementiert, sondern ueber Validator-,
Codec-, Driver-, Golden-Master- und CLI-Tests konsistent abgesichert.

Die Phase baut auf B bis E auf:

- Phase B liefert das Modell und die Validatorregeln `E120` und `E121`
- Phase C liefert Spatial-Fixtures und die Codec-Seite
- Phase D liefert Profilauflosung, JSON-/Report-Vertrag und CLI-Pfad
- Phase E liefert die eigentliche Dialekt-DDL inklusive `E052`, `W120` und
  Spatial-Rollback

Phase F fuehrt selbst keine neue Produktfunktion ein. Sie macht den bereits
vereinbarten 0.5.5-Vertrag testbar und regressionssicher.

Nach Phase F soll klar und belastbar gelten:

- Spatial-Validierung ist auf Core-Ebene abgesichert
- Spatial-Schemata und Negativfaelle sind ueber den Codec-Pfad reproduzierbar
- jeder Dialekt hat eigene Spatial-Generator-Tests fuer den vereinbarten
  SQL-/Note-/Rollback-Vertrag
- Golden Masters decken Spatial pro Dialekt mit explizitem Profil ab
- CLI, JSON-Output und Sidecar-Report transportieren `E052` und `W120`
  konsistent
- der 0.5.5-Umbau verschlechtert `uuid`, `json`, `binary` und `array`
  nicht still

---

## 2. Ausgangslage

Aktueller Stand in Core, Formats, Drivern und CLI:

- `SchemaValidatorTest` deckt Spatial bereits teilweise ab:
  - gueltige `geometry`-Faelle
  - `E120` fuer unbekannten `geometry_type`
  - `E121` fuer `srid <= 0`
  - Ausschluss von `array.element_type: geometry`
- `NeutralTypeTest` deckt `NeutralType.Geometry` und `GeometryType` bereits
  grundlegend ab.
- `YamlSchemaCodecTest` deckt Spatial bereits teilweise ab:
  - `schemas/spatial.yaml`
  - `E120_invalid_geometry_type.yaml`
  - `E121_srid_zero.yaml`
  - `E121_srid_negative.yaml`
  - JSON-Smoke ueber denselben Read-Pfad
- Die allgemeine Fixture-Story ist bereits geschaerft:
  - `all-types.yaml` deckt bewusst nur pre-0.5.5-Typen
  - Spatial liegt separat in `schemas/spatial.yaml`
- `CliGenerateTest` und `SchemaGenerateRunnerTest` decken die
  Profil-Option bereits teilweise ab:
  - gueltige Profil-/Dialekt-Kombination
  - ungueltige Profilkombination
  - Default-Profile je Dialekt
  - Weitergabe desselben Profils an Generate- und Rollback-Pfad
- `SchemaGenerateHelpersTest` und `TransformationReportWriterTest` decken
  bereits generisch ab, dass `SkippedObject` Code und Hint in JSON bzw.
  Report erscheinen koennen.
- `DdlModelTest` in `driver-common` deckt bereits generische
  `SkippedObject`-/`TransformationNote`-Renderlogik ab.
- `DdlGoldenMasterTest` laeuft derzeit aber nur ueber:
  - `minimal`
  - `e-commerce`
  - `all-types`
  - `full-featured`
- Es existieren noch keine Spatial-Golden-Master-Dateien.
- Der Golden-Master-Loop ruft die Generatoren heute direkt mit
  `generate(schema)` auf und setzt damit **keine** dialektspezifischen
  Spatial-Profile. Fuer ein Spatial-Fixture waere das nicht ausreichend, weil
  der direkte Generator-Default in `DdlGenerationOptions()` derzeit
  `SpatialProfile.NONE` ist.
- Die Profilmatrix ist fuer MySQL derzeit noch nicht voll synchron:
  - `SpatialProfilePolicy` erlaubt aktuell `native` und `none`
  - Phase E legt generatorseitig nur `native` fest
  - Phase F darf diese Inkonsistenz nicht still mitschleppen, sondern muss den
    finalen 0.5.5-Vertrag testseitig eindeutig machen
- In `PostgresDdlGeneratorTest`, `MysqlDdlGeneratorTest` und
  `SqliteDdlGeneratorTest` gibt es bislang keine eigentlichen
  Spatial-Generator-Tests.
- Insbesondere fehlen derzeit Tests fuer:
  - PostgreSQL-Tabellenblockierung bei Profil `none`
  - MySQL-`W120`
  - SQLite-`AddGeometryColumn(...)`
  - SQLite-`DiscardGeometryColumn(...)`
  - Spatial-Rollback allgemein
  - das Verbot partieller Tabellen-DDL ohne Geometry-Spalte
- Es gibt derzeit keine expliziten Spatial-CLI-Tests fuer:
  - `--output-format json`
  - Sidecar-Report
  - Spatial-Rollback-Datei

Konsequenz fuer Phase F:

- Die Grundlagen aus B bis D sind bereits testseitig teilweise sichtbar.
- Der grosse offene Block liegt nicht bei Parser- oder Optionsbasis, sondern
  bei dialektnaher SQL-Semantik, Golden Masters und End-to-End-Outputvertrag.
- Fuer MySQL gehoert dazu auch die klare Trennung zwischen nativer
  Generatorsemantik und dem separaten Gate-Verhalten fuer `--spatial-profile`.
- Ohne Phase F koennte 0.5.5 funktional "fertig" wirken, waehrend genau die
  risikoreichen Spatial-Pfade nur punktuell oder indirekt abgesichert sind.

---

## 3. Scope fuer Phase F

### 3.1 In Scope

- Ausbau der Core-Validator-Tests fuer den finalen 0.5.5-Vertrag
- Spatial-Codec- und Fixture-Absicherung in `formats:test`
- Spatial-spezifische Driver-Unit-Tests fuer PostgreSQL, MySQL und SQLite
- Golden Masters fuer `spatial.{postgresql,mysql,sqlite}.sql`
- CLI-Tests fuer Profil, JSON-Output, Sidecar-Report und Rollback-Dateien
- Regressionstests fuer `uuid`, `json`, `binary`, `array` in den betroffenen
  Generatorpfaden
- testspezifische Absicherung des gemeinsamen DDL-/Report-Vertrags in
  `driver-common` und `formats`

### 3.2 Bewusst nicht Teil von Phase F

- neue Produktfunktion ausserhalb des bereits vereinbarten 0.5.5-Scope
- Live-DB-Reverse-Pfade, `SchemaReader` oder Testcontainers-Read-Szenarien
- neue Spatial-Features wie `geography`, `z`, `m`
- breiter Integrationstest-Ausbau fuer 0.6.0-Read-Pfade
- generisches Performance-Tuning der gesamten Test-Suite ohne konkreten
  Spatial-Bezug

---

## 4. Architekturentscheidungen

### 4.1 Phase F erweitert bestehende Tests, statt dieselbe Semantik mehrfach neu zu erfinden

Die Phase baut bewusst auf vorhandenen Testschichten auf:

- Core-Tests pruefen Modell- und Validierungsregeln
- Formats-Tests pruefen Fixtures, Codec und Golden Masters
- Driver-Tests pruefen die dialektspezifische SQL-Semantik
- CLI-Tests pruefen Orchestrierung, Output und Exit-Codes

Verbindliche Folge:

- eine Aussage wird moeglichst dort getestet, wo sie fachlich sitzt
- CLI-Tests ersetzen keine Driver-SQL-Assertions
- Golden Masters ersetzen keine gezielten Negativ- und Note-Assertions

### 4.2 Spatial-Golden-Masters bleiben ein eigenes fokussiertes Fixture

Spatial wird fuer 0.5.5 nicht in `all-types.yaml` oder `full-featured.yaml`
hineingezwungen, nur damit ein einzelner Test "mehr abdeckt".

Begruendung:

- `all-types.yaml` ist bereits bewusst als pre-0.5.5-Coverage entschaerft
- `full-featured.yaml` haengt an vielen bestehenden Assertions
- Spatial braucht dialektspezifische Profile und eigene Rollback-Erwartungen

Deshalb bleibt Phase-F-konform:

- `schemas/spatial.yaml` als fokussiertes Positivfixture
- neue Golden-Master-Dateien fuer jeden Dialekt
- separate gezielte Driver-Tests fuer Negativ- und Rollback-Faelle

### 4.3 Golden-Master-Harness muss explizite Spatial-Profile setzen

Der aktuelle Golden-Master-Loop ruft `generator.generate(schema)` direkt auf.
Das reicht fuer Spatial nicht, weil damit die CLI-/Runner-Defaults umgangen
werden.

Verbindliche Folge:

- Spatial-Golden-Masters duerfen nicht auf implizite Generator-Defaults
  vertrauen
- der Harness setzt fuer das Spatial-Fixture explizit:
  - PostgreSQL -> `POSTGIS`
  - MySQL -> `NATIVE`
  - SQLite -> `SPATIALITE`
- falls der gleiche Harness auch Nicht-Spatial-Fixtures laufen laesst, bleiben
  deren bisherige Erwartungen unangetastet

Nicht akzeptabel waere:

- ein Spatial-Golden-Master, das nur deshalb "gruen" ist, weil versehentlich
  `SpatialProfile.NONE` aktiv war
- ein MySQL-Spatial-Master, das implizit einen zweiten `none`-Generatorpfad
  voraussetzt, obwohl dieser Fall eigentlich am CLI-/Runner-Gate haengen soll

Zusatz fuer MySQL:

- MySQL-Golden-Masters und MySQL-Driver-Tests laufen nur auf dem kanonischen
  `NATIVE`-Pfad
- die Kombination `mysql + none` wird als eigener CLI-/Runner-Gate-Fall
  getestet, nicht als zweiter Driver-Pfad
- Phase F ist erst abnahmefaehig, wenn Phase-D-Matrix, Phase-E-Generatorvertrag
  und `SpatialProfilePolicy` dabei dieselbe Semantik abbilden

### 4.4 Spatial-Fehlfaelle muessen den ganzen Output-Vertrag pruefen

Bei `E052` und `W120` reicht keine einzelne SQL-String-Assertion.

Verbindliche Folge:

- Driver-Tests pruefen bei Spatial-Fehlfaellen mindestens:
  - erzeugte SQL bzw. deren bewusstes Fehlen
  - `TransformationNote`
  - `SkippedObject`, falls vereinbart
  - Verbot partieller Tabellen-DDL
- CLI-/Formats-Tests pruefen die Projektion nach:
  - JSON-Output
  - Sidecar-Report
  - Rollback-Datei, falls angefordert

### 4.5 Driver-Regressionen fuer bestehende Typen gehoeren in dieselbe Matrix

0.5.5 ist nicht nur Spatial, sondern auch Haertung von `uuid`, `json`,
`binary` und `array`. Deshalb ist Phase F nicht abgeschlossen, wenn nur die
neuen Spatial-Pfade gruen sind.

Verbindliche Folge:

- die bestehenden Driver-Suites behalten oder ergaenzen Assertions fuer die
  nicht-spatialen erweiterten Typen
- Spatial-Aenderungen duerfen dort keine stillen Regressionen verursachen

---

## 5. Arbeitspakete

### F.1 Core- und Modelltests

Betroffene Module:

- `hexagon:core:test`

Arbeitspunkte:

1. Bestehende Validator-Tests fuer `E120` und `E121` auf finalen
   Fehlermeldungs- und Pfadvertrag abgleichen.
2. Explizit absichern, dass unbekannte `geometry_type`-Werte bis zum Validator
   transportiert und nicht schon vorher verworfen werden.
3. Den Ausschluss von `array.element_type: geometry` als Regressionstest
   beibehalten.
4. Falls noetig, `NeutralTypeTest`/`GeometryType`-Tests um kanonische
   Default- und Known/Unknown-Semantik ergaenzen.

### F.2 Formats-, Fixture- und Reporttests

Betroffene Module:

- `adapters:driven:formats:test`
- `adapters:driven:driver-common:test`

Arbeitspunkte:

1. `YamlSchemaCodecTest` fuer den finalen Spatial-Fixture-/Negativfixture-Satz
   stabilisieren.
2. Sicherstellen, dass die bestehende JSON-Smoke-Absicherung fuer
   `schemas/spatial.yaml` erhalten bleibt.
3. `TransformationReportWriterTest` um echte Spatial-Codes erweitern:
   - `E052` in `skipped_objects`
   - `W120` in `notes`
4. `DdlModelTest` bzw. gleichwertige gemeinsame Tests fuer Render-/Hint-Vertrag
   bei Spatial-Codes weiter nutzen oder nachziehen.
5. Die Benennung von `all-types`/Spatial-Coverage nicht wieder aufweichen.

### F.3 PostgreSQL-Driver-Tests

Betroffene Module:

- `adapters:driven:driver-postgresql:test`

Arbeitspunkte:

1. Positivtest fuer `geometry(<Type>, <SRID>)` mit Profil `postgis`.
2. Positivtest fuer fehlendes `srid` -> `0`.
3. Negativtest fuer Profil `none`:
   - `E052`
   - `SkippedObject` auf Tabellenebene
   - keine partielle `CREATE TABLE`-DDL ohne Geometry-Spalte
4. Rollback-Test fuer Spatial-Tabellen unter demselben Profil.
5. Regression der bestehenden nicht-spatialen erweiterten Typen im selben
   Generatorpfad.

### F.4 MySQL-Driver-Tests

Betroffene Module:

- `adapters:driven:driver-mysql:test`

Arbeitspunkte:

1. Positivtests fuer Mapping auf native Spatial-Typen:
   - `POINT`
   - `POLYGON`
   - `MULTIPOLYGON`
   - `GEOMETRY`
2. Expliziter Test fuer `W120`, wenn SRID nicht voll uebertragbar ist.
3. MySQL-Driver-Tests laufen ausschliesslich auf dem kanonischen `native`-Pfad;
   ein generatorinterner `none`-Pfad wird hier nicht vorausgesetzt.
4. Sicherstellen, dass kein PostgreSQL-/SQLite-Blockierungsverhalten nach
   MySQL ueberlaeuft.
5. Rollback-Test fuer Spatial-Tabellen unter Profil `native`.
6. Regression der bestehenden nicht-spatialen erweiterten Typen im
   MySQL-Generator.

### F.5 SQLite-/SpatiaLite-Driver-Tests

Betroffene Module:

- `adapters:driven:driver-sqlite:test`

Arbeitspunkte:

1. Positivtest fuer den zweistufigen Pfad:
   - `CREATE TABLE` ohne Geometry-Spalte
   - `SELECT AddGeometryColumn(...)`
2. Explizite Tests fuer den Down-Pfad mit `DiscardGeometryColumn(...)`.
3. Negativtest fuer Profil `none`:
   - `E052`
   - `SkippedObject` auf Tabellenebene
   - keine partielle Tabellen-DDL
4. Tests fuer Geometry-bezogene Tabellenmetadaten gemaess Phase E:
   - entweder korrekt getragen
   - oder Tabelle explizit blockiert
5. Regression der bestehenden `json`-/`array`-Fallbacks und der weiteren
   erweiterten Typen.

### F.6 Golden Masters

Betroffene Module:

- `adapters:driven:formats:test`

Arbeitspunkte:

1. Neue Golden-Master-Dateien anlegen:
   - `fixtures/ddl/spatial.postgresql.sql`
   - `fixtures/ddl/spatial.mysql.sql`
   - `fixtures/ddl/spatial.sqlite.sql`
2. `DdlGoldenMasterTest` um `spatial` erweitern.
3. Fuer `spatial` explizite `DdlGenerationOptions` je Dialekt setzen, statt
   auf den nackten Generator-Default zu vertrauen.
4. Sicherstellen, dass die vorhandenen Nicht-Spatial-Golden-Masters davon
   nicht unbeabsichtigt beeinflusst werden.

### F.7 CLI- und Runner-Tests

Betroffene Module:

- `adapters:driving:cli:test`

Solange `SchemaGenerateRunnerTest` und `SchemaGenerateHelpersTest` physisch in
`adapters/driving/cli` liegen, ist `:adapters:driving:cli:test` auch der
verbindliche Verifikationstask fuer Runner- und Helper-Verhalten. Eine spaetere
Verschiebung nach `hexagon:application:test` ist fuer Phase F nur relevant,
wenn Artefaktliste und Verifikation im selben Zug konsistent umgestellt werden.

Arbeitspunkte:

1. Vorhandene Tests fuer Profil-Parsing und Default-Weitergabe beibehalten.
2. Expliziten Gate-Test fuer `mysql + none` nachziehen. Fuer den finalen
   0.5.5-Vertrag darf diese Kombination nicht still ungetestet bleiben; falls
   der Phase-E-Vertrag gilt, endet sie vor dem Generator mit Exit-Code `2`.
3. Spatial-CLI-Tests fuer `--output-format json` nachziehen:
   - `E052` sichtbar
   - `W120` sichtbar
4. Spatial-CLI-Tests fuer `--output` + Sidecar-Report nachziehen.
5. Spatial-CLI-Tests fuer `--generate-rollback` nachziehen.
6. Runner-Tests dafuer ergaenzen, dass derselbe Profilwert konsistent in
   Generate- und Rollback-Pfad verwendet wird.
7. Fehlkonfigurationen und Exit-Codes weiter dialektbezogen absichern.

---

## 6. Technische Zielstruktur

### 6.1 Fixture-Set

Phase F arbeitet mit einem kleinen, klar abgegrenzten Spatial-Fixture-Set:

- `schemas/spatial.yaml` als Positivfixture
- `invalid/E120_invalid_geometry_type.yaml`
- `invalid/E121_srid_zero.yaml`
- `invalid/E121_srid_negative.yaml`
- neue Golden-Master-SQL:
  - `ddl/spatial.postgresql.sql`
  - `ddl/spatial.mysql.sql`
  - `ddl/spatial.sqlite.sql`

Das Ziel ist nicht maximale Fixture-Menge, sondern hohe Signalstaerke pro
Datei.

### 6.2 Golden-Master-Optionsmatrix

Der Golden-Master-Harness braucht fuer `spatial.yaml` eine explizite
Profilmatrix:

- PostgreSQL -> `SpatialProfile.POSTGIS`
- MySQL -> `SpatialProfile.NATIVE`
- SQLite -> `SpatialProfile.SPATIALITE`

Wichtig:

- diese Matrix sitzt im Test-Harness, nicht implizit in den Golden-Master-
  Dateien
- Nicht-Spatial-Fixtures koennen weiter ohne Spatial-Profil laufen
- Spatial-Masters duerfen die CLI-Default-Semantik abbilden, auch wenn sie den
  Runner direkt umgehen
- fuer MySQL gibt es in Golden Masters keinen separaten `none`-Pfad; dieser
  Fall wird separat als CLI-/Runner-Gate verifiziert

### 6.3 Output-Vertrag fuer Fehlfaelle

Fuer Spatial-Fehlfaelle muessen Tests den gesamten Ergebnisvertrag pruefen:

- `TransformationNote`
- `SkippedObject`
- gerenderte SQL-Kommentare
- JSON-Ausgabe
- Sidecar-Report
- Rollback-Datei oder deren bewusstes Fehlen

Insbesondere fuer `E052` auf Tabellenebene gilt:

- keine partielle Tabellen-DDL
- `action_required` bleibt in JSON/Report sichtbar
- Code und Hint gehen in den strukturierten Kanaelen nicht verloren

### 6.4 Regressionskante fuer bestehende Typen

Spatial-Testarbeit darf nicht dazu fuehren, dass `uuid`, `json`, `binary` oder
`array` nur noch implizit durch alte Fixtures "irgendwie" mitlaufen.

Phase F erwartet deshalb:

- gezielte Driver-Regressionen fuer diese Typen in den umgebauten Generatoren
- kein Rueckfall auf unehrliche Coverage-Claims ueber `all-types`

---

## 7. Betroffene Artefakte

Voraussichtlich anzupassen oder zu erweitern:

- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/SchemaValidatorTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/model/NeutralTypeTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/TransformationReportWriterTest.kt`
- `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/DdlModelTest.kt`
- `adapters/driven/driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGeneratorTest.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
- `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGeneratorTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliGenerateTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`
- `adapters/driven/formats/src/test/resources/fixtures/schemas/spatial.yaml`
- `adapters/driven/formats/src/test/resources/fixtures/invalid/E120_invalid_geometry_type.yaml`
- `adapters/driven/formats/src/test/resources/fixtures/invalid/E121_srid_zero.yaml`
- `adapters/driven/formats/src/test/resources/fixtures/invalid/E121_srid_negative.yaml`
- neue Dateien:
  - `adapters/driven/formats/src/test/resources/fixtures/ddl/spatial.postgresql.sql`
  - `adapters/driven/formats/src/test/resources/fixtures/ddl/spatial.mysql.sql`
  - `adapters/driven/formats/src/test/resources/fixtures/ddl/spatial.sqlite.sql`

---

## 8. Akzeptanzkriterien

- [ ] `SchemaValidatorTest` deckt `E120`, `E121` und den Ausschluss von
      `array.element_type: geometry` explizit ab.
- [ ] `YamlSchemaCodecTest` deckt `schemas/spatial.yaml`, die drei
      Spatial-Negativfixtures und den JSON-Smoke explizit ab.
- [ ] `DdlGoldenMasterTest` enthaelt `spatial` fuer PostgreSQL, MySQL und
      SQLite.
- [ ] Der Golden-Master-Harness setzt fuer `spatial` explizite
      `DdlGenerationOptions` pro Dialekt.
- [ ] PostgreSQL-Driver-Tests pruefen den Positivpfad fuer
      `geometry(<Type>, <SRID>)`.
- [ ] PostgreSQL-Driver-Tests pruefen den Blockierungspfad mit Profil `none`
      inklusive `E052`, `SkippedObject` und fehlender partieller Tabellen-DDL.
- [ ] MySQL-Driver-Tests pruefen native Spatial-Typen und mindestens einen
      expliziten `W120`-Fall; ein generatorinterner MySQL-`none`-Pfad wird
      dabei nicht still vorausgesetzt.
- [ ] SQLite-Driver-Tests pruefen `AddGeometryColumn(...)`,
      `DiscardGeometryColumn(...)` und den Blockierungspfad mit Profil `none`.
- [ ] Spatial-Rollback ist in allen drei Driver-Suites explizit getestet.
- [ ] CLI-/Runner-Tests pruefen Spatial fuer:
      `--spatial-profile`, `--output-format json`, `--output` + Report und
      `--generate-rollback`.
- [ ] CLI-/Runner-Tests fixieren den kanonischen Gate-Fall `mysql + none`
      explizit; fuer den finalen 0.5.5-Vertrag ist die erwartete Semantik dabei
      nicht mehr zwischen Phase D, Phase E und `SpatialProfilePolicy`
      widerspruechlich.
- [ ] JSON-Output und Sidecar-Report transportieren `E052` und `W120`
      konsistent.
- [ ] Die bestehenden nicht-spatialen erweiterten Typen `uuid`, `json`,
      `binary` und `array` sind in den betroffenen Generator-Suites
      regressionsseitig abgesichert.

---

## 9. Verifikation

Phase F wird mit einem gezielten Testlauf ueber Core, Formats, Driver und CLI
verifiziert. Mindestumfang:

Solange die Runner-/Helper-Tests noch unter `adapters/driving/cli` liegen,
genuegt dafuer `:adapters:driving:cli:test`. Falls dieselben Tests im Zuge der
Parallel-Arbeit gemaess `docs/hexagonal-port.md` bereits nach
`hexagon:application:test` verschoben wurden, ist der Verifikationstask fuer
diesen Slice im selben Commit entsprechend umzustellen.

1. Gezielter Testlauf:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test :adapters:driven:formats:test :adapters:driven:driver-postgresql:test :adapters:driven:driver-mysql:test :adapters:driven:driver-sqlite:test :adapters:driving:cli:test" \
  -t d-migrate:0.5.5-phase-f-tests .
```

2. Optionaler Vollbuild mit CLI-Distribution:

```bash
docker build --no-cache \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:0.5.5-phase-f .
```

3. Manuelle Smoke-Proben gegen das Spatial-Fixture:

```bash
docker run --rm -v $(pwd):/work d-migrate:0.5.5-phase-f \
  schema generate --source /work/adapters/driven/formats/src/test/resources/fixtures/schemas/spatial.yaml --target postgresql

docker run --rm -v $(pwd):/work d-migrate:0.5.5-phase-f \
  --output-format json schema generate --source /work/adapters/driven/formats/src/test/resources/fixtures/schemas/spatial.yaml --target mysql

docker run --rm -v $(pwd):/work d-migrate:0.5.5-phase-f \
  schema generate --source /work/adapters/driven/formats/src/test/resources/fixtures/schemas/spatial.yaml --target sqlite --spatial-profile spatialite --output /work/spatial.sqlite.sql --generate-rollback
```

Dabei explizit pruefen:

- Spatial-Golden-Masters laufen mit expliziten Profilen
- PostgreSQL/SQLite blockieren mit Profil `none` die ganze Tabelle
- der kanonische Gate-Fall `mysql + none` ist in CLI-/Runner-Tests explizit
  fixiert
- MySQL projiziert `W120` in den strukturierten Output
- SQLite erzeugt Up- und Down-Pfad fuer `AddGeometryColumn(...)`
- JSON-Output und Sidecar-Report verlieren Code/Hint nicht

---

## 10. Risiken und offene Punkte

### R1 - Golden Masters koennen mit falschem Profil gruen wirken

Wenn der Test-Harness Spatial ohne explizite Optionen laufen laesst, pruefen
die Golden Masters moeglicherweise nur den nackten Generator-Default statt der
tatsaechlichen 0.5.5-Semantik.

### R2 - Driver- und CLI-Tests koennen sich gegenseitig zu wenig pruefen

Wenn SQL-Semantik nur in CLI-Tests oder Output-Projektion nur in Driver-Tests
landet, entstehen Luecken trotz "viel Testcode". Die Aufgabentrennung pro
Schicht muss sauber bleiben.

### R3 - SQLite-Rollback bleibt der sensibelste Pfad

`AddGeometryColumn(...)`/`DiscardGeometryColumn(...)` ist syntax- und
strategiesensitiv. Gerade dort duerfen Golden Masters nicht die einzigen
Assertions sein.

### R4 - Regressionsschutz fuer bestehende Typen darf nicht verdunsten

Spatial zieht viel Aufmerksamkeit auf sich. Phase F muss trotzdem sichtbar
absichern, dass `uuid`, `json`, `binary` und `array` in den umgebauten
Generatoren stabil bleiben.

### R5 - MySQL-Profilvertrag kann zwischen D, E und Policy auseinanderlaufen

Wenn Phase D, Phase E und `SpatialProfilePolicy` fuer `mysql + none` nicht
dieselbe Semantik meinen, testet Phase F sonst nur einen Zufallszustand.
Deshalb braucht die Phase einen expliziten Gate-Test und eine eindeutige
Abnahme fuer diesen Fall.

---

## 11. Abschlussdefinition

Phase F ist abgeschlossen, wenn die 0.5.5-Testmatrix den finalen Spatial- und
Haertungsvertrag ueber alle relevanten Schichten sichtbar abdeckt:

- Core validiert Spatial korrekt
- Formats und Fixtures reproduzieren Positiv- und Negativfaelle
- Driver-Tests pruefen die eigentliche Dialektsemantik
- Golden Masters decken Spatial pro Dialekt mit korrektem Profil ab
- CLI, JSON und Report transportieren `E052` und `W120` konsistent

Danach ist 0.5.5 nicht nur implementiert, sondern generatorseitig robust genug
abgesichert, um 0.6.0 auf einem belastbaren Typsystem aufsetzen zu lassen.
