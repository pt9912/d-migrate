# Volle Partitions-Hierarchie-Rekonstruktion (PG zuerst)

> **Status:** in-progress/-Slice — **graduiert 2026-06-23** (aus `open/`), **alle IN-SCOPE-
> Arbeitspakete fertig + grün (2026-06-24): AP1a (`021c0ce2`) + AP1/AP2 + AP4 + Review-Härtung +
> AP2a + AP3** (docker-`check`, live `make sample-db-smoke`, live `make integration`).
> **Offen nur AP6 (Cross-Dialect) = eigener Folge-Slice (ADR 0019).** Gate-Entscheidung =
> [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md) (accepted):
> **strukturierte** `PartitionDefinition`.
> **Trigger:** Der Pagila/PG-Round-Trip des Sample-DB-Harness meldet `E055`
> für die range-partitionierte `payment`-Tabelle und erzeugt sie als plain
> (nicht partitionierte) Tabelle —
> in [`../done/sample-db-roundtrip-findings.md`](../done/sample-db-roundtrip-findings.md).
> Ursache: der PG-Reverse erfasst nur *Strategie + Schlüssel* der
> Partitionierung, nie die Kind-Partitionen — `PartitionConfig.partitions`
> bleibt leer (`partitioning != null && partitions.isEmpty()` → E055-Fallback).
> **Korrektur des Findings-Labels (supersedes):** Das Findings-Doc nennt das eine
> „leere RANGE-Partition / dump-abhängige Eigenheit / kein Defekt" — das ist
> **unpräzise**. Die 7 Kinder **existieren vollständig in der Quelle** (sie werden
> nur als Standalone-Tabellen statt unter dem Parent geführt); der Verlust der
> Partitions-Hierarchie ist ein **systematischer Reverse-Capture-Fidelity-Defekt**,
> nicht dump-abhängig. Nur der E055-*Generate*-Fallback selbst bleibt korrekt
> (sichere Reaktion auf eine leere Liste). Erratum im Findings-Doc gesetzt.
> **Bezug (Anforderung):** **LN-008** „Partitionierung für große Tabellen"
> ([`../../../spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md):
> automatische Erkennung/Verarbeitung partitionierter Tabellen, Partition by
> RANGE/HASH/LIST). Heute nur **teilweise** abgedeckt: Strategie/Schlüssel
> round-trippen, die Hierarchie nicht.
> **Graduierung (2026-06-23):** nach `next/` gehoben + Gate-ADR 0019 geschrieben. Sie
> löst den zentralen Fork (Repräsentation) → **strukturiert**; die „Offenen Entscheidungen"
> unten sind in ADR 0019 aufgelöst (DEFAULT in-Scope, Sub-Partition out, Performance-
> Transfer out, **PG-Reverse zuerst + MySQL-Generate-Consume in dieser Scheibe**,
> MySQL-Reverse als Folge-Slice). Arbeitspakete + Kopplung + Akzeptanz unten.
> Performance-Aspekte von LN-008 (per-Partition-Transfer, parallel) bleiben Performance-Phase.

## Stand & Wiedereinstieg (Stand 2026-06-24)

**Erledigt 2026-06-23 — AP1a (Modell-Gate), Commit `021c0ce2`, docker-verifiziert grün
(compile + alle Tests + detekt):**

- **Modell** ([`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt)):
  `sealed interface PartitionBound { MinValue; MaxValue; Value(literal) }`;
  `PartitionDefinition` jetzt strukturiert — `from`/`to: List<PartitionBound>?` (RANGE-Tupel
  + Sentinels), `modulus`/`remainder: Int?` (HASH), `isDefault: Boolean` (DEFAULT),
  `values` (LIST) unverändert.
- **PG-Generate** ([`PostgresDdlGenerator.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt)):
  `generatePartitionStatement` rendert strukturierte Bounds (`renderRangeBounds`),
  `DEFAULT`, HASH aus `modulus`/`remainder`; Literal-Guard `validatePartitionLiteral` erhalten.
- **MySQL-Generate** ([`MysqlIndexPartitionDdlHelper.kt`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt)):
  `VALUES LESS THAN` aus dem `to`-Tupel (`renderMysqlUpperBound`); `from` verworfen
  (Carve-Out, ADR 0019); `isDefault → MAXVALUE`.
- **Serialisierung** ([`SchemaNodeStructureBuilders.kt`](../../../adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeStructureBuilders.kt) /
  [`SchemaNodeStructureParsers.kt`](../../../adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeStructureParsers.kt)):
  `from`/`to` als String-Array (Sentinels `MINVALUE`/`MAXVALUE`), `modulus`/`remainder`/`default`.
  `spec/schema.json` + `spec/schema-reference.md` nachgezogen.
- **DDL byte-identisch** (Goldens unverändert) — AP1a ändert nur die *interne* Repräsentation,
  nicht die emittierte DDL. 8 Test-Konstruktions-Sites + 3 Fixtures auf strukturierte Form,
  MaxLineLength sauber umgebrochen (kein `@Suppress`).

**Erledigt 2026-06-24 — AP1 + AP2 (PG-Reverse-Capture), docker-verifiziert grün
(`:driver-postgresql:check` = compile + Tests + detekt + koverVerify) UND live
end-to-end (`make sample-db-smoke`, Exit 0):**

1. **AP1 — Kinder + Grenzen lesen.** Neue Query `listPartitionChildren`
   ([`PostgresTableMetadataQueries.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTableMetadataQueries.kt))
   über `pg_inherits` (+ `c.relispartition`-Guard), je Kind
   `pg_get_expr(c.relpartbound, c.oid)`. Neuer **Bound-Parser**
   ([`PostgresPartitionBoundParser.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresPartitionBoundParser.kt)) —
   quote-/klammer-bewusster Scanner: strippt nachgestellte `::typ`-Casts auf Top-Level,
   kanonisiert `MINVALUE`/`MAXVALUE` → Sentinels, RANGE-`FROM/TO`-Tupel,
   LIST-`IN`, HASH-`WITH (modulus/remainder)` → Ints, `DEFAULT` → `isDefault`.
   `readPostgresPartitioning` befüllt jetzt `partitions`. **15 Parser-Unit-Tests**
   (inkl. Pagila-timestamptz-Cast, mehrspaltige Tupel, numeric(10,2)-Cast-mit-Klammer,
   escaptes Quote, Sentinel-im-Tupel).
2. **AP2 — Doppel-Emit vermeiden.** `relispartition`-Filter in `listTableRefs`
   (`to_regclass`-NOT-EXISTS, Stil wie der Extension-Filter). Deckt **zugleich AP5**:
   Schema-Reader **und** `PostgresTableLister`/Transfer teilen dieselbe Query → Kinder
   sind auch aus der Transfer-Enumeration weg, kein Doppeltransfer.
3. **Live-Verifikation grün** (PG→PG Pagila): reverse erfasst 7 Kinder mit korrekt
   gestrippten Grenzen (`'2022-01-01 00:00:00+00'`); generate **0 Notes** (kein E055);
   post-data sauber; **Per-Kind-Parität** src==tgt (723/2401/2713/2547/2677/2654/2334 →
   Bound-Routing korrekt); **keine Duplikation** (payment total 16049, nicht 32098);
   `schema compare` **IDENTICAL** (Baseline gehalten). Harness-Gate + `pagila-smoke.md`
   auf „0 Notes" nachgezogen.

**Bekannte Kopplung (jetzt scharf):** AP1/AP2 ändert auch den **Cross-Dialect**-Pfad —
der MySQL-Generate konsumiert jetzt befüllte `partitions` und emittiert für Pagila eine
MySQL-RANGE-Partition mit timestamptz-Grenzen (`VALUES LESS THAN ('…+00')`, W112), die
MySQL ablehnt (RANGE braucht Integer-Ausdruck, z. B. `UNIX_TIMESTAMP()`/`RANGE COLUMNS`).
Das ist **AP6**-Gebiet (eigener Folge-Slice) und betrifft nur das separate
`sample-db-cross-smoke-pg2my`-Target; **kein** Ad-hoc-Stopgap (Hausregel „No-Carveouts").
Der PG→PG-Smoke (Akzeptanz dieser Scheibe) ist davon unberührt.

**Erledigt 2026-06-24 — AP4 (Comparator partitions-bewusst), docker-`:hexagon:core:check`
grün UND live re-verifiziert (`make sample-db-smoke`, Exit 0, compare IDENTICAL):**

- **`TableDiff`** ([`TableDiff.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/TableDiff.kt))
  trägt jetzt `partitioning: ValueChange<PartitionConfig?>?` + in `hasChanges()`.
- **`TableComparator.comparePartitioning`**: Strategie + Schlüssel (geordnet) gleich UND
  Kind-Partitionen als **Menge** (`partitions.toSet()`, reihenfolge-unabhängig) → sonst Diff.
  Set-Gleichheit setzt das *eine* kanonische Bound-Encoding (AP1/AP1a) voraus.
- **Test umgedreht:** „partitioning changes do not produce diff" entfernt; neue eigene Spec
  [`SchemaComparatorPartitioningTest.kt`](../../../hexagon/core/src/test/kotlin/dev/dmigrate/core/diff/SchemaComparatorPartitioningTest.kt)
  (7 Fälle: none→RANGE, Strategie, Schlüssel, geänderte Grenze, hinzugefügtes Kind → DIFFERENT;
  Reihenfolge egal + identisch → kein Diff). Eigene Datei = echte Aufteilung gegen LargeClass
  (kein `@Suppress`, [[feedback-no-suppress-for-size]]).
- **`MigrationFingerprint` auf `schema-fingerprint-v4`** gebumpt: projiziert jetzt
  Partitionierung (Strategie/Schlüssel/Kinder nach Name sortiert + Bounds im kanonischen
  Encoding), damit Comparator und Post-`--execute`-Drift-Check übereinstimmen. `spec/schema-reference.md`
  nachgezogen (zeitlose Vergleichs-Notiz, kein Abwärtsverweis).
- **Linchpin live bewiesen:** der Round-Trip bleibt `IDENTICAL` *jetzt aussagekräftig* — der
  Comparator prüft die Bound-Strings, und Source-↔Target-Reverse sind byte-identisch.

**Erledigt 2026-06-24 — AP2a (kind-lokale Partition-Indizes), `:core/:driver-postgresql/:formats:check`
grün UND live (`make sample-db-smoke`, Exit 0, 3 Indizes von payment_p2022_01 namensgleich überlebt):**

- **Modell:** `PartitionDefinition.indices: List<IndexDefinition>` (kind-lokal).
- **Reverse-Klassifikation:** neue Query `listInheritedIndexNames` (Index-Vererbung via
  `pg_inherits` über Index-OIDs) trennt parent-propagiert von kind-lokal; nur kind-lokale
  erfasst (`readPartitionLocalIndices`, nach Name sortiert). `mapPostgresIndices` extrahiert
  + geteilt. **FKs/PK bleiben am Parent + propagieren** (live verifiziert: `_pkey` propagiert,
  nicht dupliziert) → kein per-Kind-FK-Capture nötig.
- **Generate:** `generateIndices` emittiert kind-lokale Indizes auf der Partitionstabelle.
- **Serialisierung** (`indices` im Partition-Node), `spec/schema.json` (`#/$defs/index`) +
  `schema-reference` + Contract-Fixture nachgezogen.
- **Fingerprint → `schema-fingerprint-v5`** (Partition-Indizes projiziert; Comparator vergleicht
  sie strukturell). Comparator/Drift-Check stimmen überein.
- **Refactor (detekt, kein @Suppress):** Partition-Queries → eigenes `PostgresPartitionMetadataQueries`
  (Fassade unter Funktions-Limit); Partition-Reader-Tests → eigene `PostgresSchemaReaderPartitionTest`
  (LargeClass).

**Erledigt 2026-06-24 — AP3 (Generate-Verifikation, kein Neubau), `:driver-postgresql:check` grün
+ Live-Integration gegen echtes PG (`make integration`, BUILD SUCCESSFUL):**

- **Unit:** PG-Generate emittiert die **DEFAULT-Partition** als `CREATE TABLE … PARTITION OF …
  DEFAULT;` (war ungetestet; RANGE/LIST/HASH waren es). Test in eigener
  `PostgresDdlGeneratorPartitionTest` (LargeClass-Split, kein @Suppress).
- **Live (Testcontainers, echtes PG):** LIST-partitionierte Tabelle mit explizitem Kind +
  DEFAULT-Partition + kind-lokalem Index reverst korrekt (isDefault/values/Index erfasst, Kinder
  nicht Top-Level), UND das reverse-Modell **generiert sauber** — die erzeugte DDL wird in ein
  Wegwerf-Schema re-appliziert (Defekt würde werfen). Integration-Test in
  `PostgresSchemaReaderIntegrationTest`.

**IN-SCOPE-ARBEITSPAKETE KOMPLETT** (AP1a/AP1/AP2/AP2a/AP3/AP4; AP5 = Transfer-Nicht-Duplikation
automatisch via AP2 + per Smoke-Parität belegt). **AP6 (Cross-Dialect) ist laut
[ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md) ein eigener Folge-Slice:**
MySQL-RANGE-Mapping (`UNIX_TIMESTAMP`/`RANGE COLUMNS`; timestamptz-Grenzen) + MySQL-Reverse-Capture;
bricht aktuell `sample-db-cross-smoke-pg2my` (MySQL-Literal-Guard ist seit Review-Härtung schon da).
Sub-Partitionierung bleibt OUT (ADR 0019). → Diese Scheibe ist bereit zur Graduierung nach `done/`,
sobald AP6 als eigener Slice geschnitten ist.

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
  [`spec/schema.json`](../../../spec/schema.json) (`partitions` mit `from`/`to`
  als **Strings**, `values` als **String-Array**, `spec/schema.json:335`). **Bei der empfohlenen
  strukturierten Variante (AP1a) zieht die Serialisierung mit** (`schema.json` +
  `schema-reference.md`) — dann ist „bereits da" nur die opake Form. Das ist ein
  **Bruch einer bereits ausgelieferten Serialisierungs-Form** (String-Grenzen →
  typisierte Grenzen) mit Contract-Fixture-/Golden-Churn; weil `spec/` Zielbild
  ist, vertretbar, aber in der ADR zu flaggen.
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
`;`/`--`/`/*` (`PostgresDdlGenerator.kt:207-215`). Das kollidiert **frontal** mit
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
  Mehr Arbeit (Modell + Generate **PG `PostgresDdlGenerator` *und* MySQL
  `MysqlIndexPartitionDdlHelper.generatePartitionClause`** + Serialisierung +
  Reverse + Compare), aber die einzige Variante, die zur Hausregel passt. **Der
  MySQL-Generate-Anteil ist real, nicht hypothetisch** — der Pfad existiert und
  müsste mit umgebaut werden (sonst wird der Aufwand der empfohlenen Variante zu
  niedrig angesetzt). **Gegengewicht:** die strukturierte Variante **löst das
  Encoding-Identitäts-Risiko (den „Linchpin") weitgehend auf** — Zahlen
  vergleichen sich als Zahlen (HASH `modulus`/`remainder`), Sentinels als
  Enum-Werte, und die Cast-/Casing-/Whitespace-Strip-Fragilität kollabiert zu
  einem *einmaligen* Parse in typisierte Felder. Bei der opaken Variante ist
  byte-identisches Re-Encoding auf beiden Seiten das ganze Spiel. Der Linchpin ist
  also **ein Preis des opaken Wegs, kein inhärentes Risiko** — ein zusätzliches
  Argument für strukturiert.

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
- **AP2a — Per-Partition-Index/FK-Vererbung — Pflicht-Sub-Slice (drei
  Behandlungsklassen, nicht binär).** Jedes der 7 Pagila-Kinder trägt ~3 Indizes
  + 3 FKs → **≈42 Objekte**, aber sie sind **keine einheitliche Klasse**. Drei
  Behandlungen:
  - **parent-propagiert** → verwerfen & neu propagieren (PG legt das Kind-Backing
    automatisch an, wenn der Index am Parent definiert ist).
  - **kind-lokal** → **muss erhalten bleiben.** Wird es als „Backing-Name"
    verworfen, geht es still verloren — genau die Fidelity-Verlustklasse, die
    dieser Slice beseitigen soll.
  - **FK** → Parent-Deklaration verifizieren (Pagila deklariert FKs am Parent →
    PG 11+ propagiert → Kind-Kopien leiten sich neu ab; *als verifizierte Annahme*
    behandeln, nicht durch Auslassen unterstellen).
  **Pagila macht das scharf:** der Parent `payment` hat **gar keine** Indizes
  (Quelle deklariert `idx_fk_payment_p2022_NN_*` und `payment_p2022_NN_*_idx`
  **pro Kind**, `pagila.sql:47739ff`) → in diesem Dump ist **jeder** Kind-Index
  *kind-lokal*; ein „nur Parent behalten"-Modell verlöre **alle**.
  **Reverse-Konsequenz:** `relispartition`/`pg_class` allein unterscheidet die
  Klassen nicht — die pg_index-Zeilen sehen identisch aus. Es braucht eine
  **Index-Vererbungs-Abfrage** (`pg_inherits` über die Index-OIDs, `inhparent`
  zeigt auf den Parent-Index) und für Constraints `pg_constraint.conparentid`.
  AP1s „`pg_inherits` über Tabellen" ist **notwendig, aber nicht hinreichend**.
  **Modell-Konsequenz:** kind-lokale Objekte brauchen einen Platz (per-Partition-
  Index/FK-Listen), sonst kollidiert „Kinder verschwinden aus dem Top-Level" (AP2)
  mit „kind-lokal muss erhalten bleiben". Diese Dedup/Klassifikation speist
  denselben Set-Vergleich wie die Bounds (AP4).
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
  **Motivation (latenter Defekt — DATENBELEGT 2026-06-20):** aktuell enumeriert
  der Transfer alle 8 plain Tabellen (Parent + 7 Kinder); `SELECT * FROM payment`
  (Partition-Parent in der Quelle) liefert **alle** Kind-Zeilen, *zusätzlich*
  liefert jeder Kind-SELECT seine — die Zeilen landen also doppelt im Ziel (einmal
  im geflachten Parent, einmal in den Kind-Tabellen). **Belegt durch den
  Sample-DB-Harness Phase 2 (Pagila PG→MySQL):** im MySQL-Ziel hat `payment`
  (plain) **16049** Zeilen **und** die 7 Kinder `payment_p2022_01..07` zusammen
  **16049** — Gesamt **32098 statt 16049** (Faktor 2). Die Per-Tabelle-Parität
  (16049==16049, 723==723) bemerkt es **nicht** (wie hier vorhergesagt). Tracker:
  [`../done/sample-db-phase2-findings.md`](../done/sample-db-phase2-findings.md)
  (P2-pg2my). Übrig bleibt **ein Verifikationstest**, der genau das prüft:
  Parent-Routing für Read **und** Write, **und Nicht-Duplikation** (Gesamtzeilen
  im Ziel == Quelle, nicht 2×) — nicht nur Per-Tabelle-Parität.
- **AP6 — Cross-Dialect (MySQL ist *nicht* Greenfield).** Der MySQL-Generate-Pfad
  **existiert bereits**: `MysqlIndexPartitionDdlHelper.generatePartitionClause`
  emittiert `PARTITION BY RANGE (key) (PARTITION p0 VALUES LESS THAN (…))` und
  konsumiert **dieselben** `PartitionDefinition`-Felder (RANGE: `partition.to`;
  LIST: `partition.values`) plus E055 (nur bei leerer RANGE-/LIST-Liste; HASH
  bleibt mit Default-Partition gültig) und W112 (RANGE-only). Zwei Folgen:
  - **Verlustbehaftete Abbildung = semantischer Carve-Out (ADR-pflichtig), nicht
    nur Syntax.** PG-RANGE hat `from`+`to` (Lücken erlaubt); MySQL-RANGE kennt nur
    `VALUES LESS THAN` (obere Grenze) und setzt Kontiguität voraus — der Helfer
    **verwirft `from` für RANGE schon heute** (Z. 61). Dieser Informationsverlust
    ist eine bewusste Entscheidung (analog zum Volltext-Muster), die die ADR
    benennen muss, kein reines Generate-Form-Detail.
  - **MySQL-Reverse ist genauso partitions-blind wie PG** (kein MySQL-Reader
    befüllt `partitioning.partitions`; nur `MysqlDdlGenerator` konsumiert es). „PG
    zuerst" rechtfertigt die Abgrenzung — aber ein vollständiger Cross-Dialect-
    Round-Trip braucht **dieselbe AP1-Klasse Arbeit für MySQL** (eigener Reverse-
    Capture). Das ist der eigentliche AP6-Scope, nicht „nur Generate-Mapping".
  - SQLite kennt keine Partitionierung → `E055` bleibt (bereits so).

## Kopplung (Reihenfolge ist nicht beliebig)

- **AP1a ist das *erste* Gate (Upstream von AP1/AP3/AP4/AP6).** Die
  Repräsentations-Wahl der ADR (opak vs. strukturiert) muss **vor** AP1/AP4
  fallen, weil sie bestimmt, was „ein kanonisches Encoding" überhaupt bedeutet —
  sie verändert die Form, die Reverse erzeugt, Generate (PG **und** MySQL)
  emittiert und Comparator vergleicht. Erst danach sind AP1/AP4 sauber schneidbar.
- **AP1 ⇄ AP2 — *harter* Generate-Fehler, nicht nur ein Diff (AP2 ist faktisch
  Teil von AP1).** Befüllt AP1 die `partitions`-Liste, **ohne** dass AP2 die
  Kinder aus der Top-Level-Liste entfernt, emittiert Generate den Kind-Namen
  **zweimal**: einmal als `CREATE TABLE payment_p2022_01 PARTITION OF payment …`
  (aus `partitions`) und einmal als `CREATE TABLE payment_p2022_01 (…)` (aus der
  Top-Level-Tabelle) → **doppelter Relationsname → `relation already exists`**,
  der ganze Generate-Lauf scheitert. AP1 und AP2 sind daher untrennbar; AP2 ist
  kein eigener Slice, sondern die zweite Hälfte von AP1.
- **AP1 ⇄ AP4 — Baseline-Bruch.** Macht man den Comparator partitions-bewusst
  (AP4), **bevor** der Reverse die Partitionen erfasst (AP1), bekäme der
  Pagila-Round-Trip plötzlich einen Partitions-Diff (Quelle modelliert RANGE,
  Ziel — als plain Tabelle erzeugt — nicht) und die bestehende
  `IDENTICAL`-Baseline bricht. Erst Reverse-Capture stellt beide Seiten gleich,
  dann ist der partitions-bewusste Vergleich grün.

## Akzeptanzkriterien (Skizze — schärfen beim Move nach `next/`)

- **Reverse-Capture:** `PartitionConfig.partitions` wird für RANGE/LIST/HASH
  befüllt (pg_inherits + `relpartbound`-Parsing) — Unit-Test je Strategie; das
  `from`-Encoding für HASH (`MODULUS/REMAINDER`) ist als Modellvertrag getestet
  (AP1a). **Kanonische RANGE-Fixture = der echte Trigger-Fall:** Pagilas
  `payment_date` ist `timestamp with time zone`, die Grenzen rendern als
  `FROM ('2022-02-01 00:00:00+00'::timestamp with time zone)` — übt
  Cast-Stripping **und** tz-Handling zugleich.
- **Kind-lokaler Index überlebt den Round-Trip (AP2a):** ein explizit
  kind-lokaler Index (Pagilas `idx_fk_payment_p2022_NN_*`, der im Dump **nicht**
  vom Parent propagiert) ist nach dem Round-Trip noch da — direkter Test gegen die
  „still verworfen"-Falle.
- **Comparator beweist Treue (negativ):** ein Partitionsunterschied (Kind
  hinzugefügt/entfernt, Grenze geändert, Strategie/Schlüssel geändert) **lässt
  `schema compare` fehlschlagen** (Exit DIFFERENT). Der heutige Test
  „partitioning changes do not produce diff" ist umgedreht.
- **Encoding-Identität Reverse↔Generate (der Linchpin):** ein Round-Trip-Test
  beweist, dass das vom Reverse erzeugte Bound-Encoding **identisch** zu dem von
  Generate erwarteten/emittierten ist. Das ist der eigentliche Schutz gegen
  false-positive-Comparator-Diffs (AP1a) — als **eigenes** Kriterium, nicht nur
  Prosa, weil das Doc es selbst als größtes Risiko führt.
- **Pagila/PG-Round-Trip:** `payment` als echte RANGE-Partition mit ihren 7
  Kindern emittiert; **kein** `E055` mehr; Zeilen-Parität **und Nicht-Duplikation**
  (Gesamtzeilen im Ziel == Quelle, nicht 2× — siehe AP5); und
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
- **PK-Propagation am Parent:** PG verlangt, dass der **Primary Key einer
  partitionierten Tabelle den Partitionsschlüssel enthält**. Für Pagila **schon
  erfüllt** — der Parent-PK ist `[payment_date, payment_id]`, enthält den
  Schlüssel `payment_date`; hier also kein Risiko. Der allgemeine Constraint gilt
  weiter (andere Tabellen). Die **Index-/FK-Konsolidierung** ist *nicht*
  abgegrenzt — sie ist echtes Arbeitspaket (AP2a).
- HASH ist **nicht** abgegrenzt: das Generate emittiert es bereits — der Reverse
  muss es nur (im kanonischen Encoding, AP1a) erfassen.

## Entschieden in ADR 0019 (war: Offene Entscheidungen für die ADR)

> **Alle hier genannten Punkte sind in
> [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md) (accepted)
> aufgelöst** — die Darstellung unten bleibt als Begründungs-Kontext.


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
