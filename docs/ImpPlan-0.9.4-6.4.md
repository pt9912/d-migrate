# Implementierungsplan: 0.9.4 - Arbeitspaket 6.4 `Compare-Stabilisierung`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.4 (`Phase E1: Compare-Stabilisierung`)
> **Status**: Draft (2026-04-21)
> **Referenz**: `docs/implementation-plan-0.9.4.md` Abschnitt 4.4,
> Abschnitt 5.6, Abschnitt 6.4, Abschnitt 6.6, Abschnitt 7 und
> Abschnitt 9;
> `docs/ImpPlan-0.9.4-6.1.md`;
> `docs/ImpPlan-0.9.4-6.2.md`;
> `docs/ImpPlan-0.9.4-6.3.md`;
> `docs/cli-spec.md`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ResolvedSchemaOperand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareProjection.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererPlain.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererJson.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererYaml.kt`;
> `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunnerTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaCompareTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.4 stabilisiert `schema compare` fuer den in 6.1 bis 6.3
aufgebauten MySQL-Sequence-Reverse-Pfad. E1 baut auf dem bereits
normalisierten Neutralmodell auf und zieht Compare-Logik,
Exit-Semantik und strukturierte Ausgabe so nach, dass intakte
Sequence-Emulation diff-stabil bleibt und degradierte Reverse-Notes
(`W116`) sichtbar sind, ohne Compare in eine neue Vergleichsart zu
verwandeln.

6.4 liefert vier konkrete Ergebnisse:

- `schema compare` bleibt fuer neutral vs. reverse bei intakter
  MySQL-Sequence-Emulation diff-frei
- operandseitige Reverse-Notes wie `W116` bleiben sichtbare Diagnose,
  ohne selbst Diff-Eintraege oder Exit-1 ausgeloest zu haben
- `SchemaCompareRunner` traegt operandseitige Notes und
  `skippedObjects` konsistent in den Compare-Dokumentvertrag
- Plain-, JSON- und YAML-Ausgabe behandeln operandseitige Diagnose
  konsistent und maschinenlesbar

Nach 6.4 soll klar gelten:

- Compare bewertet weiterhin nur das neutrale Schema, nicht die
  MySQL-Supportobjekte direkt
- `W116` bleibt ein Begleitsignal des jeweiligen Operanden
- file-vs-file, file-vs-db und db-vs-db behalten dieselbe
  Exit-Semantik
- strukturierte Compare-Ausgabe verliert operandseitige Reverse-Notes
  nicht mehr gegenueber Plain-`stderr`

---

## 2. Ausgangslage

Der Compare-Unterbau ist fuer 0.9.4 bereits weit vorbereitet:

- `SchemaComparator` vergleicht schon heute `schema.sequences`; 
  `DefaultValue.SequenceNextVal(...)` wird ueber den
  Table-/Column-Vergleichspfad auf Neutralmodell-Ebene mitabgedeckt
- `ResolvedSchemaOperand` und `OperandInfo` koennen Validation,
  Reverse-Notes und `skippedObjects` bereits transportieren
- `SchemaCompareRunner` fuehrt operandseitige Reverse-Notes in Plain
  bereits auf `stderr` aus
- das Compare-Dokument in `SchemaCompareProjection.kt`
  (`SchemaCompareDocument`) enthaelt schon `sourceOperand` und
  `targetOperand`

Was nach D1 bis D3 fuer E1 noch fehlt:

- explizite Compare-Abdeckung fuer MySQL-sequence-emulierte Reverse-
  Faelle
- haerte Absicherung, dass operandseitiges `W116` keinen Compare-Exit
  kippt
- strukturierte Ausgabe von operandseitigen Notes und
  `skippedObjects` in JSON/YAML
- ein sauberer Testvertrag fuer file-vs-file, file-vs-db und db-vs-db
  unter degradierten Operanden

Aktueller Scaffold-Gap vor E1:

- `SchemaCompareRunner` baut die Compare-`validation` heute bereits
  korrekt nur aus echten Validation-Warnungen; dieser Ist-Zustand ist
  in E1 zu halten und testseitig abzusichern, nicht zu aendern
- `SchemaCompareProjection` hat den noetigen Operand-Slot bereits, aber
  der Rendervertrag fuer JSON/YAML bildet `sourceOperand.notes` und
  `targetOperand.notes` noch nicht ab
- `CompareRendererJson` und `CompareRendererYaml` serialisieren heute
  `validation` und `diff`, aber noch keine operandseitige Diagnose
- operandseitige Sichtbarkeit verteilt sich aktuell auf zwei getrennte
  Pfade:
  - Runner-seitige `stderr`-Emission nur im Plain-Format
  - Plain-Renderer-Darstellung im Dokument selbst
- E1 muss daher explizit zwischen Runner-Sichtbarkeit und
  Renderer-/Dokumentvertrag unterscheiden; die eigentliche Luecke liegt
  in JSON/YAML, nicht im bereits vorhandenen Plain-Pfad an sich

Konsequenz ohne 6.4:

- intakte Sequence-Reverse-Pfade koennen zwar fachlich diff-frei sein,
  aber degradierte Operanden bleiben im strukturierten Compare-Output
  unterbestimmt
- CI-/Automationspfade muessen Plain-`stderr` parsen, um `W116`
  operandseitig zu sehen
- da `W116` in `cli-spec.md` fuer 0.9.4 noch als reserviert gefuehrt
  ist, braucht E1 fuer fruehe Tests und Runner-Absicherung entweder:
  - bereits umgesetzte W116-Emission aus D1 bis D3
  - oder synthetische Compare-Operanden mit Notes, bis D1 bis D3 den
    finalen Reverse-Pfad liefern

---

## 3. Scope fuer 6.4

### 3.1 In Scope

- E1 setzt D1 bis D3 voraus und nutzt deren:
  - operandseitige `SchemaReadNote`s
  - `W116`-Diagnostik
  - normalisierte `schema.sequences`
  - normalisierte `SequenceNextVal`-Defaults
- compare-seitige Absicherung fuer:
  - file-vs-file
  - file-vs-db
  - db-vs-db
- Verifikation, ob `SchemaComparator` unveraendert ausreicht
- nur falls noetig: minimale compare-seitige Anpassungen auf
  Neutralmodell-Ebene
- harter Exit-Vertrag fuer operandseitiges `W116`
- strukturierte Compare-Ausgabe von:
  - operandseitigen Notes
  - operandseitiger Validation
  - operandseitigen `skippedObjects`
- Unit- und CLI-Tests fuer Diff-, Exit- und Renderer-Verhalten

### 3.2 Bewusst nicht Teil von 6.4

- neue Reverse- oder Compare-Warncodes
- MySQL-spezifische Stringfilter im Comparator
- neue oeffentliche Compare-Flags nur fuer `W116`
- Doku-/Ledger-Nachzug aus Phase E2
- Reparatur oder Normalisierung degradierter Reverse-Operanden im
  Compare selbst

Praezisierung:

6.4 loest "wie bleibt Compare auf dem bereits normalisierten
Neutralmodell stabil und sichtbar?", nicht "wie repariert Compare
unsaubere Reverse-Ergebnisse nachtraeglich?".

---

## 4. Leitentscheidungen

### 4.1 Compare bleibt neutralmodellbasiert

Verbindliche Folge:

- E1 fuehrt keinen MySQL-Spezialvergleich auf Basis von Supportobjekt-
  Namen oder Triggertexten ein
- `SchemaComparator` bleibt primaer auf `SchemaDefinition`,
  `SequenceDefinition` und `DefaultValue` ausgerichtet
- wenn E1 Anpassungen braucht, dann nur auf bereits normalisierten
  Neutralmodell-Strukturen

### 4.2 Operandseitiges `W116` ist Diagnose, kein Diff

Verbindliche Folge:

- `W116` wird in Compare nicht zu einer eigenen Vergleichsart
- operandseitige Reverse-Notes bleiben am jeweiligen Operand sichtbar
- `W116` erzeugt allein:
  - keinen Diff-Eintrag
  - keinen Exit 1
  - keinen Exit 3, 4 oder 7

### 4.3 Exit-Codes bleiben streng von Invaliditaet und echtem Diff bestimmt

Verbindliche Folge:

- file-vs-file, file-vs-db und db-vs-db behalten in E1 denselben
  Exit-Vertrag
- Exit 3 bleibt Validation-Fehler vorbehalten
- Exit 4 und 7 bleiben Lade-/Verbindungs- bzw. IO-Fehlern
  vorbehalten
- Exit 1 entsteht nur aus einem echten Compare-Diff
- Exit 0 bleibt auch dann korrekt, wenn ein Operand `W116` traegt, aber
  kein Diff vorliegt

### 4.4 Plain ist nur Darstellungsform, nicht alleiniger Diagnosetraeger

Verbindliche Folge:

- Plain-`stderr` bleibt fuer interaktive Nutzung erhalten
- JSON/YAML muessen operandseitige Notes und `skippedObjects` aber
  genauso im Dokument tragen
- es gibt keinen Sondervertrag "Notes nur in Plain"

### 4.5 Operandvertrag wird nicht gespalten

Verbindliche Folge:

- `sourceOperand` und `targetOperand` bleiben die einzige strukturierte
  Heimat fuer operandseitige Notes und `skippedObjects`
- Compare-`validation` bleibt auf Schema-Validation beschraenkt
- Reverse-Notes werden nicht kuenstlich in Validation-Warnungen
  umetikettiert

---

## 5. Zielarchitektur fuer 6.4

### 5.1 E1-Pipeline im Compare-Pfad

Nach der Operand-Aufloesung und Normalisierung fuehrt
`SchemaCompareRunner.run(...)` fuer E1 logisch diese Schritte aus:

1. beide Operanden inklusive Validation, Notes und `skippedObjects`
   aufloesen
2. Validation-Fehler unveraendert als Exit 3 behandeln
3. operandseitige Reverse-Notes in Plain weiter sichtbar halten:
   - sofortige Runner-Emission auf `stderr`
   - zusaetzliche Sichtbarkeit im Plain-Dokument des Renderers
4. Compare auf dem normalisierten Neutralmodell ausfuehren
5. `SchemaCompareDocument` mit:
   - `summary`
   - `diff`
   - `validation`
   - `sourceOperand`
   - `targetOperand`
   bauen
6. JSON/YAML/Plain aus demselben Dokumentvertrag rendern

Architektur-Verortung:

- E1a lebt primaer in `SchemaCompareRunner` und den zugehoerigen Tests
- E1b lebt im Dokumentvertrag `SchemaCompareDocument` in
  `SchemaCompareProjection.kt` und in den Compare-Renderern
- `SchemaComparator` bleibt dabei moeglichst unangetastet; wenn E1 dort
  eingreift, dann nur minimal und neutralmodellbasiert

### 5.2 Compare-Semantik fuer sequence-emulierte MySQL-Reverse-Pfade

Fuer E1 gilt:

- intakter Reverse aus D2/D3 muss gegen das urspruengliche neutrale
  Schema diff-frei sein
- geaenderte Sequence-Metadaten muessen als echte
  `sequencesChanged`-Deltas sichtbar werden
- fehlende Default-Rekonstruktion oder degradierte Triggerpfade duerfen
  nur dann Diff erzeugen, wenn das neutrale Schema dadurch fachlich
  wirklich anders ist
- Supportobjekt-Rauschen aus MySQL darf nach D2/D3 im Compare nicht
  mehr als zusaetzliche Tabellen-/Funktionen-/Trigger-Aenderung
  auftauchen

### 5.3 Exit- und Sichtbarkeitsvertrag fuer operandseitige Notes

Verbindlicher E1-Vertrag:

- `sourceOperand.notes` und `targetOperand.notes` werden immer im
  Dokument carried, wenn sie vorhanden sind
- `validation` bleibt davon getrennt und enthaelt nur echte
  Validation-Ergebnisse
- operandseitige Notes werden in Plain weiterhin sofort sichtbar
  ausgegeben
- operandseitige Notes beeinflussen den Compare-Exit nicht direkt

Designentscheidung fuer E1:

- Runner-seitige `stderr`-Emission bleibt bewusst plain-only
- bei `--output-format json` oder `yaml` traegt das strukturierte
  Dokument die Operanddiagnose allein; es gibt dort keine zusaetzliche
  stderr-Doppelung

Praktische Folge:

- ein Compare mit operandseitigem `W116`, aber leerem Diff, endet mit
  Exit 0
- ein Compare mit operandseitigem `W116` und echtem Diff endet mit
  Exit 1
- ein Compare mit Validation-Fehler bleibt Exit 3, auch wenn
  operandseitige Notes zusaetzlich vorhanden sind

### 5.4 Renderer- und Dokumentvertrag

E1 zieht den strukturierten Compare-Vertrag explizit nach:

- `SchemaCompareDocument` bleibt die gemeinsame Quelle fuer alle
  Renderer
- JSON/YAML muessen mindestens serialisieren:
  - `sourceOperand.reference`
  - `sourceOperand.notes`
  - `sourceOperand.skippedObjects`
  - `targetOperand.reference`
  - `targetOperand.notes`
  - `targetOperand.skippedObjects`
- Plain darf zusaetzlich eine komfortable, menschenlesbare Darstellung
  liefern, aber keine exklusiven Informationen tragen

Praezisierung:

- die Operand-Huellen existieren bereits; E1 zieht vor allem die
  Renderer und den Dokumentationsvertrag nach
- `skippedObjects` und operandseitige Notes werden dabei gleichrangig
  behandelt, nicht getrennt ueber Sonderpfade

### 5.5 Minimalprinzip fuer Comparator-Aenderungen

Bevor E1 `SchemaComparator` aendert, gilt:

- erst den Reverse-Pfad aus D1 bis D3 als eigentliche Ursache
  betrachten
- dann pruefen, ob der bestehende Comparator mit dem normalisierten
  Modell bereits korrekt diffed
- nur wenn danach noch ein fachlicher Compare-Spalt bleibt, ist eine
  kleine neutralmodellbasierte Korrektur zulaessig

Damit wird vermieden, dass E1 Reverse-Defizite per Compare-Hack
kaschiert.

---

## 6. Konkrete Arbeitsschritte

### E1-0 Compare-Scaffold und Ist-Vertrag verifizieren

- bestehenden Compare-Pfad gegen den aktuellen Codezustand abgleichen:
  - `SchemaCompareRunner`
  - `SchemaCompareProjection`
  - Plain-/JSON-/YAML-Renderer
  - Runner-/CLI-Tests
- bestaetigen, welche Teile des Operandvertrags bereits vorhanden sind
  und welche nur im Renderer fehlen

Done-Kriterien fuer E1-0:

- Compare-Ist-Stand fuer Validation, Notes, `skippedObjects` und
  Exit-Codes ist dokumentiert
- offene E1a-/E1b-Luecken sind als konkrete Codepfade benannt
- fuer E1 ist entschieden, ob Tests bereits auf echte D1-D3-`W116`-
  Emission aufsetzen koennen oder voruebergehend mit synthetischen
  Operand-Notes arbeiten muessen

### E1a Compare-Semantik absichern

- pruefen, ob `SchemaComparator` unveraendert diff-stabil bleibt
- falls noetig nur minimale, neutralmodellbasierte Anpassung vornehmen
- `SchemaCompareRunner` explizit gegen operandseitiges `W116`
  absichern, damit Exit-Codes allein von Validation oder echtem Diff
  abhaengen
- explizit verifizieren, dass der Bau von `CompareValidation`
  weiterhin ausschliesslich aus `sourceNormalized.validation` und
  `targetNormalized.validation` erfolgt, auch wenn Operand-Notes
  vorhanden sind

Done-Kriterien fuer E1a:

- es ist entschieden und belegt, ob `SchemaComparator` unveraendert
  ausreicht
- ein eventuell noetiger Compare-Eingriff bleibt minimal und
  neutralmodellbasiert
- der semantische Vertrag "operandseitiges `W116` ist Diagnose, kein
  Diff" ist im Runner-Code explizit abgesichert
- der Bau von `CompareValidation` ist explizit gegen das Einschleusen
  von Reverse-Notes abgesichert

### E1b Renderer- und Output-Nachzug umsetzen

- JSON/YAML-Renderer um `sourceOperand` und `targetOperand`
  nachziehen
- operandseitige Notes und `skippedObjects` in den strukturierten
  Rendervertrag aufnehmen
- sicherstellen, dass Plain-/JSON-/YAML-Ausgabe dieselbe fachliche
  Operanddiagnose zeigen

Done-Kriterien fuer E1b:

- JSON und YAML serialisieren operandseitige Notes und
  `skippedObjects`
- Plain behaelt seine bestehende Sichtbarkeit als Regression Guard
- Renderer transportieren keine operandseitigen Informationen mehr nur
  implizit ueber Plain-`stderr`

### E1c Exit-Code-Vertrag testseitig festziehen

- explizite Runner- und CLI-Tests fuer:
  - Exit 0 bei operandseitigem `W116` ohne Diff
  - Exit 1 bei operandseitigem `W116` mit echtem Diff
  - Exit 3 nur bei Validation-Fehler
  - Exit 4/7 weiterhin nur bei Lese-/Verbindungs- oder IO-Fehlern

Done-Kriterien fuer E1c:

- Exit-0/1/3/4/7-Verhalten ist fuer operandseitige Diagnosefaelle
  explizit getestet
- file-vs-file, file-vs-db und db-vs-db folgen demselben
  Exit-Grundvertrag

### E1d Compare-Dokumentvertrag stabilisieren

- `SchemaCompareProjection` und Renderer auf denselben
  Minimalvertrag festziehen
- dokumentieren, welche Felder fuer maschinenlesbare Compare-Ausgabe
  stabil sind
- keine operandseitigen Notes in `validation` verschieben

Done-Kriterien fuer E1d:

- `SchemaCompareDocument`, Renderpfade und Tests verwenden denselben
  Operandvertrag
- `validation` bleibt validation-only
- `sourceOperand`/`targetOperand` sind als stabile maschinenlesbare
  Diagnosefelder festgezogen

### E1e Tests nachziehen

- `SchemaCompareRunnerTest` fuer operandseitiges `W116`,
  Sequence-Diffs und Exit-Code-Faelle erweitern
- `CliSchemaCompareTest` fuer Plain-, JSON- und YAML-Sichtbarkeit von
  operandseitigen Notes und `skippedObjects` erweitern
- falls notwendig Integrationstest fuer neutral -> MySQL -> reverse ->
  compare als E1-Abschluss mitziehen

Done-Kriterien fuer E1e:

- Runner-Tests decken Compare-Semantik und Exit-Code-Vertrag ab
- CLI-Tests decken Plain/JSON/YAML-Sichtbarkeit ab
- mindestens ein sequence-emulierter End-to-End-Fall ist als
  Regression Guard verankert, falls E1 ihn benoetigt

Abhaengigkeiten:

- `E1-0` vor `E1a` bis `E1d`
- `E1a` vor `E1c`
- `E1b` vor `E1d`
- `E1c` und `E1d` vor `E1e`
- `E1e` laeuft inkrementell mit, ist aber Abschluss-Gate fuer 6.4

---

## 7. Verifikation

Pflichtfaelle fuer 6.4:

1. Neutral vs. reverse ist fuer intakte MySQL-Sequence-Emulation
   diff-frei.
2. Geaenderte Sequence-Metadaten fuehren zu genau
   `sequencesChanged`.
3. Hilfsobjekt-Rauschen taucht nach D2/D3 im Compare nicht mehr als
   Tabellen-, Funktions- oder Trigger-Diff auf.
4. Operandseitiges `W116` bleibt sichtbar, ohne selbst einen
   Diff-Eintrag zu erzeugen.
5. file-vs-db mit operandseitigem `W116`, aber ohne fachlichen Diff,
   endet mit Exit 0.
6. file-vs-db mit operandseitigem `W116` und echtem Diff endet mit
   Exit 1.
7. db-vs-db mit operandseitigem `W116` auf nur einer Seite folgt
   weiterhin ausschliesslich dem realen Diff.
8. file-vs-file mit operandseitigem `W116` auf beiden Seiten, aber
   identischem neutralem Modell, bleibt Exit 0 und diff-frei.
9. Validation-Fehler bleiben Exit 3, auch wenn operandseitige Notes
   zusaetzlich vorhanden sind.
10. Plain zeigt operandseitige Notes und `skippedObjects` weiterhin
   sichtbar an.
   Regression Guard: dieser Pfad ist bereits vorhanden und darf durch
   E1 nicht regressieren.
11. JSON enthaelt `sourceOperand` und `targetOperand` inklusive Notes
    und `skippedObjects`.
12. YAML enthaelt `sourceOperand` und `targetOperand` inklusive Notes
    und `skippedObjects`.
13. Compare-`validation` enthaelt nur echte Validation-Ergebnisse, nicht
    operandseitige Reverse-Notes.
14. Ein Compare mit operandseitigem `W116`, aber identischem neutralem
    Modell, bleibt diff-frei.

Akzeptanzkriterium fuer 6.4:

- Compare bleibt fuer sequence-emulierte MySQL-Reverse-Pfade fachlich
  diff-stabil, exit-stabil und in Plain/JSON/YAML gleichermassen
  diagnosefaehig.

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareProjection.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererJson.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererYaml.kt`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaCompareTest.kt`

Als fachliche Zieltypen bzw. konsumierte Vertragsanbieter relevant,
aber voraussichtlich nicht direkt zu aendern:

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/CompareOperandResolver.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ResolvedSchemaOperand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererPlain.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`

Bewusst noch nicht direkt produktiv betroffen:

- MySQL-Reader-Implementierung aus D1 bis D3
- Doku-/Ledger-Dateien aus E2

---

## 9. Risiken und Abgrenzung

### 9.1 Reverse-Probleme duerfen nicht im Compare kaschiert werden

Risiko:

- E1 koennte versucht sein, noch unsaubere Reverse-Ergebnisse durch
  Compare-Sonderlogik zu maskieren

Gegenmassnahme:

- Comparator-Aenderungen nur minimal und neutralmodellbasiert
- D1 bis D3 bleiben die primaere Ursache fuer Reverse-Stabilitaet

### 9.2 JSON/YAML bleiben hinter Plain zurueck

Risiko:

- E1b koennte die bestehende JSON/YAML-Luecke nicht vollstaendig
  schliessen, obwohl Plain bereits mehr Sichtbarkeit bietet
- Automationspfade verlieren Diagnoseinformationen

Gegenmassnahme:

- `sourceOperand`/`targetOperand` als verbindlicher strukturierter
  Outputvertrag
- CLI-Tests fuer JSON und YAML verpflichtend

### 9.3 Exit-Semantik driftet zwischen Compare-Pfaden

Risiko:

- file-vs-file, file-vs-db und db-vs-db behandeln operandseitiges
  `W116` unterschiedlich

Gegenmassnahme:

- explizite Exit-Code-Tests fuer alle drei Pfade
- `W116` als operandseitige Diagnose vertraglich von Exit-Entscheid
  trennen

### 9.4 Validation und Reverse-Notes werden vermischt

Risiko:

- operandseitige Reverse-Notes landen in `validation`
- Compare-Dokument verliert semantische Trennschaerfe

Gegenmassnahme:

- `CompareValidation` bleibt Validation-only
- Reverse-Notes bleiben in `sourceOperand`/`targetOperand`

### 9.5 Compare-Testabdeckung bleibt zu schmal

Risiko:

- E1 prueft nur den Happy-Path und verfehlt degradierte Operanden oder
  strukturierte Ausgabeformate

Gegenmassnahme:

- Runner- und CLI-Tests fuer Plain, JSON, YAML und Exit-Codes
- mindestens ein End-to-End-Fall neutral -> MySQL -> reverse -> compare
