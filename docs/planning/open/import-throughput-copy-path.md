# Import-Durchsatz: COPY-Bulk-Pfad (PostgreSQL) statt nur Prepared-INSERT

> **Status:** Vorabklärung (Trigger, 2026-06-23)
> **Trigger:** Der #2-Tool-Vergleich (`make sample-db-tool-compare`,
> [`tool-comparison.md`](tool-comparison.md)) zeigte d-migrates PG→PG-**Import** bei
> ~86k rows/s vs. die COPY-Decke ~460k rows/s — **~5,4×** langsamer (Export nur ~3,4×).
> Gesamt ~4,6× COPM-Zeit, ~2,7× pgloader. Der Import ist der klare Optimierungs-Hebel.
> **Bezug:** keine harte LF-Anforderung verletzt (Verlustfreiheit + Korrektheit sind ok);
> reine Durchsatz-/Effizienz-Frage. Diagnostisch (Off-Spec-Host), aber das **Verhältnis**
> (Tool-Overhead über der COPY-Decke) ist aussagekräftig.

## Ursache (code-verifiziert 2026-06-23)

`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTableImportSession.kt`
schreibt über
**gebatchte / multi-row Prepared-`INSERT`** (`INSERT INTO … VALUES (…)`, `addBatch` /
`executeBatch`; Multi-Row-VALUES ~Z. 234) — **nicht** über das **COPY-Protokoll**. Das
ist bereits batch-optimiert, bleibt aber deutlich unter COPY (pgjdbc/Server-COPY ist der
native Bulk-Pfad). pgloader erreicht ~1,7× COPY, weil es intern COPY nutzt.

## „Bulk-import?" — ja, aber mit echter Einschränkung

Der naheliegende Hebel ist ein **COPY-Protokoll-Bulk-Pfad** (PostgreSQL: pgjdbc
`CopyManager.copyIn`). **Aber COPY ist ein roher Wert-Stream und kann die Per-Wert-
Bind-Ausdrücke nicht anwenden**, die `valuePlaceholder` heute setzt:
`AbstractTableImportSession.valuePlaceholder` wrappt z. B. Geometrie als
`ST_GeomFromWKB(?, srid[, axis-order])`, JSON-Encoding, SRID/Typmod — genau d-migrates
Cross-Dialect-/Typ-Treue-Schicht (siehe `AbstractTableImportSession` ~Z. 50,
`MysqlTableImportSession.bindRow`). COPY ingestiert nur **literale** Spaltenwerte. Ein
COPY-Pfad ist deshalb **kein pauschaler Ersatz** des INSERT-Pfads.

## Lösungsskizze (zu entscheiden)

1. **COPY-Fast-Path für transformations-freie Spalten/Tabellen** (der häufige Fall — z. B.
   TPC-H: nur `BIGINT`/`INT`/`VARCHAR`/`DECIMAL`/`DATE`, **kein** `valuePlaceholder`-Wrap).
   Pro Tabelle/Lauf erkennen, ob **irgendeine** Spalte einen nicht-trivialen
   Bind-Ausdruck braucht; wenn nein → COPY, sonst → bestehender INSERT-Pfad.
2. **Oder: COPY→Staging→`INSERT … SELECT <transform>`** — roh per COPY in eine Temp-
   Tabelle laden, dann typ-transformierend ins Ziel (COPY-Speed + Transforms erhalten,
   aber Staging-Schritt + Transform muss als SQL ausdrückbar sein).
3. **Reihenfolge:** PostgreSQL zuerst (`CopyManager`); MySQL hat `LOAD DATA LOCAL INFILE`
   als Analogon; SQLite profitiert via großen Transaktionen/`executeBatch` (kein COPY).

## Erwarteter Effekt

Für transformations-freie Workloads (Mehrheit) sollte der COPY-Fast-Path den Großteil
des ~5,4×-Import-Abstands schließen (Richtung pgloader ~1,7×). Spatial/JSON-Workloads
behalten den INSERT-Pfad (oder Staging) — dort ist die Typ-Treue wichtiger als der Speed.

## Scope-Hinweis

Nicht LF-blockierend (Korrektheit/Verlustfreiheit unverändert). Aktivieren, wenn
Import-Durchsatz ein Ziel wird (große Volumen-Migrationen). Re-Messung auf einem
designierten Runner (ADR 0018) für belastbare Vorher/Nachher-Zahlen.
