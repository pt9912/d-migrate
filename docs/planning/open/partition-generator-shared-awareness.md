# Partitions-Bewusstsein im gemeinsamen DDL-Generator (statt MySQL-Seitenkanal)

> **Status:** Vorabklärung (Trigger, 2026-06-25)
> **Trigger:** AP6-Review-Härtung (Befund #12, Altitude). Beim Cross-Dialect-Partitionierungs-Slice
> ([`../in-progress/cross-dialect-partitioning.md`](../in-progress/cross-dialect-partitioning.md))
> fiel auf, dass der MySQL-Generator das „ist-partitioniert"-Wissen über einen **mutierbaren
> Seitenkanal** trägt.
> **Bezug (Anforderung):** keine harte LF-Anforderung; reine interne Code-Altitude (Wartbarkeit,
> Dialekt-Konsistenz). Nicht RC-blockierend.

## Beleg

`MysqlDdlGenerator` hält ein `private val partitionedTables = mutableSetOf<String>()`, das während
`generateTable` befüllt und in mehreren Pfaden gelesen wird (FK-Carve-Out für FKs **auf** und **zu**
einer partitionierten Tabelle, beide Richtungen + zirkulärer ALTER). Das Set wird pro `generate`-Lauf
`clear()`-t. Konsequenzen:

- **Reihenfolge-Abhängigkeit:** Ein FK, der eine partitionierte Tabelle referenziert, wird nur korrekt
  als Carve-Out erkannt, wenn die referenzierte Tabelle **vorher** ihren Eintrag im Set gesetzt hat.
  Heute durch topologische/Emissions-Reihenfolge gedeckt, aber als impliziter Vertrag fragil.
- **Mutierbarer Instanz-Zustand** auf dem Generator (statt am Modell/an einer Analyse), der zwischen
  Läufen zurückgesetzt werden muss — ein klassischer Stale-State-Fußangel.
- **Dialekt-lokal:** Das Partitions-Bewusstsein lebt nur im MySQL-Treiber. PG/SQLite haben eigene
  Logik. Ein gemeinsames, **ordnungsunabhängiges** Partitions-Bewusstsein im
  `AbstractDdlGenerator` (driver-common) wäre die richtige Höhe.

## Skizze (zu schärfen)

- Vor der Tabellen-Emission **einmal** den Satz partitionierter Tabellen aus dem `SchemaDefinition`
  ableiten (eine reine Funktion über `schema.tables`, ordnungsunabhängig), statt ihn inkrementell
  während der Emission zu mutieren. Die „tatsächlich partitioniert"-Bedingung (skip via E055/E062 lässt
  die Tabelle unpartitioniert) muss dabei erhalten bleiben — d. h. die Ableitung muss dieselben
  Skip-Regeln anwenden wie der Generator (gemeinsamer Prädikat-Helper).
- Den FK-Carve-Out-Pfad (MySQL/InnoDB: keine FKs auf/zu partitionierten Tabellen) gegen diese
  vorab berechnete Menge prüfen.
- Prüfen, ob PG/SQLite von einer gemeinsamen Abstraktion profitieren oder die Hebung MySQL-lokal bleibt.

## Abgrenzung / Nicht-Ziel

- **Kein** Verhaltens-Change am generierten DDL — reines Refactoring (gleiche Notes/Carve-Outs,
  gleiche Goldens).
- Sub-Partitionierung bleibt OUT.

## Bezug

- Auslöser-Slice: [`../in-progress/cross-dialect-partitioning.md`](../in-progress/cross-dialect-partitioning.md)
  (Review-Härtung Runde 1, Befund #12).
- ADR der Modellform: [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md),
  [ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md).
