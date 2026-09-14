---
id: skipped-object-erfassung-luecken-unique-funktions-default
title: "Der v1.5.0-Exit-8-Fix deckt zwei weitere Objektverluste nicht ab"
status: resolved
---

# Der v1.5.0-Exit-8-Fix deckt zwei weitere Objektverluste nicht ab

## Der Befund (gemeldet aus einem Konsumentenprojekt)

Der `SkippedObject`-Fix in `v1.5.0` (`feat(schema-generate)!: der Ausgang
haengt an SkippedObject, nicht an der Notiz-Stufe`, Commit `51ec884b7`, und
`fix(ddl-generate): sechs Codestellen tragen jetzt SkippedObject`, Commit
`54d9e472f`) funktioniert fuer die sechs auditierten Stellen — verifiziert
live gegen `local_pg → MSSQL`: ein CHECK-Constraint-Verlust und ein Verlust
einer berechneten Spalte erhoehen jetzt korrekt `skipped_objects`, `summary`
zeigt `"3 skipped"` statt wie vorher immer `"0 skipped"`.

Zwei verwandte Faelle bleiben aussen vor:

1. **E057 UNIQUE-Constraint-Skip** (MSSQL, grosse Objekttypen als
   Key-Spalte). Im selben Repro verschwinden `uq_customer_email` und
   `uq_product_sku` komplett — ohne dass `skipped_objects` das zaehlt. Der
   Code-Pfad war nicht Teil des P0-Audits der sechs Emissionsstellen.
2. **E053 verworfener Rohtext-Funktions-DEFAULT** (z. B. `orders.status_history`,
   analog zum `ARRAY['NEW'::order_status]`-Fund gegen v1.3.1) zaehlt ebenfalls
   nicht als `skipped_objects`.

Beides ist derselbe Objektverlust wie die bereits gefixten CHECK-/
Computed-Column-Faelle — nur an anderer Stelle im Code.

Zusaetzlich, unabhaengig vom Exit-Code: Der MCP-Tool-Call `schema_generate`
liefert weiterhin ein normales (nicht-fehlerhaftes) Ergebnis, unabhaengig vom
`skipped_objects`-Zaehler — nur der CLI-Prozess-Exit-Code hat sich geaendert.
Fuer Konsumenten, die den MCP-Server statt der CLI nutzen, bringt der Fix
also noch keine Verbesserung am Call-Status; sie muessen weiterhin
`summary`/`findings` im Payload parsen.

## Warum (gemessen im Code, 2026-09-14)

Der Mechanismus ist rein mechanisch: alles, was in
`DdlResult.skippedObjects` landet (Deklaration + `SkippedObject`-Klasse in
`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`,
Zeilen 27-31 bzw. 108-115), treibt `exitCodeFor` in
`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
(Zeilen 624-628) auf Exit 8; alles, was nur `notes`/`globalNotes` erreicht,
tut das nicht.
`AbstractDdlGenerator.generate()`
(`adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`)
haelt die eine `skipped`-Liste (Zeile 19), die an jeden Dialekt-Renderer
durchgereicht und als `DdlResult(statements, skipped, globalNotes)`
zurueckgegeben wird (Zeile 127).

Die sechs von `54d9e472f` gefixten Stellen fuegen alle ein
`skipped?.add(SkippedObject(...))` neben dem bestehenden `notes +=` hinzu:
E053 CHECK-Constraint (`*ColumnConstraintHelper.checkClauseOrNull`, alle
fuenf Dialekte), E054 EXCLUDE (`*ColumnConstraintHelper.generateConstraintClause`,
`ConstraintType.EXCLUDE`), E054 SQLite-COMPOSITE
(`SqliteCapabilityDdlSupport.generateCustomTypes`), E057 MySQL-Partial-Index
(`MysqlIndexPartitionDdlHelper.generateIndices`/`generateIndex`), E065
MySQL-FK-auf-partitionierter-Tabelle (`MysqlDdlGenerator.partitionedFkSkip`)
und die Computed-Column-Rohausdruck-Ablehnung
(`RawSqlExpressionPortability.computedRefusal` in
`*ColumnConstraintHelper.refuseUnportableComputed`/`generateColumnSql`/`renderColumn`).
Keine der sechs beruehrt die beiden hier gemeldeten Pfade.

**E057 UNIQUE/PK-auf-LOB (MSSQL)** —
`adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlColumnConstraintHelper.kt`:
- `lobKeyNote()` (Zeilen 444-452) baut nur die `TransformationNote`.
- `generateConstraintClause()`, `ConstraintType.UNIQUE`-Zweig (Zeilen 513-526):
  obwohl die Funktionssignatur seit `54d9e472f` bereits
  `skipped: MutableList<SkippedObject>? = null` traegt (fuer den
  CHECK/EXCLUDE-Zweig), macht der UNIQUE-auf-LOB-Zweig nur `notes +=
  lobKeyNote(...)` — nie `skipped?.add(...)`.
- Spalten-inline UNIQUE-auf-LOB in `nullabilityAndObjects()` (Zeilen
  363-381, konkret 373-380): nur `ctx.notes += lobKeyNote(...)`; `ctx`
  traegt gar keine `skipped`-Referenz.
- Tabellen-PRIMARY-KEY-auf-LOB in
  `MssqlDdlGenerator.generateTable()`
  (`adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlDdlGenerator.kt`,
  Zeilen 184-187): `skipped` liegt als Funktionsparameter in Reichweite
  (Zeile 133), der PK-auf-LOB-Zweig macht trotzdem nur `notes +=
  columnHelper.lobKeyNote(...)`.
- Verwandt, gleiche Codeklasse, nicht explizit gemeldet aber beim Messen
  aufgefallen: das FK-Cascade-auf-`NO ACTION`-Neutralisieren (ebenfalls
  E057) in `buildForeignKeyClause()` (Zeilen 472-501) nimmt gar keinen
  `skipped`-Parameter entgegen — derselbe Luecken-Typ, anderer Ausloeser
  (Cascade-Zyklus statt LOB-Schluessel).

**E053 Funktions-DEFAULT** —
`adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/ForeignFunctionDefaultFilter.kt`
(dessen eigene KDoc, Zeilen 16 und 22-25, zitiert bereits das
`ARRAY['NEW'::order_status]`-Beispiel als „Gemeldet von einem Konsumenten"):
- `apply()` (Zeilen 43-49) gibt `Result(schema, notes: List<TransformationNote>)`
  zurueck — kein `SkippedObject`-Parameter, kein `SkippedObject`-Feld im
  Ergebnis. Der Default wird entfernt (`col.copy(default = null)`, Zeile
  65), nur eine `TransformationNote` via `refusalFor()` (Zeilen 72-84,
  `RawSqlExpressionPortability.notPortableNote(..., "function default", ...)`)
  bleibt.
- Aufrufstelle `AbstractDdlGenerator.generate()`: Zeile 16 `val filtered =
  ForeignFunctionDefaultFilter.apply(rawSchema, dialect)`, Zeile 86
  `globalNotes += filtered.notes` — `filtered.notes` fliesst nie in die
  lokale `skipped`-Liste (Zeile 19), nur in `globalNotes` →
  `DdlResult.globalNotes`, nicht `DdlResult.skippedObjects`.
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MigrateRawSqlPortabilityGuard.kt`
  dokumentiert die Zentralstelle bereits in seiner eigenen KDoc (Zeilen
  26-35): „Der generate-Pfad verwirft solchen Text seit dem
  Konsumentenbefund benannt (`E053`) … zentral fuer den Funktions-Default."
  — das Team kannte die Zentralstelle, sie wurde beim Schliessen der
  sechs anderen Luecken offenbar nur nicht mitgenommen.

**MCP `schema_generate`** —
`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaGenerateHandler.kt`:
- KDoc (Zeilen 45-51) nennt `SkippedObject` bereits explizit „blocking"
  (`severity error — the object did not make it into the DDL — clients
  should treat it as blocking`).
- `handle()` (Zeilen 63-118) gibt unbedingt `ToolCallOutcome.Success(...)`
  zurueck (Zeile 109) — kein Zweig prueft `result.skippedObjects` fuer
  `Success` vs. `Error`. `skippedFindings` (Zeile 92) fliesst nur in das
  `findings`-Array des Payloads (`projectSkipped`, Zeilen 279-285), das
  pro Eintrag `"severity": "error"` setzt — ein Payload-Feld, keine
  Transport-Ebene.
- `McpServiceImpl.kt:330-332` bestaetigt die Abbildung
  `ToolCallOutcome.Success -> isError = false`.
- `SchemaGenerateHandlerTest.kt:262-282` („skipped objects surface as
  error-severity findings") haelt das heutige Verhalten bereits als Test
  fest: `ToolCallOutcome.Success` bei nicht-leerem `skippedObjects` — eine
  absichtliche Entscheidung, testgesichert. Die KDoc ist dazu nicht der
  Massstab: sie ist Kommentar, nicht Vertrag (normativ ist `spec/`), und
  kann selbst veraltet/aspirationsartig sein. Der Befund ist die
  Diskrepanz zwischen Kommentar und Code, kein Verstoss gegen eine
  bindende Zusage — ob der Code oder die KDoc-Zeile nachziehen soll, ist
  offen.

## Was zu tun bleibt

1. **UNIQUE/PK-auf-LOB (MSSQL) `skipped?.add(...)` nachziehen** — im selben
   Muster wie `54d9e472f`: den bereits vorhandenen `skipped`-Parameter in
   `MssqlColumnConstraintHelper.generateConstraintClause()`s
   UNIQUE-Zweig tatsaechlich befuellen; `nullabilityAndObjects()`s
   `ColumnContext` um eine `skipped`-Referenz ergaenzen;
   `MssqlDdlGenerator.generateTable()`s PK-Zweig den bereits in Reichweite
   liegenden `skipped`-Parameter nutzen lassen. `buildForeignKeyClause()`
   braucht dafuer zusaetzlich einen neuen `skipped`-Parameter (existiert
   dort noch gar nicht) — separat zu entscheiden, ob das in denselben
   Schnitt gehoert oder eine eigene, kleinere Folge ist.
2. **`ForeignFunctionDefaultFilter.Result` um Skip-Eintraege erweitern** —
   entweder das `Result` selbst um `skipped: List<SkippedObject>` (parallel
   zu `notes`) ergaenzen, oder die entfernten Defaults ueber einen
   Seitenkanal an `AbstractDdlGenerator`s lokale `skipped`-Liste (Zeile 19)
   mergen, analog zu `globalNotes += filtered.notes` (Zeile 86).
3. **MCP-`isError`-Frage separat entscheiden.** Beide Codefixe oben liefern
   dem MCP-Handler korrekte Daten fuer `severity: error`-Findings, aendern
   aber fuer sich genommen **nichts** an `isError`. Ob `SchemaGenerateHandler.handle()`
   bei nicht-leerem `skippedObjects` kuenftig `ToolCallOutcome.Error` statt
   `Success` liefern soll, ist eine Eigner-Entscheidung — sie aendert das
   MCP-Vertragsverhalten fuer bestehende Konsumenten und braucht eine
   bewusste Freigabe, kein automatischer Nebeneffekt der beiden Datenfixe.
   Die KDoc-Zeile ist dabei kein Argument fuer sich (Kommentar, nicht
   Vertrag) — sie zeigt nur, dass die Frage schon einmal gestellt wurde.
   `SchemaGenerateHandlerTest.kt:262-282` muesste im Falle einer Aenderung
   mit umgeschrieben werden (heute sperrt der Test genau das Gegenteil
   fest).

## Herkunft

Gemeldet gegen `v1.5.0`, isoliertes Repro `local_pg → MSSQL`. Zweiter
Konsumentenbefund zum selben Fix-Commit-Paar (`51ec884b7`/`54d9e472f`); der
Fund zum Funktions-DEFAULT (`E053`) knuepft inhaltlich an den bereits
gegen v1.3.1 gemeldeten `ARRAY['NEW'::order_status]`-Fall an, der
`ForeignFunctionDefaultFilter` erst entstehen liess.

## Umgesetzt (2026-09-14)

Beide Emissionsstellen aus „Was zu tun bleibt" Punkt 1 und 2 sind
geschlossen, im selben Muster wie `54d9e472f`:

- **MSSQL UNIQUE/PK-auf-LOB**: `MssqlColumnConstraintHelper.lobKeyNote()`
  (gab nur eine `TransformationNote` zurück) wurde zu `lobKeyAction()`
  (gibt das zugrundeliegende `ManualActionRequired` zurück, das sowohl
  `.toNote()` als auch `.toSkipped()` kennt). Alle drei Aufrufstellen —
  `nullabilityAndObjects()` (Spalten-inline UNIQUE), `generateConstraintClause()`s
  UNIQUE-Zweig (Tabellen-Constraint), `MssqlDdlGenerator.generateTable()`s
  PK-Zweig — rufen jetzt beides auf. `ColumnContext` trägt dafür neu ein
  optionales `skipped`-Feld.
- **Funktions-DEFAULT (E053)**: `ForeignFunctionDefaultFilter.Result` trägt
  jetzt zusätzlich `skipped: List<SkippedObject>`, befüllt aus denselben
  Feldern wie die bestehende `TransformationNote`. `AbstractDdlGenerator.generate()`
  mischt `filtered.skipped` in seine lokale `skipped`-Liste (Phase `PRE_DATA`,
  analog zu `globalNotes += filtered.notes`).

Beide Fixe sabotage-verifiziert (Fix temporär entfernt, Testfehlschlag
beobachtet, zurückgesetzt) und gegen `docker-check` ohne `MODULES`-Filter
gelaufen, da `driver-common`/`AbstractDdlGenerator` von allen fünf
Dialekten geerbt wird.

**Nicht mitgenommen** (bewusst, siehe „Was zu tun bleibt" Punkt 1 und 3):
das verwandte FK-Cascade-auf-`NO ACTION`-E057 in `buildForeignKeyClause()`
(eigener, kleinerer Fix bei Bedarf) und die MCP-`isError`-Frage
(Eigner-Entscheidung, ändert Vertragsverhalten für bestehende Konsumenten).
