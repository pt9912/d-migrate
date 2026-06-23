# Import-Durchsatz: pgjdbc-Batch-Rewrite + COPY-Bulk-Pfad (PostgreSQL)

> **Status:** Vorabklärung (Trigger, 2026-06-23)
> **Trigger:** Der #2-Tool-Vergleich (`make sample-db-tool-compare`,
> [`tool-comparison.md`](tool-comparison.md)) zeigte d-migrates PG→PG-**Import** bei
> ~86k rows/s vs. die COPY-Decke ~460k rows/s — **~5,4×** langsamer (Export nur ~3,4×).
> Gesamt ~4,6× COPY-Zeit, ~2,7× pgloader. Der Import ist der klare Optimierungs-Hebel.
> **Bezug:** keine harte LF-Anforderung verletzt (Verlustfreiheit + Korrektheit sind ok);
> reine Durchsatz-/Effizienz-Frage. Diagnostisch (Off-Spec-Host), aber das **Verhältnis**
> (Tool-Overhead über der COPY-Decke) ist aussagekräftig.

## Ursache (code-verifiziert 2026-06-23)

`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTableImportSession.kt`
schreibt den
**Default-Import-Pfad** (`OnConflict.ABORT`/`SKIP`) über **Einzelzeilen-Prepared-`INSERT`**:
`buildColumnInsert` erzeugt `INSERT INTO … (cols) VALUES (<placeholders>)` mit **genau einer**
Werte-Zeile, `executeInsertChunk` bindet jede Zeile und feuert `addBatch` / `executeBatch`.
Eine **Multi-Row-VALUES**-Form gibt es **nur** im **UPSERT**-Pfad (`buildMultiRowUpsertSql`,
`OnConflict.UPDATE`) — nicht im heißen Default-Pfad.

Entscheidend: Der PG-Writer aktiviert pgjdbcs **`reWriteBatchedInserts`** nicht
(`PostgresJdbcUrlBuilder.defaultParams()` setzt nur `ApplicationName`). Ohne diesen Parameter
schickt pgjdbc die `executeBatch`-Zeilen als jeweils **eigenes** Server-INSERT — genau das
Profil hinter den gemessenen ~86k rows/s. Keiner der beiden Pfade nutzt das **COPY-Protokoll**
(pgjdbc/Server-COPY ist der native Bulk-Pfad). pgloader erreicht ~1,7× COPY, weil es intern
COPY nutzt.

## Schritt 0: pgjdbc-Batch-Rewrite (billigster Hebel, kein COPY-Konflikt)

Vor jedem COPY-Aufwand: **`reWriteBatchedInserts=true`** für den PG-Writer setzen. pgjdbc
schreibt damit die vorhandenen `addBatch` / `executeBatch`-Einzelzeilen-INSERTs serverseitig in
**Multi-Row-INSERTs** um — **ohne** `valuePlaceholder` / `bindRow` anzufassen
(Geometrie-Wrap, JSON, Enum, Array bleiben vollständig erhalten). Das ist die **direkte
Entsprechung** zu MySQLs `rewriteBatchedStatements=true`, das d-migrate im MySQL-Writer
(`MysqlJdbcUrlBuilder.defaultParams()`) **bereits** setzt — für PostgreSQL fehlt das Pendant
schlicht.

- **Kein** COPY-Transform-Konflikt (siehe unten) — es bleibt der Prepared-Statement-Pfad,
  nur effizienter gebündelt.
- Greift für die `ABORT`/`SKIP`-Mehrheit (plain `INSERT … VALUES`, ohne `RETURNING`). Der
  UPSERT-Pfad baut seine Multi-Row-Form ohnehin selbst und ist unberührt; Shapes, die pgjdbc
  nicht umschreiben kann, fallen sicher auf den normalen Batch zurück (kein Korrektheitsrisiko).
- Erwartung: typischerweise 2–3× auf dem Batch-INSERT — **erst messen**, dann entscheiden,
  wie viel COPY-Aufwand der *verbleibende* Abstand noch rechtfertigt.

## COPY-Bulk-Pfad — der zweite Hebel (mit echter Einschränkung)

Der weitergehende Hebel ist ein **COPY-Protokoll-Pfad** (PostgreSQL: pgjdbc
`CopyManager.copyIn`). **COPY ist ein roher Wert-Stream und kann keine Per-Wert-SQL-Ausdrücke
anwenden.** Der *einzige* solche Ausdruck heute ist das **Geometrie-Wrapping** in
`AbstractTableImportSession.valuePlaceholder` (`ST_GeomFromWKB(?, srid[, axis-order])`) —
sonst liefert `valuePlaceholder` ein nacktes `?`.

Wichtig zur Abgrenzung: JSON/JSONB, Enum, Array, Interval, XML werden **nicht** über
`valuePlaceholder`, sondern in `bindValue` per `PGobject` / `setObject` / `setArray` behandelt.
Diese Typen haben Text-/Binär-Repräsentationen und sind in COPY (Text- oder Binär-Format)
**sehr wohl** darstellbar; selbst Geometrie ginge als EWKB-Hex (inkl. SRID) direkt in COPY.
Der *echte* Blocker ist also allein das **SQL-Funktions-Wrapping** (`ST_GeomFromWKB`), nicht
„irgendeine `bindValue`-Sonderbehandlung". Ein COPY-Pfad ist deshalb **kein pauschaler Ersatz**
des INSERT-Pfads, aber die Sperre ist enger als sie zunächst wirkt.

## Harte Sperren für den COPY-Fast-Path (Korrektheit, nicht nur Speed)

COPY hat andere Semantik als der INSERT-Pfad. Ein Fast-Path ist nur zulässig, wenn **alle**
folgenden Bedingungen erfüllt sind — sonst bleibt der INSERT-/Staging-Pfad:

1. **`OnConflict.ABORT`.** COPY kennt kein `ON CONFLICT` — `SKIP` (`ON CONFLICT DO NOTHING`)
   und `UPDATE` müssen den INSERT-/Staging-Pfad behalten.
2. **Keine `GENERATED ALWAYS`-Spalte mit Wert-Übernahme.** `buildInsertSql` setzt dafür
   `OVERRIDING SYSTEM VALUE`; COPY hat dafür kein Pendant und verhält sich für Identity-Spalten
   anders.
3. **Gröberes Zeilen-Accounting akzeptabel.** Das heutige `WriteResult`
   (inserted/updated/skipped/unknown via `executeBatch`-Counts bzw. `RETURNING (xmax = 0)`)
   wird unter COPY zu einer reinen Gesamtsumme.
4. **Trigger-Interaktion geklärt.** Der Import deaktiviert Trigger (`triggersDisabled`); das
   Zusammenspiel mit COPYs Default-Trigger-Verhalten ist zu prüfen.

Das Prädikat in der Lösungsskizze muss diese Sperren mit abdecken — „keine Spalte braucht ein
SQL-Funktions-Wrapping" allein ist **notwendig, aber nicht hinreichend**.

## Lösungsskizze (zu entscheiden)

1. **COPY-Fast-Path für wrapping-freie Tabellen/Läufe** (der häufige Fall — z. B. TPC-H:
   nur `BIGINT`/`INT`/`VARCHAR`/`DECIMAL`/`DATE`, kein Geometrie-Wrap). Pro Tabelle/Lauf
   erkennen, ob **irgendeine** Spalte ein SQL-Funktions-Wrapping braucht **und** ob die harten
   Sperren oben erfüllt sind; wenn alles passt → COPY, sonst → bestehender INSERT-Pfad.
2. **Oder: COPY→Staging→`INSERT … SELECT <transform>`** — roh per COPY in eine Temp-Tabelle,
   dann typ-transformierend ins Ziel (COPY-Speed + Transforms erhalten, aber Staging-Schritt +
   Transform muss als SQL ausdrückbar sein). Deckt auch Geometrie/`ON CONFLICT` ab.
3. **Architektur-Naht (offen):** Der Import ist heute zeilen-orientiert
   (`AbstractTableImportSession.write` → `executeChunk` über `DataChunk`); COPY ist
   stream-orientiert. Zu entscheiden: Stream aus dem In-Memory-Chunk (z. B. `PGCopyOutputStream`)
   oder direkt aus der bereits existierenden Export-CSV (`tool-comparison.md`: „export→import CSV"
   → `CopyManager.copyIn(Reader)`). Erfordert einen neuen Dialekt-Hook (z. B.
   `supportsCopyFastPath` / `executeCopyChunk`) auf `AbstractTableImportSession`.
4. **Reihenfolge:** PostgreSQL zuerst (`CopyManager`). MySQL hat `rewriteBatchedStatements=true`
   bereits aktiv — sein Batch-Pfad ist schon die umgeschriebene Form, also **kleinerer Headroom**;
   `LOAD DATA LOCAL INFILE` als COPY-Analogon hat dieselbe Literal-only-Sperre **plus**
   `local_infile`-Aktivierung und Sicherheitsfläche → schwererer Lift. SQLite profitiert via
   großen Transaktionen / `executeBatch` (kein COPY).

## Erwarteter Effekt

- **Schritt 0** (`reWriteBatchedInserts=true`) sollte einen erheblichen Teil des ~5,4×-Abstands
  ohne jeden Transform-Konflikt schließen — risikolos und mit MySQL-Präzedenz.
- Der **COPY-Fast-Path** schließt für wrapping-freie Workloads (Mehrheit) den verbleibenden
  Abstand Richtung pgloader (~1,7×). Spatial-Workloads behalten den INSERT-Pfad (oder Staging) —
  dort ist die Typ-Treue wichtiger als der Speed.

## Scope-Hinweis

Nicht LF-blockierend (Korrektheit/Verlustfreiheit unverändert). Aktivieren, wenn
Import-Durchsatz ein Ziel wird (große Volumen-Migrationen). Vorher/Nachher belastbar über die
bestehende 4c-Harness (`make sample-db-tpch-perf` / `perf-acceptance.yml`, ADR 0018) auf einem
designierten Runner messen — Schritt 0 isoliert, dann COPY gegen den Rest.

> **Mess-Hinweis zur Quelle:** `tool-comparison.md` nennt im Fließtext „~216k/~78k rows/s",
> in der Durchsatz-Tabelle „~232k export / ~86k import". Dieses Dokument zitiert durchgängig
> die Tabellenwerte; die interne Drift der Quelle bei Gelegenheit dort bereinigen.
