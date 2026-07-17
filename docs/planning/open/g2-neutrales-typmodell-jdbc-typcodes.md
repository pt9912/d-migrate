# G2 — JDBC-Typcodes durch ein neutrales Typmodell ersetzen

> Status: **Vorschlag (Draft)** — die Entscheidung „was ersetzt `Int`" ist offen und
> [ADR 0028](../../adr/0028-a-check-architecture-gate-scope.md) nennt sie ausdrücklich
> ADR-würdig.
> Trigger: ADR 0028 hält fest: „G2 bleibt ein eigener, spaeterer ADR-/Slice-wuerdiger Umbau:
> neutrales Typ-Enum". Der Punkt war danach **nirgends getrackt** — nicht in `open/`, nicht in
> `next/`, nicht in der Roadmap, nicht in `carveout.md`. Als der a-check-Slice nach
> [`done/`](../done/a-check-architecture-gate.md) graduierte, wurde der G2-Rest unsichtbar; er
> lebte nur noch im ADR-Fließtext. Aufgefallen am 2026-07-17 bei einem unabhängigen Durchgang.
> Aktivierungsbedingung: Entscheidung über die Gestalt des neutralen Typmodells (s. „Offene
> Entscheidung"). Ohne sie ist kein Slice schneidbar.

## Worum es geht: G1 ist erreicht, G2 nicht

[ADR 0028](../../adr/0028-a-check-architecture-gate-scope.md) hat zwei Ziele bewusst getrennt:

- **G1 — Gate grün**: kein `java.sql`-/`javax.sql`-*Import* in der falschen Schicht, keine
  lateralen Adapter-Kanten. **Erreicht** — `make a-check` meldet 0 Befunde.
- **G2 — Neutralmodell wirklich entkoppelt**: die *semantische* JDBC-Kopplung ist raus.
  **Offen.**

Der Unterschied ist der Kern dieses Tickets und wird leicht überlesen: **`make a-check` grün
beweist G1, nicht G2.** `TargetColumn.jdbcType: Int` und `JdbcTypeHint.jdbcType: Int`
transportieren JDBC-`Types`-Codes **ohne** `java.sql`-Import — ein Import-/Schicht-Gate kann das
prinzipiell nicht sehen. Der abgeschlossene Slice nennt das selbst „falsch-grün". Die Kopplung ist
per ADR 0028 als **eng begrenzte Interop-Ausnahme ratifiziert**, nicht übersehen.

## Verifizierter Ist-Stand (2026-07-17)

Die semantische Kopplung sitzt an zwei Port-Feldern:

- [`TargetColumn.jdbcType: Int`](../../../hexagon/ports-write) — **tragend**, nicht dekorativ:
  - **erzeugt** in `driver-common` aus `ResultSetMetaData.getColumnType(...)`
    ([`DataWriterUtils.kt`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/DataWriterUtils.kt),
    [`JdbcChunkSequence.kt`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/JdbcChunkSequence.kt))
  - **konsumiert** für echte Dispatch-Entscheidungen in
    [`PostgresTableImportSession.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTableImportSession.kt)
    (`Types.OTHER` → jsonb/uuid/enum, `Types.ARRAY`)
  - **konsumiert** als Kompatibilitätsmatrix in
    [`ImportTypeCompatibility.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportTypeCompatibility.kt)
    (via portseitigem `JdbcTypeCodes`-Wrapper — das war der G1-Fix: kein `java.sql.Types`-Import
    mehr, der `Int` blieb) und in Fehlertexten von
    [`ImportTableValidator.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportTableValidator.kt)
- [`JdbcTypeHint.jdbcType: Int`](../../../hexagon/ports-common) — konsumiert in `formats`
  ([`DefaultValueDeserializer.kt`](../../../adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DefaultValueDeserializer.kt)
  prüft `Types.NULL`;
  [`DeserializerHelpers.kt`](../../../adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DeserializerHelpers.kt)
  für Meldungen)

Betroffen sind rund 22 Produktivdateien über `ports-common`, `ports-write`, `application`,
`driver-common`, `driver-postgresql` und `formats`.

## Der Parquet-Manifest-Vertrag ist **kein** Blocker (Scope-Reduktion)

Die Slice-Vorarbeit nannte den Parquet-Manifest-/Bundle-Vertrag als G2-Betroffenen, was nach
persistiertem Format und Rückwärtskompatibilität klingt. Nachgeprüft trifft das **nicht** zu:

`ManifestColumn` wird an genau zwei Stellen konstruiert. Der **Schreibpfad**
([`ChunkSchemaToManifest.kt`](../../../adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/manifest/ChunkSchemaToManifest.kt))
setzt `jdbcType = null` (und `sqlTypeName = null`) und schreibt ausschließlich `neutralType`; der
Writer emittiert das Feld nur bedingt (`column.jdbcType?.let { … }`) — also **nie**. Kein von
d-migrate geschriebenes Manifest enthält `jdbcType`. Das Feld existiert nur, damit der
**Lesepfad** es *toleriert*
([`ParquetManifestReader.kt`](../../../adapters/driven/formats-parquet/src/main/kotlin/dev/dmigrate/format/parquet/manifest/ParquetManifestReader.kt)).

**Folge:** Der Parquet-Schreibvertrag ist bereits neutral. G2 muss dort nichts migrieren — und die
teuerste vermutete Teilaufgabe (Format-Migration bestehender Bundles) entfällt. Zu prüfen bleibt
nur, ob die read-toleranten Felder ersatzlos entfallen können.

## Offene Entscheidung (blockiert den Slice-Schnitt)

**Was ersetzt den `Int`?** ADR 0028 sagt „neutrales Typ-Enum", legt die Gestalt aber nicht fest.
Zu klären, bevor Arbeitspakete Sinn ergeben:

1. **Verhältnis zu `NeutralType`.** Es gibt bereits ein neutrales Typmodell
   ([`neutral-model-spec.md`](../../../spec/neutral-model-spec.md)). Ist G2 „`jdbcType` fällt weg,
   `NeutralType` genügt" — oder braucht der Datenpfad eine eigene, gröbere Transport-Kategorie
   neben dem Schema-Typ? `ImportTypeCompatibility` vergleicht heute **beide** (`NeutralType` des
   Schemas gegen `jdbcType` des Ziels); ein Wegfall hieße, die Matrix neu zu begründen.
2. **`Types.OTHER` ist eine Nicht-Information.** PostgreSQL dispatcht auf `OTHER` + `sqlTypeName`
   (jsonb/uuid/enum). Ein neutrales Enum muss diese Fälle *benennen*, sonst wandert die Kopplung
   nur von `Int` nach `String`.
3. **Präzedenz beachten:** [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) hat
   PG-only-Typen (`tsvector`) **first class** ins Neutralmodell gehoben, statt Dialekt-Strings
   durchzureichen. Dieselbe Linie gilt hier: das Ersatzmodell darf keine rohen Dialekt-Strings
   transportieren — sonst wandert die Kopplung nur von `Int` nach `String` (s. Punkt 2).

## Nicht-Ziel

- G1 erneut anfassen: das Gate ist grün und bleibt es.
- Die ratifizierte Ausnahme heimlich aufweichen: solange G2 nicht entschieden ist, bleibt
  `jdbcType: Int` gültig und **sichtbar** (ADR 0028), statt in einem Plan geparkt zu werden.

## Wenn aktiviert

ADR (Typmodell-Entscheidung) → Slice/ImpPlan nach [`../next/`](../next/) → AP-weiser Bau.
