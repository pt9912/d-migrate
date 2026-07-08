# Import-Durchsatz: pgjdbc-Batch-Rewrite + COPY-Bulk-Pfad (PostgreSQL)

> **Status: BEIDE HEBEL ERLEDIGT + live gemessen (2026-06-25) — Ticket geschlossen.**
> Schritt 0 (pgjdbc-Batch-Rewrite): Import ~66,6k → ~115,5k rows/s (~1,73×), verlustfrei.
> COPY-Bulk-Fast-Path (zweiter Hebel): Import ~115,5k → **~157,5k rows/s (~1,36× ggü.
> Schritt 0; ~2,36× ggü. Ur-Baseline ~66,6k)**, Verlustfreiheit HART (kanonischer SHA-256
> aller 8 Tabellen quell-identisch), alle vier Korrektheits-Sperren abgedeckt. Trigger 2026-06-23.
> **Trigger:** Der #2-Tool-Vergleich (`make sample-db-tool-compare`,
> [`tool-comparison.md`](../open/tool-comparison.md)) zeigte d-migrates PG→PG-**Import** bei
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

**✅ ERLEDIGT (2026-06-25).** `reWriteBatchedInserts=true` in
`PostgresJdbcUrlBuilder.defaultParams()` ergänzt (Pendant zu MySQLs bereits gesetztem
`rewriteBatchedStatements=true`); Unit-Test (`PostgresJdbcUrlBuilderTest`) + Full-Build grün.
**Gemessen** (`make sample-db-tpch-perf`, SF=0.2 → 1 731 999 Zeilen, Caps 2 CPU/4 GB):
Import **~66 615 → ~115 466 rows/s (~1,73×)** (Import-Zeit 26 s → 15 s); Verlustfreiheit
unverändert HART (kanonischer SHA-256 identisch). Export unbeeinflusst (~247k/s, erwartet —
der Param wirkt nur auf Batch-INSERTs). **Verbleibender Abstand zur COPY-Decke (~460k/s)** ist
der Spielraum, den der COPY-Pfad unten adressiert.

**Accounting-Nachzug (2026-06-25, via PG-Integrationstests aufgedeckt):** Mit
`reWriteBatchedInserts=true` fasst pgjdbc Mehrzeilen-Batches zu einem Multi-Row-INSERT zusammen
und meldet `Statement.SUCCESS_NO_INFO` je Batch-Element statt einer Zeilenzahl. Das alte
`toWriteResult` zählte das als `unknown` → `rowsInserted=0` für ≥2-Zeilen-Chunks auf dem
INSERT-Pfad (nicht COPY-fähige Typen wie Enum). Fix: unter `ABORT` zählt `SUCCESS_NO_INFO` als
eingefügt (ein Konflikt würde werfen, nicht stumm überspringen), unter `SKIP` bleibt es ehrlich
`unknown` (Einfügen vs. DO-NOTHING-Skip nicht rekonstruierbar). Reine Zähl-Korrektheit; die
4c-SHA-256-Verlustfreiheit war nie betroffen (sie prüft persistierte Daten, nicht die
gemeldete Zeilenzahl) — genau deshalb fiel es erst in den `rowsInserted`-Assertions der
Integrationstests auf.

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

## ✅ ERLEDIGT (2026-06-25): COPY-Fast-Path (Lösungsskizze 1 + Stream aus In-Memory-Chunk)

Umgesetzt wurde **Lösungsskizze 1** (COPY-Fast-Path für wrapping-freie Tabellen) mit dem
**In-Memory-Chunk-Stream** aus Skizze 3 (`CopyManager.copyIn(Reader)` über einen
`StringReader`) — **kein** Staging (Skizze 2) und **kein** neuer abstrakter Dialekt-Hook:
der Pfad sitzt PG-lokal im bereits existierenden `executeChunk`-Override, der Rückfall ist der
bestehende `executeInsertChunk`. Format: **COPY TEXT** (`FORMAT text`), nicht binär — der
NULL-Marker `\N` ist eindeutig und die kanonische Text-Repräsentation der erlaubten Skalartypen
ist gültiges PG-Input.

**Code** (alle `internal`, PG-lokal):
- `PostgresCopyFastPath` — Gate (`isEligible`) + `execute` (`COPY … FROM STDIN WITH (FORMAT text)`).
- `PostgresCopyText` — reiner COPY-TEXT-Encoder (isoliert unit-getestet; der korrektheits-
  kritische Teil — ein Kodierfehler wäre stille Datenkorruption).
- `PostgresTableImportSession.executeChunk` — Dispatch: `ABORT` + echte PGConnection
  (`isWrapperFor(PGConnection)`) + `isEligible` → COPY, sonst INSERT.

**Konservatives Eignungs-Gate** (`isEligible`) — deckt alle vier harten Sperren ab:
1. **Nur `OnConflict.ABORT`** — `UPDATE`/`SKIP` bleiben per Dispatch auf dem INSERT-Pfad. ✅
2. **Keine `GENERATED ALWAYS`-Spalte** — über `generatedAlwaysColumns` ausgeschlossen. ✅
3. **Gröberes Accounting akzeptiert** — `copyIn`-Zeilenzahl → `rowsInserted`. ✅
4. **Trigger-Semantik identisch** — der Import deaktiviert Trigger via
   `ALTER TABLE … DISABLE TRIGGER USER` (engine-level): deaktivierte Trigger feuern weder bei
   INSERT noch bei COPY, aktivierte feuern bei COPY per-row genau wie bei INSERT → keine
   Divergenz INSERT↔COPY, also keine zusätzliche Sperre nötig. ✅

Zusätzlich (über das Skizzen-Prädikat hinaus, konservativ): nur eine **Allowlist eindeutig
COPY-TEXT-sicherer Skalartypen** (`COPY_TEXT_SAFE_JDBC_TYPES`: int/decimal/float/bool/char/
varchar/date/time/timestamp). Geometrie (SQL-Wrap), Enum, Array, json/jsonb, interval, xml,
bytea bleiben bewusst auf dem INSERT-Pfad — eine spätere Erweiterung (EWKB-Hex für Geometrie,
COPY-Text für json/array) ist möglich, aber nicht nötig für den häufigen Fall. Das
`isWrapperFor(PGConnection)`-Gate hält außerdem Mock-Tests sauber auf dem INSERT-Pfad und
aktiviert COPY nur gegen eine echte pgjdbc-Verbindung.

**Messung** (`make sample-db-tpch-perf`, SF=0.2 → 1 731 999 Zeilen, Caps 2 CPU/4 GB; TPC-H ist
rein wrapping-frei → COPY greift für alle 8 Tabellen): Import **~115,5k → ~157,5k rows/s
(~1,36×)** (Import-Zeit 15 s → 11 s); **Verlustfreiheit HART** unverändert (kanonischer SHA-256
aller 8 Tabellen quell-identisch); Resume weiter komplett + verlustfrei; Export unbeeinflusst
(~247k/s). Unit grün (`:adapters:driven:driver-postgresql:check` — Encoder + Routing-Tests +
Regression + Detekt + Kover ≥90 %).

**Abstand zur ~460k/s-COPY-Decke:** Die Decke ist roher `\copy` aus CSV (kein JSON-Parse, keine
JVM-Chunk-Pipeline). Unser Import ist Datei-JSON → Parse → Encode → COPY; der verbleibende
Abstand ist die JSON-/Pipeline-Arbeit pro Zeile, nicht mehr das INSERT-Protokoll. Weitere
Hebel (binäres COPY, paralleler Import — siehe „Orthogonale Achse" unten) sind eigene Tickets,
nicht Teil dieses.

## Lösungsskizze (zu entscheiden) — historischer Entscheidungs-Kontext

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

## Orthogonale Achse: Parallelität (bewusst außerhalb dieses Tickets)

Beide Hebel oben — Schritt 0 (`reWriteBatchedInserts`) und der COPY-Pfad — beschleunigen
einen **einzelnen** Import-Stream (Protokoll-Effizienz pro Strom). Eine davon unabhängige
Achse ist **paralleler Import**: mehrere gleichzeitige Verbindungen/Streams (eine je Tabelle;
oder eine große Tabelle chunk-partitioniert über N Streams). Beide Achsen multiplizieren sich.

Bewusst **nicht** in diesem Ticket, weil eigene Korrektheits-/Architektur-Fragen:
- **FK-/Ladereihenfolge** über gleichzeitig geladene Tabellen (referenzielle Integrität).
- **Globaler `triggersDisabled`-Zustand** und Connection-Pool-Dimensionierung.
- **Decke ist die Ziel-Instanz:** Importer-seitige Parallelität ist durch die vertikale
  Kapazität der **einen** Ziel-PG-Instanz (CPU/IO/WAL) begrenzt — ab einem Punkt bringt ein
  weiterer Stream nichts mehr. Protokoll-Effizienz (dieses Ticket) senkt die Arbeit pro Zeile
  und bleibt darum auch unter dieser Decke wirksam.

Falls Volumen-Migrationen das rechtfertigen, als **eigenes** Ticket führen — nicht in den
COPY-Fast-Path mischen.

## Scope-Hinweis

Nicht LF-blockierend (Korrektheit/Verlustfreiheit unverändert). Aktivieren, wenn
Import-Durchsatz ein Ziel wird (große Volumen-Migrationen). Vorher/Nachher belastbar über die
bestehende 4c-Harness (`make sample-db-tpch-perf` / `perf-acceptance.yml`, ADR 0018) auf einem
designierten Runner messen — Schritt 0 isoliert, dann COPY gegen den Rest.

> **Mess-Hinweis zur Quelle:** `tool-comparison.md` nennt im Fließtext „~216k/~78k rows/s",
> in der Durchsatz-Tabelle „~232k export / ~86k import". Dieses Dokument zitiert durchgängig
> die Tabellenwerte; die interne Drift der Quelle bei Gelegenheit dort bereinigen.
