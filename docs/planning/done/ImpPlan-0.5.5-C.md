# Implementierungsplan: Phase C - Schema-Codecs und Fixtures

> **Milestone**: 0.5.5 - Erweitertes Typsystem
> **Phase**: C (Schema-Codecs und Fixtures)
> **Status**: Done (2026-04-13)
> **Referenz**: `docs/planning/implementation-plan-0.5.5.md` Abschnitt 2,
> Abschnitt 4.4, Abschnitt 4.9, Abschnitt 5 Phase C, Abschnitt 6.1,
> Abschnitt 6.4, Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `docs/planning/ImpPlan-0.5.5-A.md`; `docs/planning/ImpPlan-0.5.5-B.md`;
> `docs/planning/change-request-spatial-types.md`

---

## 1. Ziel

Phase C macht den in Phase B erweiterten Core-Vertrag ueber Schema-Dateien
erreichbar und testbar. Ergebnis der Phase ist ein Read-Pfad, der Spatial
Phase 1 aus YAML-/JSON-Schemadateien in das neutrale Modell ueberfuehrt, sowie
eine Fixture-Basis fuer positive und negative 0.5.5-Faelle.

Der Teilplan liefert bewusst noch keine Generator-Optionen, keine
Dialektabbildung und keine Schreibunterstuetzung fuer `SchemaCodec.write(...)`.
Er schliesst die Luecke zwischen Core-Modell und den spaeteren CLI-/Generator-
Phasen.

Nach Phase C soll klar und testbar gelten:

- `YamlSchemaCodec` versteht `type: geometry`
- `geometry_type` und `srid` werden in das neue Core-Modell eingelesen
- ungueltige Spatial-Werte werden nicht vorschnell im Codec verworfen, sondern
  koennen den Validatorpfad `E120` und `E121` erreichen
- derselbe Read-Pfad deckt mindestens einen kleinen JSON-Smoke fuer Spatial mit
  ab
- es existieren gezielte Spatial-Fixtures fuer positive und negative Faelle
- der bestehende Read-Pfad bleibt read-only; `write(...)` bleibt ausserhalb von
  0.5.5

---

## 2. Ausgangslage

Aktueller Stand in `adapters:driven:formats`:

- Es gibt genau einen Schema-Codec: `YamlSchemaCodec`.
- `YamlSchemaCodec.read(...)` parst heute bekannte neutrale Typen bis
  einschliesslich `array`, aber noch kein `type: geometry`.
- `parseNeutralType(...)` wirft fuer unbekannte `type`-Namen derzeit direkt
  `IllegalArgumentException("Unknown type: ...")`.
- `YamlSchemaCodec.write(...)` ist weiterhin ein explizites `TODO` und damit
  produktiv nicht Bestandteil des aktuellen Pfads.
- Die bestehenden Schema-Fixtures liegen ausschliesslich unter
  `fixtures/schemas/*.yaml` und `fixtures/invalid/*.yaml`; explizite
  JSON-Schema-Fixtures gibt es derzeit nicht.
- `YamlSchemaCodecTest` testet aktuell:
  - `minimal.yaml`
  - `e-commerce.yaml`
  - `all-types.yaml`
  - `full-featured.yaml`
  - eine Reihe validatorseitiger Invalid-Fixtures von `E001` bis `E017`
- `all-types.yaml` wird aktuell als Fixture "fuer alle neutralen Typen"
  verwendet und haengt an einem Test, der die Typabdeckung explizit anspricht.
- `all-types.yaml` und `full-featured.yaml` werden ausserdem von
  `DdlGoldenMasterTest` konsumiert.
- `full-featured.yaml` wird zusaetzlich in `SchemaComparatorFixtureTest`
  verwendet.
- Es existiert bereits eine parserseitig problematische Fixture
  `E004_duplicate_column.yaml`; sie ist vorhanden, wird aber in
  `YamlSchemaCodecTest` nicht als normaler Validator-Fall durchiteriert.

Konsequenz fuer Phase C:

- Der Codec muss Spatial lesen koennen, ohne die in Phase B bewusst
  validatorseitig verankerten Fehler `E120` und `E121` in Parserfehler zu
  verwandeln.
- Der Masterplan erwartet Erreichbarkeit ueber YAML/JSON-Schemadateien,
  waehrend im aktuellen Formats-Modul nur YAML-Fixtures existieren.
- Die Fixture-Strategie muss die breite Wiederverwendung von `all-types` und
  `full-featured` beruecksichtigen.
- Wenn Spatial blind in bestehende Shared-Fixtures eingebaut wird, schlaegt das
  sofort auf DDL-Golden-Master-Tests durch, obwohl Generator-Optionen und
  Dialektpfade erst in spaeteren Phasen kommen.

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- Erweiterung von `YamlSchemaCodec` um:
  - `type: geometry`
  - `geometry_type`
  - `srid`
- Anpassung der Codec-Tests auf das neue Core-Modell aus Phase B
- mindestens ein kleiner JSON-Smoke ueber denselben `YamlSchemaCodec`-Read-Pfad
- neue oder erweiterte positive Spatial-Fixtures
- neue negative Spatial-Fixtures fuer:
  - ungueltigen `geometry_type`
  - `srid: 0`
  - `srid < 0`
- Anpassung der Fixture-Tests im Formats-Modul, soweit sie direkt den
  Schema-Read-Pfad absichern
- bewusstes Nachziehen der Array-Fixtures, falls sich durch Phase B die
  dokumentierte `element_type`-Menge schaerft

### 3.2 Bewusst nicht Teil von Phase C

- Implementierung von `SchemaCodec.write(...)`
- zweiter separater `JsonSchemaCodec`
- CLI-Flags, `spatialProfile` oder Generator-Optionen
- DDL-Golden-Master fuer Spatial
- Integration neuer Spatial-Fixtures in den generischen DDL-Golden-Master-Loop,
  solange Phase D/E den Generatorpfad noch nicht nachgezogen haben
- `type: geography`, `z`, `m`
- parserseitige Profil- oder Dialektvalidierung

---

## 4. Architekturentscheidungen

### 4.1 `YamlSchemaCodec` bleibt der eine Read-Pfad

Phase C fuehrt keinen neuen Codec-Typ ein. Der bestehende `YamlSchemaCodec`
bleibt der produktive Schema-Read-Pfad fuer 0.5.5.

Das bedeutet:

- keine neue `JsonSchemaCodec`-Klasse
- keine Ausweitung des Milestones auf Schreiblogik
- Fokussierung auf den bestehenden Einlesepfad, den CLI und Tests ohnehin
  bereits verwenden
- mindestens ein kleiner JSON-Smoke laeuft ueber denselben Read-Pfad mit
  derselben Codec-Implementierung

### 4.2 Spatial-Invalids bleiben validatorseitig, nicht codec-seitig

Phase B hat `E120` und `E121` bewusst als Modell-/Validator-Regeln festgelegt.
Phase C darf diese Trennung nicht wieder unterlaufen.

Verbindliche Folge fuer den Codec:

- `type: geometry` wird erkannt
- `geometry_type` wird in den verlustfreien Core-Typ aus Phase B eingelesen
- unbekannte `geometry_type`-Werte duerfen nicht schon beim Read-Pfad als
  Parserfehler verschwinden
- `srid: 0` und negativer `srid` werden als Werte eingelesen und erst spaeter
  durch den Validator beanstandet

Parse-Fehler bleiben auf echte Parse-/Strukturprobleme beschraenkt, z. B.:

- unbekannter Basistypname in `type`
- fehlende Pflichtfelder
- ungueltige Enum-/Constraint-/Trigger-Schluessel
- Duplicate-Key-Faelle wie `E004`

### 4.3 Fixture-Strategie: fokussierte Spatial-Fixtures vor Shared-Fixture-Umbau

Da `all-types.yaml` und `full-featured.yaml` heute bereits von
`DdlGoldenMasterTest` konsumiert werden, ist ein frueher harter Umbau dieser
Fixtures teuer.

Verbindliche Zielrichtung fuer Phase C:

- zuerst gezielte Spatial-Fixtures fuer Codec- und Validator-Pfade anlegen
- bestehende Shared-Fixtures nur dann erweitern, wenn der Blast Radius bewusst
  mitgezogen wird
- kein implizites Sprengen des bestehenden DDL-Golden-Master-Loops in Phase C

Pragmatisch heisst das:

- ein fokussiertes `spatial.yaml` oder aehnliche kleine Schemata ist fuer Phase
  C der bevorzugte Pfad
- `all-types.yaml` kann spaeter nachgezogen werden, wenn Generator und Golden
  Masters dieselbe Typmenge produktiv tragen

### 4.4 Parser-negative und Validator-negative Fixtures werden getrennt behandelt

Nicht jede invalide Fixture ist vom selben Typ.

Phase C unterscheidet deshalb bewusst:

- parser-negative Fixtures:
  - `codec.read(...)` scheitert direkt
  - Beispiel: Duplicate Keys oder unbekannter `type`
- validator-negative Fixtures:
  - `codec.read(...)` ist erfolgreich
  - `SchemaValidator.validate(...)` liefert den erwarteten Fehlercode
  - Beispiele fuer 0.5.5:
    - ungueltiger `geometry_type`
    - `srid: 0`
    - `srid < 0`

### 4.5 Read-only bleibt verbindlich

Obwohl `SchemaCodec` ein `write(...)`-Interface besitzt, bleibt 0.5.5 auf den
Read-/Generate-Pfad beschraenkt.

Phase C dokumentiert und testet daher bewusst nicht:

- Roundtrip `read -> write -> read`
- YAML-Emission des neuen Geometry-Typs
- serialisierte JSON-Ausgabe fuer Schema-Dateien

---

## 5. Arbeitspakete

### C.1 `YamlSchemaCodec` um Spatial Phase 1 erweitern

`parseNeutralType(...)` ist um den neuen Typ `geometry` zu erweitern.

Mindestens erforderlich:

- Erkennen von `type: geometry`
- Einlesen von `geometry_type`
- Einlesen von `srid`
- Nutzung des Core-Modells aus Phase B statt lokaler String-Sonderfaelle

Wichtig:

- fehlt `geometry_type`, greift der Modell-Default
- unbekannte `geometry_type`-Werte muessen den Validatorpfad noch erreichen

### C.2 Codec-Tests auf Spatial erweitern

`YamlSchemaCodecTest` ist um positive Spatial-Read-Faelle zu erweitern.

Mindestens noetig:

- Geometry-Spalte mit explizitem `geometry_type` und `srid`
- Geometry-Spalte ohne `geometry_type`, damit der Modell-Default sichtbar ist
- Validatorlauf fuer ein gueltiges Spatial-Schema
- mindestens ein kleiner JSON-Smoke fuer ein Spatial-Schema ueber denselben
  `YamlSchemaCodec`-Read-Pfad

Zusaetzlich sind die bestehenden Typabdeckungs-Tests zu ueberpruefen:

- falls `all-types.yaml` erweitert wird, muss die Typabdeckung konsistent
  nachgezogen werden
- falls Spatial bewusst in fokussierten Fixtures bleibt, darf der bisherige
  "all types"-Pfad nicht faelschlich als 0.5.5-vollstaendig missverstanden
  werden

Verbindliche Folge:

- entweder `all-types.yaml` wird um `geometry` erweitert und der zugehoerige
  Test zieht die Typabdeckung explizit nach
- oder der bestehende Testname/-anspruch wird so angepasst, dass er nicht mehr
  behauptet, alle neutralen Typen von 0.5.5 abzudecken

### C.3 Negative Spatial-Fixtures anlegen

Unter `fixtures/invalid/` sind neue validator-negative Spatial-Fixtures
anzulegen, mindestens fuer:

- `E120_invalid_geometry_type.yaml`
- `E121_srid_zero.yaml`
- `E121_srid_negative.yaml`

Verbindliche Semantik:

- `codec.read(...)` liest diese Dateien erfolgreich
- `SchemaValidator.validate(...)` liefert den erwarteten Fehlercode

### C.4 Parser-negative Codec-Tests explizit absichern

Neben validator-negativen Spatial-Fixtures braucht Phase C mindestens einen
expliziten codec-seitigen Negativpfad fuer echte Parse-Fehler.

Mindestens einer der folgenden Faelle ist als eigener Codec-Test abzudecken:

- Duplicate-Key-Fall wie `E004_duplicate_column.yaml`
- unbekannter `type`

Wichtig:

- parser-negative Faelle muessen ueber `codec.read(...)` direkt scheitern
- sie duerfen nicht still in den validator-negativen Fixture-Loop einsortiert
  werden

### C.5 Array-Fixtures bei Bedarf nachschaerfen

Falls Phase B die zulaessige `array.element_type`-Menge enger oder expliziter
gezogen hat, muessen die bestehenden Schema-/Invalid-Fixtures nachgezogen
werden.

Das betrifft insbesondere:

- `all-types.yaml`
- bestehende `E015`-Fixtures
- eventuelle neue Invalid-Fixtures fuer nun unzulaessige `element_type`-Werte

### C.6 Bestehende Fixture-Konsumenten bewusst stabil halten

Vorhandene Tests im Formats-Modul konsumieren Shared-Fixtures bereits heute in
mehreren Rollen.

Phase C muss deshalb bewusst entscheiden:

- welche Spatial-Fixtures nur codec-/validatornah genutzt werden
- welche Shared-Fixtures produktiv erweitert werden
- welche Folgeanpassungen erst in Phase D/E/F erfolgen

Nicht akzeptabel ist:

- Geometry in Shared-Fixtures einzubauen, ohne die daraus folgenden DDL- und
  Fixture-Tests bewusst mitzudenken

---

## 6. Technische Zielstruktur

Fuer Phase C gilt eine kleine, klare Read-Pfad-Struktur:

```yaml
tables:
  places:
    columns:
      location:
        type: geometry
        geometry_type: point
        srid: 4326
```

Der Codec soll daraus ein `NeutralType.Geometry` nach dem Core-Vertrag aus
Phase B erzeugen.

Wichtig ist weniger die exakte interne Builder-Form als die Semantik:

- `type: geometry` ist ein normaler bekannter Basistyp
- `geometry_type` wird in den dedizierten Core-Typ ueberfuehrt
- unbekannte `geometry_type`-Strings werden verlustfrei transportiert
- `srid` bleibt optional
- `srid: 0` oder negativ wird nicht im Codec "wegkorrigiert"

Fuer die Fixture-Zielstruktur gilt:

- positive Spatial-Fixtures liegen getrennt von den bisherigen Shared-Fixtures
- validator-negative Spatial-Fixtures liegen unter `fixtures/invalid/`
- parser-negative Faelle bleiben als eigene Codec-Fehlerfaelle pruefbar

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodec.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`
- `adapters/driven/formats/src/test/resources/fixtures/schemas/`
- `adapters/driven/formats/src/test/resources/fixtures/invalid/`

Indirekte Fixture-Konsumenten:

- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/SchemaComparatorFixtureTest.kt`

Upstream-/Downstream-Kontext:

- `docs/planning/ImpPlan-0.5.5-B.md`
- spaetere Phasen D bis F

---

## 8. Akzeptanzkriterien

- [ ] `YamlSchemaCodec` liest `type: geometry` erfolgreich in das neue
      Core-Modell ein.
- [ ] `geometry_type` wird ueber den dedizierten Core-Typ aus Phase B
      transportiert.
- [ ] Fehlt `geometry_type`, greift der dokumentierte Modell-Default.
- [ ] `srid` wird als optionaler Wert eingelesen.
- [ ] Unbekannte `geometry_type`-Werte scheitern nicht bereits im Codec,
      sondern koennen `E120` im Validator ausloesen.
- [ ] `srid: 0` und negativer `srid` werden vom Codec eingelesen und loesen
      erst im Validator `E121` aus.
- [ ] Mindestens ein kleiner JSON-Smoke fuer ein Spatial-Schema laeuft ueber
      denselben `YamlSchemaCodec`-Read-Pfad erfolgreich.
- [ ] Es existieren positive Spatial-Fixtures fuer den Read-Pfad.
- [ ] Es existieren negative Spatial-Fixtures fuer `E120` und `E121`.
- [ ] Der bisherige `all-types`-Coverage-Claim ist konsistent zur
      0.5.5-Typmenge: entweder inkl. `geometry` nachgezogen oder sichtbar
      entschaerft.
- [ ] Es existiert mindestens ein expliziter parser-negativer Codec-Test fuer
      Duplicate-Key oder unbekannten `type`, getrennt von den
      validator-negativen Spatial-Fixtures.
- [ ] Die Fixture-Strategie ist so umgesetzt, dass der bestehende
      DDL-Golden-Master-Loop in Phase C nicht unbeabsichtigt durch fehlende
      Spatial-Generatorunterstuetzung gesprengt wird.
- [ ] `SchemaCodec.write(...)` bleibt fuer 0.5.5 unveraendert ausserhalb des
      Scopes.
- [ ] Falls die Array-Allowlist in Phase B geschaerft wurde, sind die
      betroffenen Schema-/Invalid-Fixtures konsistent nachgezogen.

---

## 9. Verifikation

Phase C wird primaer ueber das Formats-Modul verifiziert.

Mindestumfang:

1. Gezielter Testlauf:

```bash
./gradlew :adapters:driven:formats:test
```

2. Optionaler containerisierter Abgleich:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats:test" \
  -t d-migrate:0.5.5-formats-tests .
```

3. Inhaltliche Gegenpruefung der Tests auf folgende Faelle:

- Spatial-Positive-Read-Pfad funktioniert
- JSON-Smoke ueber denselben Read-Pfad funktioniert
- validator-negative Spatial-Fixtures liefern `E120` bzw. `E121`
- unbekannter `geometry_type` bleibt bis zur Validator-Pruefung erhalten
- parser-negative Codec-Tests fuer Duplicate-Key oder unbekannten `type` sind
  explizit getrennt von validator-negativen Fixtures
- `write(...)` wurde nicht versehentlich in den Scope gezogen
- Shared-Fixture-Aenderungen erzeugen keinen unbeabsichtigten Seiteneffekt auf
  nicht nachgezogene Golden Masters

4. Statische Review der Fixture-Strategie:

- fokussierte Spatial-Fixtures sind klar von Shared-Fixtures getrennt
- parser-negative und validator-negative Faelle sind nicht vermischt
- der bisherige `all-types`-Coverage-Claim passt zur tatsaechlichen
  0.5.5-Typabdeckung
- Folgeanpassungen fuer spaetere Generator-Phasen sind erkennbar abgegrenzt

---

## 10. Risiken und offene Punkte

### R1 - Verlustfreie `geometry_type`-Einlesung kann versehentlich verhaertet werden

Wenn `YamlSchemaCodec` unbekannte `geometry_type`-Werte schon beim Einlesen
ablehnt, wird `E120` aus dem Validatorpfad verdraengt.

### R2 - Shared-Fixtures haben hohen Blast Radius

`all-types.yaml` und `full-featured.yaml` werden nicht nur vom Codec-Test
verwendet. Unbedachte Spatial-Erweiterungen ziehen schnell DDL- und
Vergleichstests mit.

### R3 - Read-only-Scope kann ausufern

Da `SchemaCodec` formal ein `write(...)` besitzt, ist die Versuchung gross,
die neue Type-Emission "gleich mitzunehmen". Das waere fuer 0.5.5 unnoetiger
Mehrscope.

### R4 - JSON-Erwartung kann missverstanden werden

Der produktive Read-Pfad laeuft weiterhin ueber `YamlSchemaCodec`. Falls
explizite JSON-Syntax-Smokes hinzukommen, sollten sie klein bleiben und nicht
als Startpunkt fuer einen zweiten Codec missverstanden werden.

---

## 11. Abschlussdefinition

Phase C ist abgeschlossen, wenn der Schema-Read-Pfad `geometry`,
`geometry_type` und `srid` korrekt in das Core-Modell ueberfuehrt und dafuer
eine belastbare positive und negative Fixture-Basis existiert.

Danach ist fuer die Folgephasen klar:

- wie Spatial aus Schema-Dateien in das neutrale Modell gelangt
- dass validatorseitige Spatial-Fehler den Codec-Pfad sauber passieren
- welche Fixtures gezielt fuer Spatial genutzt werden
- und dass der bestehende Read-Pfad read-only bleibt
