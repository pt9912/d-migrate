# Implementierungsplan: 0.9.2 - Arbeitspaket 6.4 SQL-, JSON- und Report-Ausgabe fuer den Split nachziehen

> **Milestone**: 0.9.2 - Beta: DDL-Phasen und importfreundliche
> Schema-Artefakte
> **Arbeitspaket**: 6.4 (SQL-, JSON- und Report-Ausgabe fuer den Split
> nachziehen)
> **Status**: Draft (2026-04-19)
> **Referenz**: `docs/implementation-plan-0.9.2.md` Abschnitt 4.4,
> Abschnitt 5.2, Abschnitt 6.4, Abschnitt 7, Abschnitt 8 und Abschnitt
> 9.2;
> `docs/cli-spec.md`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`;
> `docs/ImpPlan-0.9.2-6.2.md`;
> `docs/ImpPlan-0.9.2-6.3.md`.

---

## 1. Ziel

Arbeitspaket 6.4 macht den in 6.2 und 6.3 vorbereiteten Split-Vertrag
ueber alle sichtbaren Ausgabekanaele wirksam:

- SQL-Dateiausgabe schreibt im Split-Fall zwei Artefakte
- JSON-Ausgabe serialisiert `split_mode`, `ddl_parts` und
  phasenattribuierte Diagnosen
- der YAML-Report traegt denselben Split-Bezug
- der Single-Fall bleibt fuer Text, JSON und Report byte- bzw.
  semantikstabil

6.4 liefert damit den sichtbaren Abschluss des Kernfeatures. Nach 6.2
gibt es bereits den Request- und Preflight-Vertrag, nach 6.3 bereits die
fachliche Phasenlogik der Generatoren. 6.4 verbindet beides mit den
realen Nutzerartefakten.

Nach 6.4 soll klar gelten:

- `--split pre-post --output out/schema.sql` erzeugt zwei SQL-Dateien
  statt eines Single-Artefakts
- `--split pre-post --output-format json` liefert keine Legacy-`ddl`-
  Antwort mehr, sondern den neuen Split-JSON-Vertrag
- Report, JSON und SQL spiegeln denselben Phasenbezug aus demselben
  `DdlResult`

---

## 2. Ausgangslage

Der aktuelle Stand im Repo ist ein vorbereiteter Zwischenzustand:

- `SchemaGenerateRequest` kennt bereits `splitMode`
- `SchemaGenerateRunner` validiert `pre-post`-Kombinationen bereits im
  Preflight
- nach bestandenem Preflight laeuft der Runner heute aber noch durch die
  bestehende Single-Ausgabelogik
- `SchemaGenerateHelpers.formatJsonOutput(...)` serialisiert weiterhin:
  - ein einzelnes Feld `ddl`
  - `notes` ohne `phase`
  - `skipped_objects` ohne `phase`
- `TransformationReportWriter` kennt weder `split_mode` noch
  phasenattribuierte Notes oder Skips
- die Sidecar-/Rollback-Pfadlogik kennt noch keine Split-Dateinamen

Damit ist der sichtbare Split-Vertrag noch nicht geschlossen:

- SQL im Split-Fall schreibt heute noch nur eine Datei
- JSON im Split-Fall ist noch Legacy-Single-Ausgabe
- Report und JSON koennen den Phasenbezug aus 6.1/6.3 noch nicht
  sichtbar machen

Gleichzeitig ist die Rueckwaertskompatibilitaet kritisch:

- bestehende Golden Masters fuer `single` duerfen nicht unbeabsichtigt
  kippen
- bestehende Consumer von `ddl` im Single-Fall muessen denselben
  Vertrag behalten
- neue Split-Felder duerfen nur im Split-Fall sichtbar werden

---

## 3. Scope fuer 6.4

### 3.1 In Scope

- `SchemaGenerateRunner` auf echte Split-Ausgabe umstellen
- Dateinamenslogik fuer:
  - `.pre-data.sql`
  - `.post-data.sql`
- `SchemaGenerateHelpers.formatJsonOutput(...)` fuer Split-JSON
  erweitern
- `TransformationReportWriter` fuer Split-Report erweitern
- phasenattribuierte `notes` und `skipped_objects` in JSON und Report
  nachziehen
- byte- bzw. semantikstabile Rueckwaertskompatibilitaet fuer den
  Single-Fall absichern

### 3.2 Bewusst nicht Teil von 6.4

- fachliche Generator-Zuordnung selbst
- neue Split-Validierung oder neue CLI-Flags
- Rollback-Split
- weitere Outputformate ausser Text/JSON/Report

Praezisierung:

6.4 loest "wie sieht der Split sichtbar aus?", nicht "welche Objekte
gehoeren fachlich in welche Phase?".

---

## 4. Leitentscheidungen

### 4.1 SQL-Split schreibt zwei benannte Artefakte

Verbindliche Folge:

- `--split pre-post --output out/schema.sql` erzeugt:
  - `out/schema.pre-data.sql`
  - `out/schema.post-data.sql`
- das urspruengliche `out/schema.sql` wird im Split-Fall nicht
  geschrieben
- der Report bleibt ein einzelnes Sidecar-Artefakt

### 4.2 JSON bleibt im Single-Fall rueckwaertskompatibel

Verbindliche Folge:

- im Single-Fall bleibt `ddl` ein String
- `split_mode`, `ddl_parts` und `phase` fehlen im Single-Fall komplett
- bestehende JSON-Consumer des Single-Falls sehen keinen Typwechsel

### 4.3 Split-JSON ist ein eigener Vertrag, kein Legacy-Fallback

Verbindliche Folge:

- im Split-Fall entfaellt `ddl`
- stattdessen erscheinen:
  - `split_mode`
  - `ddl_parts.pre_data`
  - `ddl_parts.post_data`
- `notes` und `skipped_objects` tragen im Split-Fall optional `phase`,
  wenn der Eintrag einer Phase zuordenbar ist
- globale Diagnosen ohne Phase bleiben sichtbar, aber ohne `phase`

### 4.4 Report und JSON muessen denselben Phasenbezug spiegeln

Verbindliche Folge:

- JSON und Report serialisieren dieselben inhaltlichen Diagnosen aus
  demselben `DdlResult`
- phasenbezogene Notes/Skips erscheinen mit derselben Phase in beiden
  Kanaelen
- globale Diagnosen erscheinen in beiden Kanaelen ohne Phase

### 4.5 Dateinamen sind aus dem bestehenden Outputpfad ableitbar

Verbindliche Folge:

- Split-Dateien werden aus dem bestehenden `--output`-Pfad abgeleitet,
  nicht ueber neue CLI-Flags
- die Ableitung folgt derselben Grundlogik wie heutige Sidecar- und
  Rollback-Pfade:
  - mit Extension: Extension ersetzen
  - ohne Extension: Suffix anhaengen

### 4.6 Single bleibt byte- bzw. semantikstabil

Verbindliche Folge:

- Text-Output im Single-Fall bleibt byte-nah wie heute
- JSON im Single-Fall bleibt semantisch identisch
- der Report im Single-Fall darf keinen Split-Metadaten-Ballast tragen

---

## 5. Konkrete Arbeitsschritte

### 5.1 Split-Dateipfade in `SchemaGenerateHelpers` einfuehren

- Helfer fuer:
  - `.pre-data.sql`
  - `.post-data.sql`
  definieren
- dieselbe Pfadlogik wie bei `sidecarPath(...)`/`rollbackPath(...)`
  anwenden
- Dateibenennung fuer:
  - `schema.sql` -> `schema.pre-data.sql`, `schema.post-data.sql`
  - `schema` -> `schema.pre-data`, `schema.post-data`
  absichern

### 5.2 `SchemaGenerateRunner` fuer echten Split-Output umstellen

- den bisherigen Zwischenzustand aus 6.2 beenden:
  - gueltige `pre-post`-Aufrufe duerfen nicht mehr durch den
    Single-Outputpfad laufen
- Dateiausgabe:
  - `renderPhase(PRE_DATA)` nach `*.pre-data.sql`
  - `renderPhase(POST_DATA)` nach `*.post-data.sql`
- JSON-Ausgabe:
  - Split-Branch ueber `formatJsonOutput(...)` mit Split-Vertrag
    ausgeben
- stdout-Textausgabe bleibt fuer `pre-post` weiterhin unzulaessig; der
  Preflight aus 6.2 deckt dies bereits ab

Wichtig:

- `render()` bleibt fuer den Single-Fall unangetastet
- `renderPhase(...)` ist im Split-Fall die einzige Quelle fuer die
  SQL-Artefakte

### 5.3 `formatJsonOutput(...)` auf Split-Vertrag erweitern

- Single-Fall beibehalten:
  - `ddl`
  - keine `split_mode`-/`ddl_parts`-Felder
- Split-Fall implementieren:
  - `split_mode: "pre-post"`
  - `ddl_parts.pre_data`
  - `ddl_parts.post_data`
- Diagnosefelder angleichen:
  - `notes` mit optional `phase` in Kebab-Case
  - `skipped_objects` mit optional `phase` in Kebab-Case
- `warnings` und `action_required` aus derselben Diagnosebasis wie heute
  weiterzaehlen

### 5.4 `TransformationReportWriter` fuer Split-Metadaten erweitern

- `split_mode` in den Report aufnehmen, aber nur im Split-Fall
- phasenattribuierte Notes und Skips mit optionalem `phase` serialisieren
- globale Diagnosen ohne Phase sichtbar lassen
- zusammenfassende Kennzahlen fuer beide Phasen ergaenzen, soweit sie
  aus `DdlResult` direkt bestimmbar sind

### 5.5 Rueckwaertskompatibilitaet und Contract-Tests absichern

Mindestens abzudecken:

- Single-Text bleibt unveraendert
- Single-JSON bleibt unveraendert
- Single-Report bleibt semantisch stabil
- Split-SQL schreibt zwei Dateien mit korrekten Suffixen
- Split-JSON enthaelt `split_mode` und `ddl_parts`, aber kein `ddl`
- Split-Report enthaelt `split_mode`
- `phase` erscheint nur dort, wo eine Diagnose einer Phase zuordenbar
  ist
- globale Diagnosen ohne Phase bleiben in JSON und Report sichtbar

---

## 6. Verifikation

Pflichtfaelle fuer 6.4:

- `schema generate` ohne `--split` bleibt fuer alle bestehenden
  Golden-Master-Faelle unveraendert
- `schema generate --split pre-post --output out/schema.sql` erzeugt:
  - `out/schema.pre-data.sql`
  - `out/schema.post-data.sql`
  - einen einzelnen Report mit `split_mode`
- `schema generate --split pre-post --output-format json` liefert:
  - `split_mode`
  - `ddl_parts`
  - phasenattribuierte `notes`
  - phasenattribuierte `skipped_objects`
- Split-JSON enthaelt kein Legacy-Feld `ddl`
- globale Diagnosen ohne Phasenbezug bleiben in JSON und Report
  sichtbar, aber ohne `phase`
- der `phase`-Wert in JSON verwendet Kebab-Case:
  - `pre-data`
  - `post-data`
- der Single-Fall bleibt fuer Text, JSON und Report byte- bzw.
  semantikstabil

Erwuenschte Zusatzfaelle:

- Golden Masters fuer Split-SQL-Dateien je Dialekt
- JSON-Contract-Smokes fuer externe Consumer
- Report-Snapshots fuer Split-Faelle mit gemischten phasenlosen und
  phasenbehafteten Diagnosen

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- `docs/cli-spec.md`
- zugehoerige Tests unter:
  - `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/...`
  - `adapters/driven/formats/src/test/...`

Wahrscheinlich indirekt mit betroffen:

- `docs/ImpPlan-0.9.2-6.2.md`, falls Zwischenverhalten in Doku-Referenzen
  angepasst werden muss
- `docs/ImpPlan-0.9.2-6.3.md`, falls neue Report-/JSON-Beispiele den
  dortigen Diagnosevertrag referenzieren
- Consumer-Snapshot-Tests, die heute noch implizit vom Single-JSON
  ausgehen

---

## 8. Risiken und Abgrenzung

### 8.1 JSON- und Report-Vertrag koennen bestehende Consumer brechen

Risiko:

- der Split erweitert den strukturierten Output sichtbar
- bestehende Consumer koennten im Split-Fall faelschlich weiter `ddl`
  erwarten

Mitigation:

- `ddl` nur im Single-Fall beibehalten
- `ddl_parts` nur im Split-Fall einfuehren
- Contract-Tests fuer JSON und Report ergaenzen

### 8.2 Dateinamenslogik kann ungewollte Artefakte erzeugen

Risiko:

- unklare oder inkonsistente Ableitung von `pre-data`/`post-data`
  fuehrt zu ueberraschenden Pfaden oder kollidierenden Dateinamen

Mitigation:

- Pfadableitung zentral in `SchemaGenerateHelpers` kapseln
- Dateinamenslogik mit und ohne Dateiendung explizit testen

### 8.3 Single-Rueckwaertskompatibilitaet kann unbemerkt kippen

Risiko:

- beim Einziehen der Split-Zweige werden bestehende Single-Pfade leicht
  mitveraendert

Mitigation:

- Single-Branches explizit separat halten
- bestaetigende Golden Masters und Contract-Tests fuer den Single-Fall
  weiterfuehren

### 8.4 Report und JSON koennen denselben Phasenbezug unterschiedlich deuten

Risiko:

- zwei verschiedene Serializer koennten dieselbe Diagnose
  unterschiedlich markieren oder globale Diagnosen ungleich behandeln

Mitigation:

- beide Kanaele strikt aus demselben `DdlResult` ableiten
- phasenlose Diagnosen in beiden Kanaelen gleich ohne `phase`
  behandeln

