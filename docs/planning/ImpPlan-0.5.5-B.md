# Implementierungsplan: Phase B - Kernmodell und Schema-Validierung

> **Milestone**: 0.5.5 - Erweitertes Typsystem
> **Phase**: B (Kernmodell und Schema-Validierung)
> **Status**: Done (2026-04-13)
> **Referenz**: `docs/planning/implementation-plan-0.5.5.md` Abschnitt 2,
> Abschnitt 4.2, Abschnitt 4.4, Abschnitt 4.9, Abschnitt 5 Phase B,
> Abschnitt 6.1, Abschnitt 6.4, Abschnitt 7, Abschnitt 8, Abschnitt 9,
> Abschnitt 10; `docs/planning/ImpPlan-0.5.5-A.md`;
> `docs/planning/change-request-spatial-types.md`

---

## 1. Ziel

Phase B produktiviert den 0.5.5-Vertrag im Core-Layer. Ergebnis der Phase ist
ein `hexagon:core`, das Spatial Phase 1 im neutralen Modell explizit kennt und
schema-zentriert validieren kann.

Der Teilplan liefert bewusst noch keine YAML-Einlesung, keine CLI-Optionen und
kein Dialekt-Mapping. Er schafft die Fachgrundlage, auf der die spaeteren
Phasen C bis F aufsetzen.

Nach Phase B soll im Core klar und testbar gelten:

- `NeutralType` kennt `geometry` explizit
- `geometry_type` ist im Code kein lose verteiltes Ad-hoc-Stringfeld
- `srid` ist als optionale positive Ganzzahl modelliert
- `SchemaValidator` erzeugt `E120` und `E121` fuer modellbezogene Fehler
- `SchemaValidator` kennt bewusst **nicht** `spatialProfile`, `E052` oder
  `W120`
- Basistyp-Allowlist und Array-Element-Allowlist sind getrennt; `geometry`
  bleibt in 0.5.5 ausserhalb der Array-Element-Allowlist

---

## 2. Ausgangslage

Aktueller Stand der Codebasis in `hexagon:core`:

- `NeutralType` kennt heute `identifier`, `text`, `char`, `integer`,
  `smallint`, `biginteger`, `float`, `decimal`, `boolean`, `datetime`, `date`,
  `time`, `uuid`, `json`, `xml`, `binary`, `email`, `enum` und `array`, aber
  keinen Typ `geometry`.
- `NeutralType.Array` speichert `elementType` aktuell als rohen `String`; ein
  eigener Typ fuer `geometry_type` oder andere kanonische Typnamen existiert im
  Modell noch nicht.
- Die einzige zentrale Typnamenliste liegt derzeit in
  `SchemaValidator.VALID_TYPE_NAMES`.
- Diese Liste dient heute nur als Array-Element-Allowlist fuer
  `array.element_type`; ein separater zentraler Basistypkatalog existiert im
  Core noch nicht.
- Dadurch ist der aktuelle Vertrag an zwei Stellen zu implizit:
  - die Array-Element-Allowlist lebt als Validator-Detail statt als expliziter
    wiederverwendbarer Core-Vertrag
  - wuerde `geometry` spaeter unreflektiert in dieselbe Menge aufgenommen,
    waere `array.element_type: geometry` automatisch mit freigeschaltet
- `SchemaValidator` kennt heute fuer Typregeln unter anderem `E006`, `E010`,
  `E011`, `E013`, `E014` und `E015`, aber noch keine Spatial-spezifischen
  Fehler `E120` und `E121`.
- `NeutralTypeTest` und `SchemaValidatorTest` decken heute Array-Verhalten ab,
  aber noch keine Spatial-Modelle oder Spatial-Validierung.

Konsequenz fuer Phase B:

- Der Core braucht eine explizite Spatial-Repraesentation, bevor Codec, CLI oder
  DDL-Generator sinnvoll erweitert werden koennen.
- Fuer `array.element_type` existiert heute bereits eine implizite zentrale
  Allowlist, fuer Basistypen aber noch kein gleichermassen expliziter
  wiederverwendbarer Katalog. Phase B muss diese Schieflage beheben, ohne
  `geometry` versehentlich fuer Arrays freizuschalten.
- Die Validierung muss Spatial-Phase-1-Regeln abbilden, ohne schon
  generatorseitige Profilentscheidungen in den Core zu ziehen.

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Erweiterung des neutralen Modells um `NeutralType.Geometry`
- kanonische Repraesentation fuer `geometry_type`
- optionale `srid`-Angabe im Modell
- Zentralisierung der Typ-Allowlists im Core
- Trennung von:
  - zulaessigen neutralen Basistypen
  - zulaessigen `array.element_type`-Werten
- Spatial-spezifische Schema-Validierung:
  - `E120` fuer unbekannten `geometry_type`
  - `E121` fuer `srid <= 0`
- Regression-Absicherung der bestehenden Array-Regeln im Core
- Unit-Tests fuer Modell und Validator in `hexagon:core:test`

### 3.2 Bewusst nicht Teil von Phase B

- YAML-/JSON-Parsing fuer `type: geometry`, `geometry_type` oder `srid`
- Generator-Optionen, `spatialProfile` oder CLI-Flags
- DDL-Mapping fuer PostgreSQL, MySQL oder SQLite
- generatorseitige Notes `E052` und `W120`
- `type: geography`, `z`, `m`
- rekursives Typ-AST oder Arrays von Spatial-Typen
- Reverse-Engineering

---

## 4. Architekturentscheidungen

### 4.1 `geometry_type` bekommt einen eigenen verlustfreien Core-Typ

Der Core soll `geometry_type` nicht als beliebigen freien String an mehreren
Stellen herumreichen.

Fuer 0.5.5 ist ein kleiner kanonischer Value-Typ mit bekannter Wertemenge
vorgesehen:

- `geometry`
- `point`
- `linestring`
- `polygon`
- `multipoint`
- `multilinestring`
- `multipolygon`
- `geometrycollection`

Wichtig ist die Semantik:

- bekannte Werte sind zentral definiert
- Default ist `geometry`
- unbekannte Werte bleiben bis zur Validierung verlustfrei transportierbar
- spaetere Erweiterungen muessen additiv moeglich bleiben

Verbindliche Konsequenz:

- keine reine Enum-only-Repraesentation, bei der unbekannte Eingaben schon vor
  `SchemaValidator` scheitern
- Phase C muss unbekannte `geometry_type`-Werte in diesen Core-Typ einlesen
  koennen, damit Phase B `E120` liefern kann

### 4.2 Modell-Defaults gehoeren ins Modell, nicht in den Validator

`geometry_type` soll im Modell auf `geometry` defaulten. Der Validator soll
nicht fehlende Werte per Sonderlogik "heilrechnen", sondern nur ungueltige
Zustaende pruefen.

Dasselbe gilt fuer `srid`:

- `null` ist zulaessig
- ein gesetzter Wert muss positiv sein

### 4.3 Basistypkatalog und Array-Allowlist werden getrennt

Die aktuelle Lage im Core ist fuer 0.5.5 fachlich zu unscharf:

- fuer `array.element_type` existiert eine Validator-Liste
- fuer Basistypen existiert noch kein expliziter wiederverwendbarer Katalog

Phase B fuehrt deshalb zwei getrennte Vertraege ein:

- Basistypkatalog fuer neutrale Spaltentypen
- Array-Element-Allowlist fuer `NeutralType.Array.elementType`

Verbindliche Folge:

- `geometry` wird in den Basistypkatalog aufgenommen
- `geometry` wird **nicht** automatisch Teil der Array-Element-Allowlist

### 4.4 `SchemaValidator` bleibt schema-zentriert

Spatial-Profil, Dialektfaehigkeit und Transformations-Notes gehoeren nicht in
Phase B.

Verbindlich bleibt:

- `E120` und `E121` sind normale Schema-Validierungsfehler
- `E052` und `W120` entstehen spaeter im Generator-/Report-Pfad
- `SchemaValidator` kennt kein `spatialProfile`

### 4.5 `array` bleibt absichtlich flach

Phase B fuehrt kein rekursives Modell wie `Array<NeutralType>` ein.

Der 0.5.5-Pfad bleibt bewusst klein:

- `NeutralType.Array` behaelt einen flachen Elementtyp-Vertrag
- die kanonische Zulaessigkeit wird ueber eine zentrale Allowlist geregelt
- Arrays von `geometry` bleiben ausgeschlossen

---

## 5. Arbeitspakete

### B.1 `NeutralType` um Spatial Phase 1 erweitern

In `hexagon:core` ist das Modell um folgende Bausteine zu erweitern:

- `NeutralType.Geometry`
- kanonischer Typ fuer `geometry_type`
- optionale `srid`

Dabei ist bewusst **nicht** Ziel:

- ein Platzhalter fuer `geography`
- Vorarbeit fuer `z` oder `m`
- ein generisches Typsystem-Redesign

### B.2 Zentrale Typkataloge im Core einfuehren

Die heutige `VALID_TYPE_NAMES`-Liste ist aus der Rolle einer versteckten
Validator-Detailkonstante zu loesen und zusammen mit einem expliziten
Basistypkatalog als Core-Vertrag zu zentralisieren.

Mindestens noetig:

- Liste oder Registry zulaessiger neutraler Basistypen
- separate Liste oder Registry zulaessiger Array-Elementtypen
- konsistente Verwendung dieser Kataloge im Validator und in spaeteren Phasen

Wichtig:

- die Aufnahme von `geometry` in den Basistypkatalog darf nicht dazu fuehren,
  dass `array.element_type: geometry` implizit akzeptiert wird
- der heutige implizite breite Scope fuer Array-Elementtypen muss sichtbar
  eingegrenzt werden

### B.3 `SchemaValidator` um Spatial-Regeln erweitern

Der Validator ist um modellbezogene Spatial-Regeln zu erweitern:

- `E120`: unbekannter `geometry_type`
- `E121`: `srid <= 0`

Zusaetzlich ist die Array-Validierung gegen die separate Array-Allowlist zu
ziehen.

Verbindlich fuer `E120`:

- unbekannte `geometry_type`-Werte muessen im Core bis zur Validator-Pruefung
  verlustfrei transportierbar sein
- Phase B darf keinen Modelltyp vorgeben, der diese Eingaben schon vor
  `SchemaValidator` unmoeglich macht

Explizit nicht in Phase B:

- Profilpruefungen pro Dialekt
- `action_required`
- Warnings zur SRID-Uebernahme

### B.4 Core-Tests erweitern

Die Core-Tests muessen den neuen Vertrag sichtbar absichern.

Mindestens erforderlich:

- `NeutralTypeTest` fuer `Geometry`
- Update exhaustiver `when`-Pfade auf den neuen Typ
- `SchemaValidatorTest` fuer:
  - gueltiges `geometry` mit Default- oder explizitem `geometry_type`
  - `E120` bei ungueltigem `geometry_type`
  - `E121` bei `srid: 0`
  - `E121` bei negativem `srid`
  - Array-Regressionen, damit `geometry` nicht still durch die
    `array.element_type`-Validierung rutscht

---

## 6. Technische Zielstruktur

Phase B braucht eine kleine, explizite Zielstruktur im Core. Eine moegliche
Minimalform ist:

```kotlin
sealed class NeutralType {
    data class Geometry(
        val geometryType: GeometryTypeValue = GeometryTypeValue.geometry(),
        val srid: Int? = null,
    ) : NeutralType()
}
```

Ergaenzend dazu ein zentraler Geometry-Typ-Vertrag als kleiner Value-Typ mit:

- kanonischem `schemaName`
- zentraler Known-Set-Logik
- verlustfreier Aufnahme unbekannter Eingaben

Wichtig ist weniger die exakte Kotlin-Form als die Semantik:

- `geometry_type` ist nicht bloss ein roher `String`
- Default ist `geometry`
- bekannte Werte sind zentral
- unbekannte Eingaben bleiben im Gesamtpfad so behandelbar, dass `E120`
  weiterhin sauber als Validierungsfehler ausweisbar ist

Fuer die Allowlists gilt als Zielstruktur:

- ein expliziter Basistypkatalog lebt auf Modell-/Core-Vertragsebene
- die Array-Element-Allowlist lebt nicht mehr als versteckte Sondermenge im
  Validator allein
- spaetere Phasen koennen dieselben zentralen Kataloge wiederverwenden

---

## 7. Betroffene Artefakte

Direkt betroffen:

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/NeutralType.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`
- optional neuer Core-Helfer fuer Geometry-/Typkataloge, z. B. unter
  `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/model/NeutralTypeTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/SchemaValidatorTest.kt`

Indirekte Eingaben und Folgeartefakte:

- `docs/planning/ImpPlan-0.5.5-A.md`
- `spec/neutral-model-spec.md`
- `docs/planning/change-request-spatial-types.md`
- Phase C bis F, die auf dem Core-Vertrag aufbauen

---

## 8. Akzeptanzkriterien

- [ ] `NeutralType` kennt `Geometry` explizit als neuen neutralen Typ.
- [ ] `geometry_type` ist im Core ueber einen dedizierten kanonischen Typ
      modelliert und nicht nur als lose String-Konvention beschrieben.
- [ ] Der gewaehlte Core-Typ fuer `geometry_type` kann unbekannte Eingaben
      verlustfrei bis zur Validator-Pruefung transportieren.
- [ ] `Geometry` traegt `srid` als optionale Ganzzahl.
- [ ] Der Modell-Default fuer `geometry_type` ist `geometry`.
- [ ] `SchemaValidator` akzeptiert `geometry` mit gueltigem `geometry_type` und
      optional positivem `srid`.
- [ ] Ungueltige `geometry_type`-Werte liefern `E120`.
- [ ] `srid <= 0` liefert `E121`.
- [ ] `SchemaValidator` kennt keine generatorseitigen Spatial-Codes
      `E052` oder `W120`.
- [ ] Ein expliziter Basistypkatalog und eine separate Array-Element-Allowlist
      sind im Core getrennt modelliert.
- [ ] `geometry` ist als Basistyp zulaessig, wird dadurch aber nicht
      automatisch zulaessiger `array.element_type`.
- [ ] Die bisherige implizite Sonderrolle von `VALID_TYPE_NAMES` als einzige
      zentrale Typnamenstelle besteht in dieser Form nicht mehr.
- [ ] `NeutralTypeTest` und `SchemaValidatorTest` decken die positiven und
      negativen Spatial-Phase-1-Faelle ab.

---

## 9. Verifikation

Phase B wird primaer ueber gezielte Core-Tests verifiziert.

Mindestumfang:

1. Direkter Core-Testlauf:

```bash
./gradlew :hexagon:core:test
```

2. Optionaler containerisierter Abgleich ueber den bestehenden Build-Pfad:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test" \
  -t d-migrate:0.5.5-core-tests .
```

3. Inhaltliche Gegenpruefung der Tests auf folgende Faelle:

- gueltige Geometry-Spalte wird vom Validator akzeptiert
- ungueltiger `geometry_type` fuehrt zu `E120`
- `srid: 0` und negativer `srid` fuehren zu `E121`
- `geometry` wird nicht implizit als Array-Elementtyp akzeptiert
- unbekannter `geometry_type` bleibt bis zur Validator-Pruefung erhalten und
  scheitert nicht schon an einer Enum-Konvertierung
- `SchemaValidator` erzeugt keine profilabhaengigen Spatial-Fehlercodes

4. Statische Code-Review der Core-Aenderung:

- `NeutralType` oder ein modellnaher Helper kapselt den Geometry-Typvertrag
  zentral
- der Validator benutzt getrennte Kataloge fuer Basis- und Array-Typen
- es wurde kein `spatialProfile`-Wissen in `hexagon:core` eingefuehrt

---

## 10. Risiken und offene Punkte

### R1 - Verlustfreie `geometry_type`-Repraesentation darf nicht verwaessert werden

Wenn spaeter doch eine reine Enum-only-Loesung oder ein parsernaher
Kurzschluss eingefuehrt wird, wird `E120` faktisch aus dem Validatorpfad
verdraengt.

### R2 - Array-Haertung kann bisher tolerierte Faelle sichtbar brechen

Sobald Basistyp- und Array-Allowlist getrennt werden, koennen bisher implizit
akzeptierte `element_type`-Werte ungueltig werden. Das ist fachlich gewollt,
muss aber bewusst und testbar passieren.

### R3 - Sealed-Class-Aenderungen strahlen breit aus

Ein neuer `NeutralType.Geometry` erzwingt Updates an exhaustiven `when`-Pfaeden
und kann dadurch Folgearbeit in spaeteren Phasen sichtbar machen. Das ist
erwuenscht, sollte aber nicht als "unerwarteter Seiteneffekt" behandelt werden.

### R4 - Profilwissen darf nicht in den Validator rutschen

Wenn in Phase B bereits mit Dialekten oder `spatialProfile` gearbeitet wird,
verliert die Architektur die saubere Trennung zwischen Schema-Modell und
Generierungsfaehigkeit.

---

## 11. Abschlussdefinition

Phase B ist abgeschlossen, wenn `hexagon:core` den Spatial-Phase-1-Vertrag
modell- und validatorseitig explizit traegt, ohne bereits Codec-, CLI- oder
Generatorlogik vorwegzunehmen.

Danach ist fuer die Folgephasen klar:

- welches Core-Modell `type: geometry` repraesentiert
- welche Spatial-Faelle `schema validate` selbst abdeckt
- wie `geometry_type` und `srid` kanonisch im Core verankert sind
- und dass `geometry` nicht versehentlich den Array-Scope erweitert
