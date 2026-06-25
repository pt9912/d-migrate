# Partitions-Bewusstsein im gemeinsamen DDL-Generator (statt MySQL-Seitenkanal)

> **Status: BEHOBEN (2026-06-25) → graduiert nach done/.** Seitenkanal ordnungsunabhängig +
> immutabel gemacht (Up-front-Snapshot); MySQL-Lokalität bewusst beibehalten (begründet). Resolution unten.
> Vorabklärung (Trigger, 2026-06-25)
> **Trigger:** AP6-Review-Härtung (Befund #12, Altitude). Beim Cross-Dialect-Partitionierungs-Slice
> ([`../done/cross-dialect-partitioning.md`](../done/cross-dialect-partitioning.md))
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

- Auslöser-Slice: [`../done/cross-dialect-partitioning.md`](../done/cross-dialect-partitioning.md)
  (Review-Härtung Runde 1, Befund #12).
- ADR der Modellform: [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md),
  [ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md).

## Resolution (2026-06-25)

**Behoben als ordnungsunabhängiges, immutables Up-front-Snapshot — kein mutierbarer Seitenkanal mehr.**

`MysqlDdlGenerator.partitionedTables` ist jetzt ein `val`-artiges Feld (`private var … = emptySet()`),
das **einmal zu Beginn von `generate()`** aus dem `SchemaDefinition` berechnet wird
(`computePartitionedTables`), **bevor** irgendeine Tabelle emittiert wird. Während `generateTable`
wird es **nur noch gelesen** (FK-Carve-Out auf/zu partitionierter Tabelle, zirkulärer ALTER). Damit:

- **Reihenfolge-Abhängigkeit weg:** ein FK, der eine partitionierte Tabelle referenziert, wird korrekt
  als Carve-Out erkannt, egal ob die referenzierte Tabelle vorher oder nachher emittiert wird — die
  Menge ist immer vollständig.
- **Stale-State-Falle weg:** kein inkrementelles Mutieren/`clear()` zwischen Läufen; jeder `generate()`
  weist den Snapshot frisch zu.
- **„Tatsächlich partitioniert"-Semantik exakt erhalten:** `computePartitionedTables` ruft **dieselbe**
  `MysqlIndexPartitionDdlHelper.generatePartitionClause` mit einer **Wegwerf-Note-Senke** auf und nimmt
  nur das Emit-oder-nicht-Signal (E055/E062-Skip + leere-LIST-Filterung exakt wie bei der Emission;
  die echten Notes entstehen weiterhin bei der Emission). → identische DDL-Ausgabe.

**Entscheidung zur „Dialekt-Lokalität":** bewusst **MySQL-lokal** belassen, **nicht** in den gemeinsamen
`AbstractDdlGenerator` gehoben — das FK-auf-partitionierter-Tabelle-Verbot ist **nur** MySQL/InnoDB
(PostgreSQL erlaubt solche FKs; SQLite hat keine Partitionierung). Es gäbe also **keinen** PG/SQLite-
Konsumenten; eine geteilte Abstraktion wäre verfrüht (YAGNI). Der ursprünglich als Smell notierte
„Dialekt-lokal"-Punkt ist damit als **korrekt** aufgelöst — der echte Smell war der mutierbare,
ordnungsabhängige Zustand, und der ist weg.

**Verifikation:** reiner Refactor — `:adapters:driven:driver-mysql:check` + `:adapters:driven:formats:check`
(full-featured-Golden mit partitioniertem `orders` + FK→E065, `MysqlPartitionForeignKeyTest` inkl.
Cross-Direction + zirkulär) + koverVerify grün; DDL-Goldens unverändert.
