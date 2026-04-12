# Implementierungsplan: Phase B - Core-Diff-Engine

> **Milestone**: 0.5.0 - MVP-Release
> **Phase**: B (Core-Diff-Engine)
> **Status**: Draft
> **Referenz**: `implementation-plan-0.5.0.md` Abschnitt 4.2, Abschnitt 5
> Phase B, Abschnitt 7, Abschnitt 8, Abschnitt 9.1

---

## 1. Ziel

Phase B liefert den fachlichen Kern fuer `schema compare`: ein stabiles,
serialisierbares und deterministisches Diff-Modell im Core-Layer.

Der Teilplan umfasst bewusst **nicht** die CLI, Dateilesen oder
Ausgabeformatierung. Das Ergebnis von Phase B ist ein Comparator, den Phase C
ohne weitere fachliche Interpretation fuer:

- Plain-Text-Ausgabe,
- JSON/YAML-Ausgabe,
- Exit `0` vs. `1`

verwenden kann.

Wichtiger Leitgedanke aus dem Masterplan:

- Vergleich auf dem **neutralen Modell**, nicht auf erzeugtem DDL
- kein Vorgriff auf spaetere Migrations-Operationen
- deterministische Ergebnisse ohne Reihenfolgenrauschen

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- In `hexagon:core` existiert das neutrale Modell (`SchemaDefinition`,
  `TableDefinition`, `ColumnDefinition`, `IndexDefinition`,
  `ConstraintDefinition`, `ViewDefinition`, ...).
- Es gibt aktuell **kein** Diff-Package, keinen `SchemaComparator` und keine
  `SchemaDiff`-Strukturen.
- `SchemaDefinition` ist stark map-/listenbasiert:
  - Top-Level-Maps fuer `tables`, `views`, `functions`, `procedures`,
    `triggers`, `sequences`, `customTypes`
  - Tabellen enthalten Maps fuer `columns` und Listen fuer `indices`,
    `constraints`, `primaryKey`
- Die YAML-Fixtures `minimal.yaml`, `e-commerce.yaml`, `all-types.yaml` und
  `full-featured.yaml` existieren bereits in
  `adapters/driven/formats/src/test/resources/fixtures/schemas/`.

Konsequenz fuer Phase B:

- Das Diff-Modell muss die vorhandenen Core-Typen direkt verwenden koennen.
- Reihenfolgeartefakte aus YAML-/Map-/Listen-Pfaden muessen vor dem Vergleich
  abgefangen werden.
- Phase B darf kein CLI- oder Adapterwissen in `hexagon:core` einziehen.

---

## 3. Scope fuer Phase B

### 3.1 In Scope

Der MVP-Vergleich fuer 0.5.0 umfasst:

- Schema-Metadaten:
  - `name`
  - `version`
- Tabellen:
  - hinzugefuegt
  - entfernt
  - geaendert
- Spalten:
  - hinzugefuegt
  - entfernt
  - geaendert
- Primary Keys
- Foreign Keys:
  - spaltenbasierte `references`
  - tabellenbasierte `ConstraintType.FOREIGN_KEY`
- Unique-Regeln:
  - `ColumnDefinition.unique`
  - `ConstraintType.UNIQUE`
- Indizes
- `custom_types` vom Typ `enum`
- Views, soweit sie im neutralen Modell bzw. YAML-Pfad bereits aktiv genutzt
  werden

### 3.2 Bewusst nicht Teil von Phase B

Nicht in Phase B fuer 0.5.0:

- minimale oder optimale Change-Sets fuer spaetere Migrationen
- `ALTER TABLE`-Ableitung
- Destructive-Change-Safety-Bewertung
- DB-zu-DB- oder Datei-zu-DB-Vergleich
- CLI-Rendering
- Datei-I/O
- Vergleich von `procedures`, `functions`, `triggers`, `sequences`
- `custom_types` vom Typ `composite` oder `domain`

Pragmatische Einschraenkung fuer 0.5.0:

- Wenn eine Spalte `NeutralType.Enum(refType = ...)` verwendet, reicht der
  Vergleich der referenzierten Typ-ID allein **nicht** aus.
- Referenzierte `custom_types` vom Typ `enum` muessen als eigener
  Compare-Bestandteil sichtbar sein, damit Aenderungen an Enum-Werten nicht
  als "kein Diff" verloren gehen.

Ebenfalls nicht diff-relevant in 0.5.0:

- `schemaFormat`
- `description`
- `encoding`
- `locale`
- `TableDefinition.description`
- `TableDefinition.partitioning`
- `ConstraintType.CHECK`
- `ConstraintType.EXCLUDE`
- Hilfs-/Abhaengigkeitsmetadaten bei Views, sofern sie keine fachliche
  Aenderung der View-Definition selbst darstellen

---

## 4. Architekturentscheidungen

### 4.1 Hierarchisches Diff-Modell statt flacher Operationsliste

Phase B fuehrt kein flaches Event- oder Opcode-Modell ein. Fuer 0.5.0 ist ein
hierarchisches, serialisierbares Ergebnis robuster:

- `SchemaDiff`
- `TableDiff`
- `ColumnDiff`
- kleine Hilfstypen wie `ChangeType` und `ValueChange<T>`

Vorteile:

- leicht als JSON/YAML serialisierbar
- fuer Menschen und Scripting parallel nutzbar
- spaeter in Phase C ohne zusaetzliche Uebersetzung renderbar

### 4.2 "Changed" bedeutet fuer 0.5.0: vor/nach statt Operationsgraph

Geaenderte Knoten tragen im MVP **vorher/nachher**-Snapshots, nicht bereits
eine spaetere Migrationssprache.

Beispielrichtung:

```kotlin
data class ValueChange<T>(
    val before: T,
    val after: T,
)
```

Das ist fuer 0.5.0 bewusst ausreichend:

- stabil
- serialisierbar
- einfach testbar

Nicht Ziel ist eine spaetere `ALTER COLUMN TYPE`-/`DROP CONSTRAINT`-Logik
vorwegzunehmen.

### 4.3 Determinismus gewinnt vor "Originalreihenfolge"

Vergleich und Ergebnisdarstellung arbeiten gegen eine kanonische,
parserunabhaengige Reihenfolge.

Verbindliche Regeln:

- Tabellen nach Namen sortieren
- Spalten nach Namen sortieren
- Views nach Namen sortieren
- Indizes und Constraints ueber stabile Vergleichsschluessel sortieren
- reine YAML-Reihenfolgenunterschiede duerfen kein Diff erzeugen

Ausnahme:

- Die Reihenfolge **innerhalb** fachlich geordneter Listen bleibt relevant,
  wenn sie semantisch Bedeutung hat, insbesondere bei:
  - `primaryKey`
  - `index.columns`
  - `constraint.columns`

### 4.4 Enge semantische Normalisierung fuer Single-Column-UNIQUE/FK

Das neutrale Modell kennt mehrere Wege, aehnliche Semantik auszudruecken:

- `ColumnDefinition.unique`
- `ConstraintType.UNIQUE`
- spaltenbasierte `references`
- tabellenbasierte `ConstraintType.FOREIGN_KEY`

Fuer 0.5.0 ist ein rein modelltreuer Compare hier zu noisig. Deshalb gilt eine
bewusst enge Semantik-Normalisierung:

- `column.unique = true` ist aequivalent zu einem einspaltigen
  `UNIQUE`-Constraint auf genau dieser Spalte
- `column.references` ist aequivalent zu einem einspaltigen
  `FOREIGN_KEY`-Constraint auf genau dieser Spalte mit denselben
  Referenzdaten

Diese Normalisierung gilt **nur** fuer:

- single-column `UNIQUE`
- single-column `FOREIGN_KEY`

Nicht normalisiert werden in 0.5.0:

- Multi-Column-Constraints
- `CHECK`
- `EXCLUDE`
- sonstige modelluebergreifende Aehnlichkeitsheuristiken

Konsequenz:

- semantisch aequivalente Single-Column-Darstellungen erzeugen **kein** Diff
- nicht aequivalente oder mehrspaltige Darstellungen bleiben modellnah sichtbar

So bleibt das Ergebnis nah am bestehenden Modell und vermeidet schwer
nachvollziehbare Vollnormalisierung.

### 4.5 Vergleich gegen kanonische Vergleichsprojektionen statt Rohmodelle

Die aktuelle Core-Struktur enthaelt mehrere Felder, die fuer 0.5.0 explizit
nicht compare-relevant sind. Deshalb darf der Comparator nicht einfach rohe
`SchemaDefinition`-, `TableDefinition`- oder `ConstraintDefinition`-Snapshots
mit `before`/`after` durchreichen.

Verbindliche Regel:

- verglichen wird gegen kanonische **Vergleichsprojektionen**
- diese enthalten nur die fuer 0.5.0 freigegebenen Felder
- nicht compare-relevante Felder werden vor dem Vergleich explizit ausgeblendet

Mindestens noetige Projektionen:

- `SchemaMetadataComparable`: nur `name`, `version`
- `TableComparable`: nur Spalten, PK, normalisierte Single-Column-UNIQUE/FK,
  mehrspaltige `UNIQUE`/`FOREIGN_KEY`, Indizes
- `ViewComparable`: nur `materialized`, `refresh`, `query`, `sourceDialect`
- `EnumCustomTypeComparable`: nur `kind = enum` und `values`

Konsequenz:

- `description`, `partitioning`, `CHECK`, `EXCLUDE` und reine Hilfsmetadaten
  koennen nicht versehentlich ueber rohe Vorher/Nachher-Snapshots in ein Diff
  einsickern

### 4.6 Views im MVP: fachlich ja, aber mit enger Semantik

Views sind laut Masterplan im Scope, soweit sie bereits im YAML-Pfad genutzt
werden. Fuer 0.5.0 wird deshalb nur der fachlich relevante Kern verglichen:

- `materialized`
- `refresh`
- `query`
- `sourceDialect`, falls gesetzt

Nicht diff-ausloesend in Phase B:

- `description`
- rein dokumentarische Hilfsinformationen
- `dependencies`, sofern sie nur abgeleitete Metadaten sind und nicht die
  eigentliche View-Definition aendern

---

## 5. Betroffene Dateien und Module

### 5.1 Neue Produktionsdateien

Voraussichtlich neuer Package-Schnitt unter `hexagon:core`:

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaDiff.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableDiff.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/ColumnDiff.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/ChangeType.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/ValueChange.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`
- optional:
  - `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/EnumTypeDiff.kt`
- optional:
  - `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/Canonicalization.kt`

Ob mehrere Typen in weniger Dateien gebuendelt werden, ist ein
Implementierungsdetail. Verbindlich ist nur:

- keine Platzierung im CLI-Modul
- keine Abhaengigkeit auf Adapter- oder Treibercode

### 5.2 Neue Testdateien

Voraussichtlich:

- `hexagon/core/src/test/kotlin/dev/dmigrate/core/diff/SchemaComparatorTest.kt`
- optional ein fixture-basierter Compare-Test in einem testnahen hoeheren Modul,
  falls `hexagon:core:test` keine `YamlSchemaCodec`-Abhaengigkeit erhalten soll
- optional weitere Tests fuer Kanonisierung / Signaturen

---

## 6. Diff-Modell

### 6.1 Zielstruktur

Empfohlene Richtung fuer das Ergebnis:

```kotlin
data class SchemaDiff(
    val schemaMetadata: SchemaMetadataDiff? = null,
    val tablesAdded: List<NamedTable> = emptyList(),
    val tablesRemoved: List<NamedTable> = emptyList(),
    val tablesChanged: List<TableDiff> = emptyList(),
    val enumTypesAdded: List<NamedEnumType> = emptyList(),
    val enumTypesRemoved: List<NamedEnumType> = emptyList(),
    val enumTypesChanged: List<EnumTypeDiff> = emptyList(),
    val viewsAdded: List<NamedView> = emptyList(),
    val viewsRemoved: List<NamedView> = emptyList(),
    val viewsChanged: List<ViewDiff> = emptyList(),
) {
    fun isEmpty(): Boolean = ...
}
```

Wichtige Eigenschaften:

- rein aus Core-Typen oder kleinen diff-eigenen DTOs aufgebaut
- direkt serialisierbar
- keine Formatter- oder Renderer-Methoden im Modell
- `custom_types` vom Typ `enum` sind als eigener Top-Level-Diff sichtbar

### 6.2 TableDiff

`TableDiff` soll mindestens enthalten:

- Tabellenname
- hinzugefuegte Spalten
- entfernte Spalten
- geaenderte Spalten
- Aenderung des Primary Keys
- hinzugefuegte/entfernte/geaenderte Indizes
- hinzugefuegte/entfernte/geaenderte Unique-/FK-Constraints

Pragmatischer MVP-Schnitt:

- "geaendert" bedeutet hier jeweils `before`/`after` auf dem **vergleichbaren
  Teilmodell**, nicht als roher `TableDefinition`-Snapshot
- keine Unterteilung in "nullable changed", "default changed", "type changed"
  als eigene Operationscodes

### 6.3 ColumnDiff

`ColumnDiff` soll auf `ColumnDefinition` arbeiten und Aenderungen an mindestens
folgenden Aspekten sichtbar machen:

- `type`
- `required`
- `unique`
- `default`
- `references`

Wichtig:

- eine Aenderung an `references` ist eine fachliche FK-Aenderung und muss im
  Diff auftauchen, auch wenn keine `ConstraintType.FOREIGN_KEY`-Liste
  beteiligt ist

### 6.4 ChangeType

Ein kleiner Hilfstyp wie `ChangeType` ist sinnvoll fuer Renderer und JSON:

- `ADDED`
- `REMOVED`
- `CHANGED`

Fuer 0.5.0 wird bewusst **kein** groesseres Taxonomie-System eingefuehrt.

---

## 7. Vergleichsalgorithmus

### 7.1 Schema-Ebene

Verglichen werden nur:

- `SchemaDefinition.name`
- `SchemaDefinition.version`

Nicht verglichen werden in 0.5.0:

- `schemaFormat`
- `description`
- `encoding`
- `locale`

### 7.2 Enum-Custom-Types

Verglichen werden nur `custom_types` mit:

- `kind = enum`

Vergleichsschluessel:

- Enum-Typname

Vergleichsinhalt:

- `kind`
- `values`

Wichtig:

- die Reihenfolge der Enum-Werte bleibt relevant
- `description` am Custom Type ist fuer 0.5.0 nicht compare-relevant
- `composite` und `domain` bleiben ausserhalb des MVP-Scope

### 7.3 Tabellen

Vergleichsschluessel:

- Tabellenname

Algorithmus:

1. Linke und rechte Tabellenmap auf Namensmengen reduzieren
2. Added/Removed ueber Mengendifferenz bestimmen
3. Gemeinsame Tabellen einzeln vergleichen

### 7.4 Spalten

Vergleichsschluessel:

- Spaltenname innerhalb der Tabelle

Algorithmus:

1. Added/Removed ueber Spaltennamen
2. Gemeinsame Spalten ueber `ColumnDefinition`-Semantik vergleichen
3. Reihenfolge der `columns`-Map ignorieren

### 7.5 Primary Keys

Primary Keys werden als **geordnete** Liste verglichen.

Das heisst:

- `[id, tenant_id]` vs. `[tenant_id, id]` ist ein Diff
- fehlende oder zusaetzliche PK-Spalten sind ein Diff

### 7.6 Normalisierte Single-Column-UNIQUE/FK-Semantik

Vor dem Vergleich einer Tabelle wird ein kleiner semantischer Vergleichsraum
gebildet:

- `singleColumnUnique`
- `singleColumnForeignKeys`
- `multiColumnConstraints`

Regeln:

- `column.unique = true` und ein gleichwertiger einspaltiger
  `UNIQUE`-Constraint landen im selben `singleColumnUnique`-Raum
- `column.references` und ein gleichwertiger einspaltiger
  `FOREIGN_KEY`-Constraint landen im selben `singleColumnForeignKeys`-Raum
- Mehrspaltige Constraints bleiben in `multiColumnConstraints`

Damit meldet der Compare keine Unterschiede fuer bloess unterschiedlich
modellierte, aber fachlich gleiche Single-Column-UNIQUE/FK-Faelle.

### 7.7 Constraints

Nur `UNIQUE` und `FOREIGN_KEY` sind in 0.5.0 MVP-Scope.

Kanonische Vergleichsstrategie:

- Constraints werden ueber ihren Namen verglichen
- das passt zur aktuellen Core-Struktur, in der `ConstraintDefinition.name`
  verpflichtend ist
- Aenderungen am Payload eines gleich benannten Constraints werden als
  `changed` reportet

Wichtig:

- `CHECK` und `EXCLUDE` werden in Phase B nicht als eigener MVP-Vergleich
  ausgebaut
- sie duerfen deshalb weder versehentlich diff-ausloesend noch halb
  implementiert im Ergebnis landen

### 7.8 Indizes

Vergleichsstrategie analog zu Constraints:

- expliziter Name, falls vorhanden
- sonst Signatur aus:
  - `columns`
  - `type`
  - `unique`

Die Reihenfolge innerhalb `index.columns` bleibt relevant.

### 7.9 Views

Vergleich ueber:

- View-Name
- `materialized`
- `refresh`
- `query`
- `sourceDialect`

Die Reihenfolge in der `views`-Map ist irrelevant.

---

## 8. Teststrategie

### 8.1 Pflichtfaelle in `hexagon:core:test`

Mindestens folgende Tests muessen direkt fuer den Comparator existieren:

- identische Schemas -> leeres Diff
- Tabelle hinzugefuegt
- Tabelle entfernt
- Spalte hinzugefuegt
- Spalte entfernt
- Spalte geaendert
- Primary-Key-Aenderung
- Enum-Custom-Type geaendert
- spaltenbasierte FK-Aenderung ueber `references`
- `ConstraintType.UNIQUE` geaendert
- semantisch aequivalente Single-Column-UNIQUE-Darstellungen -> kein Diff
- semantisch aequivalente Single-Column-FK-Darstellungen -> kein Diff
- Index geaendert
- View geaendert
- rein kosmetische Reihenfolgeaenderung in Maps/Listen -> kein Diff, soweit die
  Reihenfolge semantisch nicht relevant ist

### 8.2 Zusatzfaelle fuer Determinismus

Explizit absichern:

- zwei logisch identische, aber unterschiedlich sortierte YAML-/Builder-Schemas
  liefern dasselbe leere Diff
- unnamed Index erzeugt eine stabile Signatur
- wiederholter Comparator-Lauf liefert dieselbe Ergebnisreihenfolge

### 8.3 Fixture-Nutzung

Die vorhandenen Fixtures `minimal.yaml` und `e-commerce.yaml` sind gemaess
Masterplan **bereits in Phase B** Pflichtbestandteil der Compare-Verifikation.

Pragmatische Regel:

- pure Core-Tests bevorzugen Builder-/Factory-Helfer
- fixture-basierte Compare-Tests mit `minimal.yaml` und `e-commerce.yaml`
  muessen ebenfalls in Phase B existieren
- falls `hexagon:core:test` adapterfrei bleiben soll, duerfen diese
  fixture-basierten Tests in einem hoeheren Testmodul liegen; fachlich gehoeren
  sie trotzdem zu Phase B und nicht erst zu Phase C

---

## 9. Akzeptanzkriterien fuer Phase B

- Ein `SchemaComparator` oder aequivalenter Core-Service existiert.
- Das Ergebnis ist ein serialisierbares Diff-Modell ohne CLI-Abhaengigkeit.
- Identische Schemas erzeugen ein leeres Diff.
- Reine Reihenfolgeaenderungen in Tabellen-/Spalten-Maps erzeugen kein Diff.
- Primary-Key-Reihenfolge bleibt semantisch relevant.
- Added/Removed/Changed fuer Tabellen und Spalten sind getrennt sichtbar.
- `custom_types` vom Typ `enum` sind added/removed/changed sichtbar.
- Aenderungen an Enum-Werten eines referenzierten `ref_type` gehen nicht als
  False Negative verloren.
- Semantisch aequivalente Single-Column-UNIQUE/FK-Darstellungen erzeugen kein
  Diff.
- FK-/Unique-/Index-Aenderungen sind deterministisch und stabil sortiert.
- Nicht compare-relevante Felder wie `description`, `partitioning`, `CHECK` und
  `EXCLUDE` erzeugen kein Diff.
- Fixture-basierte Compare-Verifikation mit `minimal.yaml` und
  `e-commerce.yaml` ist Teil von Phase B.
- Das Modell ist fuer Phase C direkt als Eingabe nutzbar; Phase C muss keinen
  fachlichen Vergleich neu erfinden.

---

## 10. Verifikation

Phase B wird mindestens ueber den gezielten Core-Testlauf verifiziert:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test --rerun-tasks" \
  -t d-migrate:0.5.0-phase-b .
```

Wenn Phase B mit Phase C gemeinsam geprueft wird, gilt zusaetzlich der
Milestone-Check aus `implementation-plan-0.5.0.md`:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :hexagon:application:test :adapters:driving:cli:test --rerun-tasks" \
  -t d-migrate:mvp-tests .
```

---

## 11. Risiken und Abgrenzungen

### 11.1 Zu tiefer Diff fuer 0.5.0

Die groesste Gefahr ist, Phase B bereits als Vorlaeufer fuer `schema migrate`
zu ueberfrachten. Das waere gegen den Masterplan.

Deshalb gilt:

- lieber stabiles `before/after`-Diff
- statt halbgarer Migrations-Operationssprache

### 11.2 Modellpfade mit semantischer Ueberlappung

`unique`, `UNIQUE`-Constraint und Indexe koennen sich fachlich nahekommen.
Phase B normalisiert deshalb nur den kleinen, klaren MVP-Bereich
single-column `UNIQUE`/FK. Darueber hinaus bleibt der Compare bewusst
zurueckhaltend und fuehrt keine breite Heuristik-Zusammenfuehrung ein.

### 11.3 `customTypes` nur teilweise im MVP

Der Review hat gezeigt: `enum`-`custom_types` muessen mit in den Compare, sonst
entstehen bei `ref_type` leicht False Negatives. Trotzdem bleibt der Schnitt
eng:

- `enum` in Scope
- `composite` und `domain` weiter ausserhalb des MVP

### 11.4 YAML-Fixtures vs. Core-Isolation

Fixture-basierte Tests sind hilfreich, duerfen aber den Core nicht unnoetig an
Adaptermodule koppeln. Falls noetig, werden Builder-basierte Tests in `core`
und fixture-basierte Compare-Tests in einem hoeheren Testmodul kombiniert. Die
Fixture-Abdeckung bleibt trotzdem eine Phase-B-Pflicht.
