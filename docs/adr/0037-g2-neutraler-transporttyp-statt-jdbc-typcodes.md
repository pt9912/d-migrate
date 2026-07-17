---
status: proposed
date: 2026-07-17
decision-makers: pt9912
consulted: docs/adr/0022-ports-jdbc-entkopplung.md, docs/adr/0028-a-check-architecture-gate-scope.md, docs/adr/0015-fulltext-tsvector-neutral-type.md, spec/architecture.md, spec/neutral-model-spec.md, docs/planning/open/g2-neutrales-typmodell-jdbc-typcodes.md
informed: hexagon/ports-common, hexagon/ports-write, hexagon/application, adapters/driven/driver-common, adapters/driven/driver-postgresql, adapters/driven/formats
---

# G2 — Port-Verträge tragen `NeutralType` statt JDBC-Typcodes

> **Status: proposed (2026-07-17).** Vorschlag zur Ratifizierung. Löst die in
> [ADR 0028](0028-a-check-architecture-gate-scope.md) als „G2" angekündigte und dort bewusst
> vertagte Entscheidung ein: `TargetColumn.jdbcType: Int` und `JdbcTypeHint.jdbcType: Int`
> werden durch `NeutralType` ersetzt, `JdbcTypeCodes` entfällt aus `hexagon:ports-common`.
> Damit erfüllt sich Entscheidung 1 aus [ADR 0022](0022-ports-jdbc-entkopplung.md) auch
> semantisch, nicht nur auf Importebene.

## Kontext und Problemstellung

[ADR 0022](0022-ports-jdbc-entkopplung.md) Entscheidung 1 lautet: „Die Ports-Schicht
(`hexagon:ports-common`, `-read`, `-write`, `-execute`) exponiert kein `java.sql` mehr. **JDBC
lebt ausschließlich in den Adaptern.**" [`spec/architecture.md`](../../spec/architecture.md)
führt dieselbe Regel als Zielbild.

Der Ist-Stand hält das auf Importebene ein und unterläuft es semantisch. Verifiziert 2026-07-17:

1. **Zwei Port-Verträge tragen JDBC-Typcodes.** `TargetColumn.jdbcType: Int`
   (`hexagon:ports-write`) und `JdbcTypeHint.jdbcType: Int` (`hexagon:ports-common`) transportieren
   `java.sql.Types`-Werte. Ein Import-/Schicht-Gate kann das prinzipiell nicht sehen — der
   Feldtyp ist `Int`.
2. **Die JDBC-Typcode-Tabelle wurde in die Ports-Schicht kopiert.** Der G1-Fix legte
   `JdbcTypeCodes` in `hexagon:ports-common` an — 30 Konstanten von `BIT = -7` bis
   `TIMESTAMP_WITH_TIMEZONE = 2014`. Sein eigener KDoc sagt: „intentionally **mirrors the numeric
   values of `java.sql.Types`** without importing `java.sql` into hexagon application code."
   Vorher importierte `hexagon:application` `java.sql.Types`; danach deklariert das Hexagon
   JDBCs Nummerierung als **eigenen Port-Vertrag**. Das Gate prüft Importe — Konstanten neu zu
   deklarieren macht es grün, ohne die Kopplung zu verringern. Die Technologie sitzt danach
   tiefer im Hexagon als zuvor, in genau einem der vier Module, die ADR 0022 namentlich nennt.
3. **`TargetColumn` gibt es selbst zu.** Sein KDoc: „Lebt in `hexagon:ports` (nicht in
   `hexagon:core`), weil `jdbcType` semantisch JDBC-coupled ist. `core.ColumnDescriptor` bleibt
   JDBC-frei." Die Ports-Schicht diente als Ausweichquartier für einen Typ, den `core` nicht
   haben durfte — obwohl ADR 0022 sie genauso JDBC-frei fordert.
4. **Das Zielbild wurde abgesenkt.** [`spec/architecture.md`](../../spec/architecture.md) trägt
   inzwischen die Ausnahme selbst: „`jdbcType: Int` bleibt **vorerst** eine eng begrenzte
   Interop-/Persistenz-Ausnahme … eine vollständige Typcode-Neutralisierung ist ein eigener
   **G2-Slice**." Ein Zielbild kennt kein „vorerst" und verweist nicht abwärts auf einen Plan.

[ADR 0028](0028-a-check-architecture-gate-scope.md) hat das nicht verschleiert — es nennt den Rest
ausdrücklich „begrenzte Ausnahme", verlangt Sichtbarkeit und kündigt G2 als eigenen Umbau an. Es
war eine **Reihenfolge**-Entscheidung (G1 zuerst), kein Freibrief. G1 ist erreicht; diese ADR löst
den angekündigten zweiten Schritt ein.

**Frage dieser ADR:** Was tritt an die Stelle des `Int` — und trägt es die Fälle, die der Code
heute über `Types.OTHER` + `sqlTypeName` auflöst?

## Entscheidungstreiber

- **ADR 0022 Entscheidung 1 soll gelten**, nicht nur import-technisch bestehen.
- **Keine Ersatz-Kopplung.** Ein Modell, das rohe Dialekt-Strings durchreicht, verschiebt das
  Problem von `Int` nach `String`. [ADR 0015](0015-fulltext-tsvector-neutral-type.md) ist die
  Präzedenz: PG-only-Typen werden **first class** modelliert, nicht durchgereicht.
- **Kein Verlust an Präzision.** Die Import-Kompatibilitätsprüfung und der PG-Dispatch
  (jsonb/uuid/enum/array) müssen exakt so treffsicher bleiben.
- **Die Asymmetrie auflösen.** Der Lesepfad ist bereits neutral, der Schreibpfad nicht.

## Ausgangslage, die den Umbau trägt (verifiziert)

- **`NeutralType` benennt bereits jeden kritischen Fall.** `Uuid`, `Json`, `Xml`, `Enum(values,
  refType)`, `Array(elementType)`, `Geometry(geometryType, srid)`, `FullText` — genau die Typen,
  die sich in PostgreSQL hinter `Types.OTHER` verstecken und heute nur über `sqlTypeName`
  auseinanderzuhalten sind. Es muss **kein neues Modell erfunden** werden.
- **Der Lesepfad ist längst neutral.** `JdbcChunkSequence` baut `ChunkSchema` mit `neutralType`;
  die dialekt-blinde Lücke (Geometrie) wird bereits über die Metadaten-Vorabfrage
  (`probedColumns`) geschlossen. Das Muster existiert und ist erprobt.
- **Der Parquet-Schreibvertrag ist neutral.** `ChunkSchemaToManifest` setzt `jdbcType = null` und
  schreibt nur `neutralType`; der Writer emittiert das Feld bedingt, also nie. **Kein von
  d-migrate geschriebenes Manifest enthält `jdbcType`** — es existiert nur read-tolerant. Die
  vermutete Format-Migration bestehender Bundles entfällt.
- **Die Konverter-Registry ist bereits am richtigen Ort.** `TypeConverterRegistry` liegt in
  `adapters/driven/formats` — einem Adapter, der `java.sql.Types` benutzen **darf**. Falsch ist
  nur, dass ihr Eingabetyp `JdbcTypeHint` ein Port-Typ ist, der den Code trägt.

## Betrachtete Optionen

- **A — Port-Verträge tragen `NeutralType`.** `TargetColumn` und `JdbcTypeHint` führen
  `neutralType: NeutralType` statt `jdbcType: Int`. Die **Dialekt-Adapter** erzeugen ihn (sie
  wissen, ob `OTHER` jsonb, uuid oder enum ist); JDBC bleibt adapterintern. `JdbcTypeCodes`
  entfällt aus `ports-common`.
- **B — Eigenes neutrales Transport-Enum** neben `NeutralType`, nur für den Datenpfad.
- **C — Status quo verlängern**, Ausnahme dauerhaft ratifizieren.

## Entscheidung

**Vorgeschlagen: Option A.**

`NeutralType` deckt den Bedarf nachweislich ab; ein zweites Modell (B) hätte keine Fälle, die A
nicht trägt, und erzeugte eine dauerhafte Abbildungspflicht zwischen zwei neutralen Modellen —
plus die Frage, welches bei Widerspruch gewinnt. Die Kompatibilitätsprüfung vergleicht dann
endlich Gleiches mit Gleichem (heute: `NeutralType` der Quelle gegen `jdbcType` des Ziels).

C ist die ehrlich benannte Alternative, aber sie hält eine Regel aufrecht, die das Repo an anderer
Stelle streng durchsetzt (`ports-jdbc-free-gate`, a-check, ADR 0022), und verlangt dauerhaft ein
abgesenktes Zielbild.

Die Mapping-Hoheit liegt bei den **Dialekt-Treibern**, nicht beim vorhandenen
`JdbcToNeutralTypeMapper`: der ist laut eigenem KDoc dialekt-blind und verlustbehaftet („Geometrie
wird NICHT hier erkannt", „unbekannte JDBC-Typen werden konservativ auf `NeutralType.Text`
gemappt"). Als gemeinsame Vorstufe darf er bleiben; die dialekt-spezifische Auflösung von `OTHER`
und Geometrie gehört in die Treiber, analog zum bereits erprobten `probedColumns`-Muster.

## Konsequenzen

**Positiv**

- ADR 0022 Entscheidung 1 gilt dann auch semantisch; `JdbcTypeCodes` verschwindet aus dem Hexagon.
- Die Ausnahme-Passage in [`spec/architecture.md`](../../spec/architecture.md) entfällt **ersatzlos**
  — samt der beiden Konventionsverstöße („vorerst", Abwärtsverweis auf einen Slice).
- `TargetColumn.srid: Int?` wird redundant: `NeutralType.Geometry` trägt die SRID bereits.
- Ein Folge-Fehler dieser Art fällt künftig auf: `TargetColumn` könnte dann nach `hexagon:core`
  wandern, weil der Grund für die Sonderstellung (JDBC-Kopplung) entfällt.

**Negativ — bewusst in Kauf genommen**

- **Die Kompatibilitätsmatrix muss neu begründet, nicht nur umgeschrieben werden.**
  `ImportTypeCompatibility` vergleicht heute zwei verschiedene Modelle; nach dem Umbau vergleicht
  sie zwei gleiche. Fälle, die heute nur zufällig durch die Asymmetrie funktionieren, werden
  sichtbar — und einige Prüfungen kollabieren womöglich zu Tautologien.
- **Große Testfläche.** ~22 Produktivdateien plus viele Fixtures konstruieren `TargetColumn` mit
  `jdbcType = Types.X`.
- **`sqlTypeName` braucht eine eigene Entscheidung.** Der PG-Import castet Enums über den
  Dialekt-Typnamen (`::mood`). Ob das Feld bleibt (adapterseitig legitim) oder in
  `NeutralType.Enum.refType` aufgeht, entscheidet der Slice.

**Neutral**

- Das Parquet-Dateiformat ändert sich nicht.

## Confirmation

- `make a-check` bleibt grün — **beweist aber nichts** für diese ADR (das war der Kern von G1).
- **Deshalb Pflicht:** ein Gate, das `jdbcType`/JDBC-Typcode-Konstanten in `hexagon/**` verbietet
  (Grep-/Fitness-Function analog `scripts/ports-jdbc-free-gate.sh`). Ohne dieses Gate wäre G2
  genauso „falsch-grün" wie G1 — nur eine Ebene höher.
- Die Ausnahme-Passage in `spec/architecture.md` ist entfernt.
- Cross-Dialect-Round-Trips (`make sample-db-cross-smoke`, `sample-db-sqlite-smoke`,
  `sample-db-spatial-smoke`) bleiben grün — sie decken jsonb/uuid/enum/array/Geometrie ab.

## Weitere Informationen

- [ADR 0022](0022-ports-jdbc-entkopplung.md) — die Regel, die hier eingelöst wird.
- [ADR 0028](0028-a-check-architecture-gate-scope.md) — G1-vor-G2; kündigt diesen Umbau an.
  Wird durch diese ADR **erfüllt, nicht ersetzt**.
- [ADR 0015](0015-fulltext-tsvector-neutral-type.md) — Präzedenz „PG-only-Typ first class statt
  Passthrough".
- [`neutral-model-spec.md`](../../spec/neutral-model-spec.md) — Vertrag des Neutralmodells.
- [Ticket](../planning/open/g2-neutrales-typmodell-jdbc-typcodes.md) — Ist-Aufnahme und Fläche.
