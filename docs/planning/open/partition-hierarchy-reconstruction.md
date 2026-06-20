# Volle Partitions-Hierarchie-Rekonstruktion (PG zuerst)

> **Status:** Vorabklärung (Trigger, 2026-06-20)
> **Trigger:** Der Pagila/PG-Round-Trip des Sample-DB-Harness meldet `E055`
> für die range-partitionierte `payment`-Tabelle und erzeugt sie als plain
> (nicht partitionierte) Tabelle — gemeldet als bewusste „fundamentale Grenze"
> in [`../done/sample-db-roundtrip-findings.md`](../done/sample-db-roundtrip-findings.md).
> Ursache: der PG-Reverse erfasst nur *Strategie + Schlüssel* der
> Partitionierung, nie die Kind-Partitionen — `PartitionConfig.partitions`
> bleibt leer (`partitioning != null && partitions.isEmpty()` → E055-Fallback).
> **Bezug (Anforderung):** **LN-008** „Partitionierung für große Tabellen"
> ([`../../../spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md):
> automatische Erkennung/Verarbeitung partitionierter Tabellen, Partition by
> RANGE/HASH/LIST). Heute nur **teilweise** abgedeckt: Strategie/Schlüssel
> round-trippen, die Hierarchie nicht.
> **Aktivierungsbedingung:** Sobald ein Pilot-/Anwenderfall echte Partitions-
> Treue braucht (oder die Sample-DB-Cross-Dialect-Phase einen partition-
> getriebenen Fidelity-Bedarf aufdeckt), wandert dieser Eintrag nach
> `../next/` — dort mit Phasenschnitt, eigener ADR und Akzeptanzkriterien.
> **Disposition (2026-06-20):** nicht in 0.9.9; Performance-Aspekte von LN-008
> (Export/Import pro Partition, parallele Verarbeitung) gehören zur
> Performance-Phase (1.0.x / Phase 4), die reine Schema-Treue kann früher.

## Gegenstand

Eine partitionierte Eltern-Tabelle samt ihrer Kind-Partitionen als **eine**
Hierarchie round-trippen lassen, statt als „partitionsloser Parent + lose
Basistabellen". Das schließt die heute via `E055` gemeldete Grenze für den
Normalfall (Pagila `payment`).

**Ist-Stand (wichtig — der Scope ist kleiner als er scheint):**

- **Modell + Serialisierung tragen die Hierarchie bereits** — *für die opake
  Repräsentation:* `PartitionConfig.partitions: List<PartitionDefinition>`
  (Name + `from`/`to`/`values` als Strings) in
  [`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt)
  und `partitioning.partitions` in
  [`spec/schema.json`](../../../spec/schema.json). **Bei der empfohlenen
  strukturierten Variante (AP1a) zieht die Serialisierung mit** (`schema.json` +
  `schema-reference.md`) — dann ist „bereits da" nur die opake Form.
- **Generate ist bereits implementiert (inkl. Tests):**
  `PostgresDdlGenerator.kt` iteriert über `partitioning.partitions` (Zeilen
  149–154) und emittiert je Kind `CREATE TABLE … PARTITION OF … FOR VALUES
  FROM/TO (RANGE) | IN (LIST) | WITH (HASH)` via `generatePartitionStatement`
  (Zeilen 178–205). Der `E055`-Fallback greift **nur**, wenn `partitions` leer
  ist.

Die **fehlende Schicht ist der Reverse-Capture**: solange er `partitions` nicht
befüllt, läuft alles Übrige (Generate, Serialisierung) ins Leere und der
E055-Fallback bleibt aktiv. Hinzu kommt eine **bewusst partitions-blinde
Vergleichsschicht** (siehe AP4), die heute Treue gar nicht prüfen kann.

**Aber: „Reverse-Capture" ist nicht „eine `pg_inherits`-Abfrage".** Der
Bug-Hotspot ist der **Bound-Parser/Normalisierer**, nicht die Query.
`pg_get_expr(relpartbound, …)` liefert die *ganze* Klausel
(`FOR VALUES FROM (…) TO (…)`), bei HASH kleingeschrieben
(`FOR VALUES WITH (modulus 4, remainder 0)`) und rendert zusätzlich **Typ-Casts**
(`FROM ('2024-01-01'::date)`) sowie **Sentinels** (`MINVALUE`/`MAXVALUE`/
`DEFAULT`). Der Parser muss daraus den Klammer-Inhalt extrahieren und ihn auf
**ein** kanonisches Encoding bringen (Casing, Quoting, Whitespace, Casts strippen,
Sentinels kanonisieren). **Wichtig:** *Generate* selbst ist tolerant — es
emittiert den `from`-String unverändert und PG akzeptiert auch lowercase (der
HASH-Golden prüft nur `FOR VALUES WITH`). Load-bearing wird die Normalisierung
erst beim **strukturellen `data class`-Vergleich des Comparators (AP4)** — dort
erzeugt jede Abweichung einen falsch-positiven Diff. Genau deshalb ist sie an den
nächsten Knackpunkt gekoppelt.

## Architektur-Knackpunkt: opake Dialekt-Strings im neutralen Modell

`PartitionDefinition.from`/`to`/`values` tragen heute **rohe PG-SQL-Fragmente**
(`'2022-01-01'`, `MINVALUE`, `MODULUS 4, REMAINDER 0`): Generate konkateniert
sie ungeparst ins DDL, abgesichert nur durch `validatePartitionBound` gegen
`;`/`--`/`*` (`PostgresDdlGenerator.kt:207-215`). Das kollidiert **frontal** mit
der Hausregel „**kein Native-Passthrough im neutralen Modell**" — `NeutralType`
reicht keine rohen Dialekt-Strings durch, PG-only-Strukturen werden first-class
modelliert (Präzedenz: `tsvector`→`fulltext` in
[`ADR 0015`](../../adr/0015-fulltext-tsvector-neutral-type.md), geometry-Muster;
vgl. [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md)).

Daraus folgt eine echte Abwägung, die **die ADR treffen muss** — nicht der Code
nebenbei:

- **Opaken String festschreiben** (expedient): Generate baut bereits darauf, also
  am schnellsten. Aber es **zementiert den Native-Passthrough** und verstößt
  gegen das Projektprinzip.
- **Strukturiert modellieren** (regelkonform, empfohlen): `PartitionDefinition`
  trägt typisierte Grenzen (z. B. RANGE: `from`/`to` als Wertlisten/Sentinels
  `MINVALUE`/`MAXVALUE`; HASH: `modulus`/`remainder` als Zahlen; LIST: Wertliste).
  Mehr Arbeit (Modell + Generate + Serialisierung + Reverse + Compare), aber die
  einzige Variante, die zur Hausregel passt.

**Verbindung zu AP6 (Cross-Dialect):** genau dieser opake String ist die
Cross-Dialect-Hürde, nicht nur die Generate-Form. PG-`FROM/TO` lässt sich nur
strukturiert nach MySQL-`VALUES LESS THAN` abbilden — aus einem rohen
PG-SQL-Fragment geht das nicht. Die strukturierte Repräsentation ist also nicht
nur „sauberer", sondern **Voraussetzung** für AP6.

## Grobe Arbeitspakete

- **AP1 — Reverse: Kinder + Grenzen erfassen (PG) — Kern-Lücke.**
  `getPartitionInfo`
  (`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTableMetadataQueries.kt`)
  um eine Kind-Abfrage erweitern: über `pg_inherits` (`inhparent = parent.oid`)
  die Partitionen finden, je Kind `pg_get_expr(c.relpartbound, c.oid)` lesen und
  die `FOR VALUES`-Klausel je Strategie ins Modell heben.
  `readPostgresPartitioning`
  (`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaStructureReaders.kt`)
  gibt dann `PartitionConfig(type, key, partitions = […])` zurück. Der
  Parser/Normalisierer ist der Bug-Hotspot (Gegenstand oben), nicht die Query.
- **AP1a — *Ein* kanonisches `PartitionDefinition`-Encoding (von Reverse,
  Generate UND Comparator geteilt).** Heute liest Generate: RANGE
  `from`→`FROM(…)`/`to`→`TO(…)`; LIST `values`→`IN(…)`; HASH `from`→`WITH(…)`
  (HASH also über `from = "MODULUS n, REMAINDER m"`). **Welche Form** dieses
  Encoding hat, entscheidet der Architektur-Knackpunkt oben (opak vs.
  strukturiert). Unabhängig davon gilt: der Reverse-Parser muss **exakt** dorthin
  normalisieren (Casing/Quoting/Whitespace, Typ-Casts strippen, Sentinels
  `MINVALUE`/`MAXVALUE`/`DEFAULT` kanonisieren — `pg_get_expr` rendert all das),
  denn — kritisch — `PartitionDefinition` ist eine `data class` und der Comparator
  (AP4) vergleicht **strukturell**. Weicht der Reverse
  vom generierten/erwarteten Encoding ab, erzeugt der partitions-bewusste
  Comparator **falsch-positive** Diffs und der Pagila-Round-Trip wird DIFFERENT
  *trotz* korrekter Migration. Also: **ein** kanonisches Encoding, geteilt von
  Reverse-Parser, Generate-Emit und Comparator-Fixtures — mit Round-Trip-Test je
  Strategie.
- **AP2 — Reverse: Doppel-Emit vermeiden.** Tabellen mit `relispartition = true`
  aus dem Top-Level-Basistabellen-Durchlauf nehmen und ausschließlich unter dem
  Parent (`partitions`) führen — sonst erscheint jedes Kind doppelt (einmal als
  Partition, einmal als eigenständige Tabelle, wie heute). Die Kinder sind nach
  AP2 **nicht mehr im Top-Level-Schema** (weder DDL noch Modell-Tabellenliste).
- **AP3 — Generate verifizieren (kein Neubau).** Das `PARTITION OF`-Emit
  existiert (oben). AP3 prüft nur, dass das von AP1 befüllte Modell sauber
  durchläuft, und schließt etwaige Lücken (z. B. Default-Partition, Sub-
  Partitionierung — siehe Abgrenzung), statt Generate neu zu schreiben.
- **AP4 — Comparator partitions-bewusst machen — Pflicht für einen echten
  Treue-Beweis.** `TableDiff`
  ([`hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableDiff.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableDiff.kt))
  hat **kein** Partitionierungsfeld, und
  [`hexagon/core/src/test/kotlin/dev/dmigrate/core/diff/SchemaComparatorTest.kt`](../../../hexagon/core/src/test/kotlin/dev/dmigrate/core/diff/SchemaComparatorTest.kt)
  fixiert das absichtlich („partitioning changes do not produce diff"). Dieser
  Slice muss: ein `partitioning`-Diff-Feld zu `TableDiff` ergänzen, den
  Comparator/`TableComparator` Partitions-Strategie/-Schlüssel/-`partitions`
  (Set-Gleichheit, reihenfolge-unabhängig) vergleichen lassen, und **den
  bestehenden Test umdrehen** (Partitionsunterschied ⇒ Diff). `MigrationFingerprint`
  und `spec/schema-reference.md` nachziehen. **Die Set-Gleichheit hängt direkt am
  kanonischen Encoding (AP1a):** ohne identische Normalisierung zwischen Reverse
  und Generate liefert der `data class`-Strukturvergleich falsch-positive Diffs.
- **AP5 — Daten-Transfer: Nebeneffekt von AP2, kein eigener Slice.** Der Transfer
  enumeriert seine Arbeitseinheiten **nicht** über einen eigenen DB-Scan, sondern
  über das **Reverse-Modell**: `DataTransferRunner` liest
  `srcDrv.schemaReader().read(srcPool, …).schema` (`DataTransferRunner.kt:107`)
  und `TransferPreflightPlanner.planTables` nimmt `source.tables.keys`
  (`TransferPreflightPlanner.kt:24`). Das ist **derselbe `schemaReader()`, den
  AP2 ändert** — entfernt AP2 die `relispartition`-Kinder aus dem Reverse, sind
  sie auch im Transfer weg. Damit gilt der Vertrag **automatisch**: der Parent ist
  die einzige Transfer-Einheit, PG routet INSERTs deklarativ in die Kinder, SELECT
  am Parent liefert alle Kind-Zeilen — kein Doppeltransfer, kein Datenverlust.
  Übrig bleibt **ein Verifikationstest** (Parent-Routing für Read **und** Write,
  Zeilen-Parität am Parent), kein neuer Enumerations-Mechanismus.
- **AP6 — Cross-Dialect-Abgrenzung.** MySQL nutzt **inline** definierte
  Partitionen (`PARTITION BY RANGE (…) (PARTITION p0 VALUES LESS THAN …)`) statt
  separater `CREATE TABLE … PARTITION OF` — andere Generate-Form, eigener
  Sub-Scope oder Mapping. SQLite kennt keine Partitionierung → `E055` bleibt
  (bereits so). Cross-Dialect-Transfer PG-deklarativ → MySQL-inline ist ein
  struktureller Umbau (vgl. Volltext-Carve-Out-Muster).

## Kopplung (Reihenfolge ist nicht beliebig)

AP1 und AP4 **müssen zusammen landen**: macht man den Comparator partitions-
bewusst (AP4), **bevor** der Reverse die Partitionen erfasst (AP1), bekäme der
Pagila-Round-Trip plötzlich einen Partitions-Diff (Quelle modelliert RANGE,
Ziel — als plain Tabelle erzeugt — nicht) und die bestehende
`IDENTICAL`-Baseline bricht. Erst Reverse-Capture stellt beide Seiten gleich,
dann ist der partitions-bewusste Vergleich grün.

## Akzeptanzkriterien (Skizze — schärfen beim Move nach `next/`)

- **Reverse-Capture:** `PartitionConfig.partitions` wird für RANGE/LIST/HASH
  befüllt (pg_inherits + `relpartbound`-Parsing) — Unit-Test je Strategie; das
  `from`-Encoding für HASH (`MODULUS/REMAINDER`) ist als Modellvertrag getestet
  (AP1a).
- **Comparator beweist Treue (negativ):** ein Partitionsunterschied (Kind
  hinzugefügt/entfernt, Grenze geändert, Strategie/Schlüssel geändert) **lässt
  `schema compare` fehlschlagen** (Exit DIFFERENT). Der heutige Test
  „partitioning changes do not produce diff" ist umgedreht.
- **Pagila/PG-Round-Trip:** `payment` als echte RANGE-Partition mit ihren 7
  Kindern emittiert; **kein** `E055` mehr; Zeilen-Parität (am Parent); und
  `schema compare` IDENTICAL — *jetzt aussagekräftig*, weil der Comparator
  partitions-bewusst ist **und** der Reverse die Partitionen auf beiden Seiten
  herstellt (Kopplung oben).
- **Cross-Dialect-Abgrenzung dokumentiert** (MySQL-inline als eigener Sub-Scope
  oder mitgeliefert; SQLite E055 unverändert).
- **LN-008-Abdeckungsgrad** (Schema-Treue vs. Performance-Teil) dokumentiert.

## Abgrenzung / Nicht-Ziel (erste Scheibe)

- **Export/Import pro Partition + parallele Verarbeitung** (Performance-Teil von
  LN-008) — eigener Slice, eher Performance-Phase (1.0.x / Phase 4).
- **Sub-Partitionierung** (Partitionen von Partitionen).
- **Default-Partition** (`… DEFAULT`): **harte Ja/Nein-Entscheidung beim Move
  nach `next/`** — nicht „falls trivial" offenlassen (sonst entsteht später ein
  bedingter else-/Stopgap-Zweig im Code statt einer sauberen Scope-Grenze).
- **Index-/Constraint-/PK-Propagation am Parent:** nach AP2 trägt der Parent die
  volle Spalten-/Index-/Constraint-Menge; PG verlangt, dass der **Primary Key
  einer partitionierten Tabelle den Partitionsschlüssel enthält**. Die
  Reverse-Erfassung muss PK/Indizes am Parent (nicht an den Kindern) führen —
  sonst schlägt das Generate fehl. (Modellierungsdetail, kein eigener Slice.)
- HASH ist **nicht** abgegrenzt: das Generate emittiert es bereits — der Reverse
  muss es nur (im kanonischen Encoding, AP1a) erfassen.

## Offene Entscheidungen (für die ADR beim Move)

- **`PartitionDefinition`-Repräsentation (der zentrale Entscheid):** opake
  Dialekt-Strings festschreiben vs. typisierte Grenzen — siehe den Abschnitt
  [Architektur-Knackpunkt](#architektur-knackpunkt-opake-dialekt-strings-im-neutralen-modell).
  Die Hausregel „kein Native-Passthrough" favorisiert die **strukturierte**
  Variante; sie ist zudem Voraussetzung für AP6 (Cross-Dialect). Der opake Weg
  ist nur expedient. Die ADR muss explizit abwägen (Aufwand vs. Prinzip), nicht
  den Status quo fortschreiben.
- **Comparator-Verhaltensänderung:** das Umdrehen von
  „partitioning changes do not produce diff" ist eine bewusste, getestete
  Bestandsentscheidung — der Move begründet sie (eigene ADR), nicht nur den Code.
- **Eigene ADR** insgesamt nötig: betrifft Comparator-Semantik, Doppel-Emit-
  Vermeidung und Cross-Dialect-Form-Divergenz (deklarativ vs. inline) — vgl. das
  geometry-/fulltext-Muster für dialekt-spezifische Strukturen.
- **Daten-Transfer-Strategie:** für die *Korrektheit* aufgelöst (Parent-Routing,
  Nebeneffekt von AP2 — siehe AP5). Offen bleibt nur die *Performance*-Variante
  (paralleler per-Partition-Transfer) — die gehört zum LN-008-Performance-Teil
  (Abgrenzung) und nicht in diese Scheibe.
