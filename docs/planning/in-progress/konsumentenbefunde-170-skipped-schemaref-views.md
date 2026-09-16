# Konsumentenbefunde gegen 1.7.0: Oracle-SkippedObjects, schemaRef-Format, View-Vergleich

> **Status:** **In Arbeit** (2026-09-16 nach `in-progress/` aktiviert; die
> Status-Zeile lautete vorher „umgesetzt und verifiziert, noch nicht committet"
> und war in **jeder** Teilaussage falsch — die Arbeit ist committet
> (`ef78036e6`) und als **1.7.1** ausgeliefert (`4a701a8ff`).
> **Vier DoD-Punkte sind offen**, und zwei Befundklassen sind unbemannt; beides
> steht in „Offene Punkte" direkt unter „Umgesetzt" — dort steht auch, woran es
> gemessen ist.
> **Vorbedingung / Gate:** Keine. Punkt 4 fasst eine dokumentierte Linie an und
> zieht **zwei** Spec-Stellen mit: `spec/cli-spec.md` (Umfang der
> Kanonisierung) und `spec/ddl-generation-rules.md` (Quoting-Strategie je
> Dialekt und Oracle-Rohtext — dort steht, warum der Requoter überhaupt
> quotet). Die **gliedernden Klammern, die Kleinschreibung und die
> Schemaqualifikation** (Punkt 3.3) bleiben ausdrücklich der Eigner-Frage des
> Compare-Slices vorbehalten und sind hier nicht Scope.

## Umgesetzt (Abweichungen vom Entwurf)

Alle Pakete sind gebaut und gegen das Image nachgewiesen: Oracle
`skipped_objects` 0→1 auf der Harness-Fixture (Exit 0→8), `schemaRef` ohne
`format` läuft durch, der PostgreSQL-Round-Trip fällt von 1 auf 0 Funde, und
die Konsumenten-Sichten SQL Server und Oracle melden mit präparierten Dateien
nichts mehr (MySQL bleibt, s. Abgrenzung).

Drei Dinge kamen beim Bauen hinzu, die der Entwurf nicht hatte:

1. **Der MSSQL-Index-Pfad zählte gar nicht.** Fünf Verluststellen (`E066`,
   `E070`, `E071`, nicht renderbarer räumlicher Index, Ausdrucks-Index) bauten
   ihr Statement über eine private Funktion, die nur die Notiz erzeugte —
   dieselbe Klasse, eine Datei weiter. Mitgezogen; die Naht erzwingt es jetzt.
2. **Ein echter Generate-Fehler auf Oracle.** Der Berechnungsausdruck wurde
   nicht requotet, während CHECK und Sichten-Rumpf es werden: `GENERATED
   ALWAYS AS (quantity * unit_price)` scheiterte live an
   `ORA-00904: "UNIT_PRICE"`, und die Tabelle fehlte danach ganz. Gefixt mit
   derselben Requotierung, mit Test.
3. **Das Semikolon musste mehrfach fallen.** Gemessen an einem
   SQL-Server-Reverse: `…customer_id;;` — der Server hängt dem gespeicherten
   Text seines an, und trug die DDL schon eines, stehen dort zwei. Ein
   einzelnes `removeSuffix` verschob den Fehlalarm nur. `TRAILING_SEMICOLA`
   strippt alle abschliessenden.

P2c ist für Oracle und den MSSQL-Index-Pfad mit Fixturen belegt; die übrigen
Dialekte hatten ihre `*ActionRequiredSkippedObjectsTest`-Fälle bereits.

## Offene Punkte (Verifier-Befund 2026-09-16)

Die Wirkung des Slices ist ausgeliefert und unabhängig bestätigt. **Was fehlt,
ist die Abdeckung** — und der Befund ist nicht gelesen, sondern **gemessen**:
der Verifier hat die Zählung an fünf Stellen zurückgenommen
(`OracleSpatialIndexDdl.kt` und vier MSSQL-Indexstellen auf
`skipped?.takeIf { false }`) und der Build blieb **grün**. Diese fünf Stellen
sind unbewacht.

**Vier DoD-Punkte sind damit nicht erfüllt** (die DoD bleibt oben stehen — sie
ist das Ziel, nicht der Ist-Zustand):

1. **E052 hat keinen Test.** Die DoD nennt ihn namentlich (P1/P1a), aber
   `OracleActionRequiredSkippedObjectsTest` endet mit dem Volltext-Fall; E052
   erscheint sonst nur als Kommentar bzw. auf dem PG-Profilpfad. Zu bauen: eine
   Fixture „mehrspaltiger Spatial-Index" mit `skippedObjects`-Zusicherung.
2. **Vier der fünf MSSQL-Indexstellen sind unbewacht** (E066, E070, E071,
   nicht renderbarer räumlicher Index); gedeckt ist nur der Ausdrucks-Index.
   Die bestehenden Prüfungen sehen den **Kommentartext**, der die Rücknahme
   überlebt.
3. **Der CLI-Renderer-Test fehlt:** die neue `columns`-Zeile der drei Renderer
   wird von keinem Test gefahren — dem Fixture in `CompareRendererDiffTest`
   fehlt das Feld. Damit ist Akzeptanzkriterium 7 für die CLI nicht prüfbar.
4. **P3s Spec-Satz ist nicht eingelöst:** weder `spec/mcp-server.md` noch die
   Tool-Schemata tragen die Aussage über `format` (der Text landete in
   `spec/ki-mcp.md`, das sich selbst als Entwurfs-Zielbild ausweist, und in
   `docs/user/api-referenz.md`).

**Zwei unbemannte Restflächen derselben Befundklasse** — sie gehören nicht in
diesen Slice, brauchen aber einen Ort (Vorschlag: `open/`-Eintrag):

- `RawSqlExpressionPortability.indexRefusal` wirft einen Index **notiz-allein**
  weg, und zwar in **allen fünf** Dialekten — genau die Klasse, die P2 schliesst.
- Der **Zusammengesetzte Typ (E054)** fällt in Oracle, MSSQL und MySQL
  notiz-allein aus der Ausgabe, während SQLite dieselbe Klasse zählt. Die Lücke
  ist also dialektungleich.

**Und die Anker driften.** Der Abschnitt „Hinweis zu den Zeilennummern" am Ende
ist ehrlich, löst das Problem aber nicht: für ein Artefakt, das als Beleg
gelesen wird, müssen die Anker stimmen oder ausdrücklich als historisch
gekennzeichnet sein. Bekannte Drift: `OracleColumnConstraintHelper.kt:345→379`,
`:432→470`, `:333→357`, `:98→133`, `RawTextFolding.kt:153→164`,
`SchemaContentLoader.kt:45→43`; `SchemaValidateWiring.kt:45` existiert nicht
mehr.

**Für die Graduation nach `done/`** (Reihenfolge, sobald die vier Punkte
geschlossen sind): Closure-Abschnitt nach der Konvention in
[`../done/README.md`](../done/README.md) (Form:
[`../done/postcompare-type-canonicalization-slice.md`](../done/postcompare-type-canonicalization-slice.md)),
Paket→Commit-Zuordnung, und der Inbound-Verweis aus
[`compare-falsch-positive-cross-dialekt.md`](compare-falsch-positive-cross-dialekt.md)
zeigt nach dem Move auf `../done/…`.


## Befund (gemessen 2026-09-15 gegen `1.8.0-SNAPSHOT` aus `main`)

Ein Konsumentenprojekt meldete gegen `1.7.0` drei Punkte und eine
Design-Rückmeldung. Alle vier sind im Code nachgemessen; Punkt 1–3 zusätzlich
live nachgestellt, im MCP-E2E-Harness (`examples/mcp-e2e/`) und über
`mcp serve --transport stdio`.

### Punkt 1 — Oracle zählt verworfene Objekte nicht als `skipped_objects` (E057, W152, E052)

Sonde: Schema mit 2× UNIQUE auf ungebundener `text`-Spalte + 1× CHECK mit
PostgreSQL-Cast. **Nicht die Harness-Fixture** — die trägt dieselbe
Konstruktklasse kleiner, und die Abnahme unten hängt an ihr (s. „Zur Zahl 3"):

| Dialekt | Meldungen | `skipped_objects` CLI | `skippedCount` MCP |
| ------- | --------- | --------------------- | ------------------ |
| **Oracle** | 2× E057 + 1× E053 | **1** | **1** |
| MSSQL | 2× E057 + 1× E053 | 3 | 3 |
| MySQL | 2× W125 + 1× E053 | 3 | — |
| SQLite | 1× E053 | 1 | — |

Ursache: `OracleColumnConstraintHelper.unkeyableKeyNote` (Zeile 345) gibt eine
`TransformationNote` zurück (`ManualActionRequired(...).toNote()`);
`toSkipped()` wird an **keiner** der **vier** Generate-Aufrufstellen gerufen
(zweimal `OracleDdlGenerator.kt`, zweimal `OracleColumnConstraintHelper.kt`).
Dazu kommen **drei** Index-Stellen, die ein Objekt wegwerfen und ebenfalls
nicht zählen: `OracleFullTextDdl.kt:50` (E057, mehrspaltiger Volltext-Index),
`OracleIndexDdlBuilder.kt:53` (W152, Index auf LOB-Spalte) und
`OracleSpatialIndexDdl.kt:70` (E052, mehrspaltiger Spatial-Index). Die zwei
Diff-/Migrate-Aufrufstellen (`OracleDiffTableOps.kt`,
`OracleRebuildRenderer.kt`) sind nicht betroffen — dort blockiert der
Migrate-Verlauf bewusst.

**Zur Zahl 3.** Erwartet wäre für Oracle 3, wie MSSQL und MySQL es melden.
Die Tabelle oben stammt aus einer Sonde mit **zwei** ungebundenen Uniques und
**einem** Cast-CHECK. Der Harness trägt dieselbe Konstruktklasse kleiner
(`uq_customer_external_ref`, kein Cast-CHECK) — auf ihm steht Oracle heute bei
**0**. Die Abnahme unten hängt deshalb an der **Harness-Fixture**: die liegt im
Repo und ist nachfahrbar, die Sonde war es nicht.

Dieselbe Klasse war schon einmal Gegenstand eines Fixes
([`../done/skipped-object-erfassung-luecken-unique-funktions-default.md`](../done/skipped-object-erfassung-luecken-unique-funktions-default.md),
1.5.1: MSSQL UNIQUE/PK auf LOB, zentraler Funktions-Default); der Sechser-Audit
jenes Fixes erfasste die Oracle-Aufrufstellen nicht, und
`OracleActionRequiredSkippedObjectsTest` deckt nur E053/E054 ab.

### Punkt 2 — `schemaRef` scheitert ohne explizites `format`

```
schemaRef = dmigrate://tenants/default/schemas/sch-eb3950bd56ae469e

schema_generate  schemaRef, ohne format   → VALIDATION_ERROR
  "failed to read schema content: Unrecognized token 'schema_format': …"
schema_generate  schemaRef, format=yaml   → Erfolg
schema_compare   schemaRef, ohne format   → VALIDATION_ERROR (dieselbe)
```

`SchemaContentLoader.load` (Zeile 45) ist `loadReference(source, format ?: "json")`;
Reverse-Artefakte sind YAML (`McpCoreJobWorkerFactory.kt:307`). **Kein
1.7.0-Regress:** `git log v1.5.1..v1.7.0 --` über Loader und Worker ist leer,
beide Seiten stammen aus `ad42a375f` (2026-05-08, 0.9.6-Zyklus). Die CLI löst
dasselbe Problem längst (`SchemaValidateWiring.kt:45`, dort `private`).

### Punkt 3 — `VIEW_CHANGED`

Der Harness meldet für den **Round-Trip innerhalb eines Dialekts**
(PostgreSQL gegen sich selbst):

| Dialekt | Funde | Zusammensetzung |
| ------- | ----- | --------------- |
| PostgreSQL | 1 | `Views changed: 1` |
| MySQL | 5 | 3 Tabellen, 1 Typ, 1 Sicht |
| SQL Server | 5 | 3 Tabellen, 1 Typ, 1 Sicht |

Nachgemessene Ursachen, jede isoliert — die erste behebt dieser Slice, die
zweite trifft nur den Harness-Fall, die dritte ist die bewusste Grenze:

1. **`columnsChanged`** — roher Vergleich (`SchemaComparator.kt:174`) auf einem
   Feld, das `spec/schema.json` als „**Optionale** sichtbare View-Signatur"
   führt, und das **in keinem Renderer ausgegeben** wird: der Fund erscheint
   als blankes `VIEW_CHANGED` ohne Feldzeile. Der PostgreSQL-Round-Trip, also
   ohne Dialektwechsel, meldet ihn.

   **Direkt belegt, mit Gegenprobe** (der Konsument konnte den Fall wegen der
   Reader-Lücken auf MySQL/Oracle nicht herstellen; mit zwei präparierten
   Dateien geht es):

   | Linke Seite | Rechte Seite | Ergebnis |
   | ----------- | ------------ | -------- |
   | Query A, Spalten `order_id: integer` | **dieselbe** Query, **dieselben** Spalten | `Status: IDENTICAL` |
   | Query A, Spalten `order_id: integer` | **dieselbe** Query, `order_id: bigint` | `VIEW_CHANGED`, **ohne Feldzeile** |

   Der erste Fall ist das „Kein Fund"-Soll, der zweite ist der MSSQL-Fall des
   Konsumenten (`text`/`nvarchar`, `numeric(10,2)`/`decimal`) im Kleinen.
   Damit ist die Wertung der Spalten bewiesen — nicht aus Mustern abgeleitet.
2. **Das abschließende `;`** eines zurückgelesenen Rumpfs wird von
   `RawTextFolding.canonicalViewQuery` (Zeile 153) nicht gestrichen. Der Test
   pinnt keinen Semikolon-Fall. *Betrifft die vier gemeldeten Sichten nicht* —
   dort sind beide Seiten Reverses und tragen den Strichpunkt beide; er trifft
   den Round-Trip **Quelle gegen Reverse** (der Harness-Fall).
3. **Weitere Schreibweise-Unterschiede, die MySQLs `VIEW_DEFINITION` liefert**
   und die die Kanonisierung ebenfalls nicht faltet — nachgemessen:

   | Paar | Ergebnis |
   | ---- | -------- |
   | `SELECT id FROM t` gegen `select \`id\` from \`t\`` | `VIEW_CHANGED` — Quoting faltet, die **Kleinschreibung** nicht |
   | `SELECT id FROM t` gegen `select \`db\`.\`id\` from \`db\`.\`t\`` | `VIEW_CHANGED` — die **Schemaqualifikation** faltet auch nicht |
   | `FROM a JOIN b ON …` gegen `from (a join b on((…)))` | `VIEW_CHANGED` — die **gliedernden Klammern** nicht |

   Alle drei sind bewusste Grenzen, nicht Scope (s. Abgrenzung) — aber sie
   stehen hier vollständig, weil die Liste sonst kürzer aussieht als sie ist.

### Rückmeldung 4 — CHECK-Quoting wird nicht vereinheitlicht

`ConstraintDiffContract.canonicalForm()` behandelt Whitespace, Klammern, `~~`
und Casts, schützt String-Literale — Quoting nicht; für View-Bodies ist es
kanonisiert (`RawTextFolding.kt:153`). Das ist kein Konsumenten-Artefakt:
`OracleIdentifierRequoter` quotet die Bezeichner eines CHECK-Ausdrucks auf dem
Generate-Pfad bewusst, weil Oracle unquotiert auf GROSSSCHREIBUNG faltet und
d-migrate wortgetreu quotet anlegt. Der Reverse liest nur zurück, was der
Generator geschrieben hat:

```
PG:     CHECK (quantity > 0)
Oracle: CHECK ("quantity" > 0)
```

## Ziel

Die gemeldeten Fehlalarme und Unterzählungen verschwinden, und die zwei
Stellen, an denen dieselbe Klasse schon einmal durchgerutscht ist, bekommen
eine Absicherung, die nicht an einer einzelnen Aufrufstelle hängt.

## Abgrenzung (nicht Scope)

- **Gliedernde Klammern, Kleinschreibung und Schemaqualifikation (Punkt 3.3).**
  Eine Kanonisierung, die `(a join b)` und `a join b` gleichsetzt oder
  `SELECT` und `select`, ist keine Schreibweise mehr, sondern Struktur — und
  Bezeichner-Kleinschreibung zu falten wäre in PostgreSQL schlicht falsch
  (`"MyCol"` und `mycol` sind dort verschiedene Spalten). Dieselbe
  Eigner-Frage, die
  [`../in-progress/compare-falsch-positive-cross-dialekt.md`](../in-progress/compare-falsch-positive-cross-dialekt.md)
  offen führt.

  **Was das für die Abnahme heißt.** Von den **vier** gemeldeten Sichten
  behebt dieser Slice voraussichtlich **drei**:

  | Gemeldete Sicht | Warum sie verschwindet |
  | --------------- | ---------------------- |
  | SQL Server | beiderseits Spalten, gleiche **Namen**, abweichende Typen — P4 vergleicht nur noch Namen |
  | Oracle | Quoting faltet bereits, Spalten einseitig — P4 lässt sie dann aus |
  | PostgreSQL (eigener Round-Trip) | Spalten einseitig **und** Semikolon — beides in P4 |
  | **MySQL** | **bleibt** — Quoting faltet, aber Kleinschreibung, Schemaqualifikation und gliedernde Klammern nicht (drei Gründe, s. Punkt 3) |

  Dass **eine** der vier stehen bleibt, ist die Entscheidung, nicht ein
  übersehener Rest. Wer die Abnahme liest, soll das vorher wissen — sonst gilt
  der Slice als gescheitert, obwohl er geliefert hat, was er verspricht.
- **Die Vertragsänderung am strikten Modus** (zwei Reverses erkennen und die
  Migrate-Faltung zuschalten) — braucht ADR und Spec.
- **Der Apply-Schritt für SQLite und Oracle im Roundtrip-Harness.** Ihre
  Fundzahlen bleiben bis dahin wertlos; das ist in
  `examples/mcp-e2e/README.md` so vermerkt.

## Arbeitspakete

### P1 — Oracle: Objektverluste zählen

`unkeyableKeyNote` wird zu `unkeyableKeyAction` und gibt `ManualActionRequired`
zurück — dieselbe Form wie MSSQLs `lobKeyAction`
(`MssqlColumnConstraintHelper.kt:520`), der den Fix seit 1.5.1 trägt. Jede
**Generate**-Aufrufstelle ruft beide Mapper:

```kotlin
val action = columnHelper.unkeyableKeyAction(name, constraintName, "UNIQUE", listOf(colName))
notes += action.toNote()
skipped += action.toSkipped()
```

- `OracleDdlGenerator.kt:146` (benanntes UNIQUE), `:163` (PRIMARY KEY)
- `OracleColumnConstraintHelper.kt:432` (Tabellen-UNIQUE-Constraint)
- `OracleColumnConstraintHelper.kt:333` (inline-UNIQUE): braucht `skipped` im
  Oracle-`ColumnContext` (Zeile 98) — `generateColumnSql` nimmt `skipped`
  bereits entgegen (Zeile 49) und reicht es nur nicht weiter; MSSQLs Context
  hat das Feld.
- Index-Pfad: `skipped` durch `OracleDdlGenerator.generateIndices` (Zeile 196)
  → `OracleIndexDdlBuilder.render` → `OracleFullTextDdl.render`. Vorbild ist
  MySQLs `generateIndex` (`MysqlIndexPartitionDdlHelper.kt:376`). **Alle
  drei** Stellen werden mitgezogen: E057 (Volltext), W152 (Index auf LOB),
  E052 (`OracleSpatialIndexDdl.kt:70`, mehrspaltiger Spatial-Index).

**Gemessen ist nur eine davon.** Sonde und Harness-Fixture treffen die
benannte-einspaltige-UNIQUE-Stelle (`OracleDdlGenerator.kt:146`) — deshalb
zwei E057 in der Tabelle oben bzw. eine auf der Harness-Fixture. PRIMARY KEY
auf LOB, inline-UNIQUE, die drei Index-Stellen und E052 sind **aus dem Code**
belegt, im Lauf aber nicht ausgelöst. Die Fixturen aus P2c decken sie ab;
bis dahin ist die Breite des Fixes eine Code-Aussage, keine Messung.

Die Diff-/Migrate-Aufrufstellen (`OracleDiffTableOps.kt:92`,
`OracleRebuildRenderer.kt:138`) haben keine `skipped`-Liste und bleiben
verhaltensgleich (`notes += action.toNote()`); dort blockiert der
Migrate-Verlauf bewusst.

**DoD:** Auf der **Harness-Fixture** (`examples/mcp-e2e/scripts/smoke-cross-dialect-roundtrip.sh`)
steigt Oracles `skipped_objects` von **0 auf 1** — sie trägt genau eine
ungebundene Unique und keinen Cast-CHECK, also genau einen der neuen Fälle.
`OracleActionRequiredSkippedObjectsTest` deckt zusätzlich jeden Pfad einzeln
ab: E057 (UNIQUE auf ungebundener `text`-Spalte, PRIMARY KEY auf LOB,
mehrspaltiger Volltext-Index), W152 (Index auf LOB) und E052 (mehrspaltiger
Spatial-Index).

### P1a — die Phase der neuen Einträge

`SkippedObject` trägt ein `phase`-Feld, und `skippedObjectsForPhase` filtert
danach — bei `--split=pre-post` ist das die Trennlinie. Für **Notizen** gibt es
diese Frage nicht: eine statement-gebundene Notiz erbt die Phase ihres
Statements, ihre eigene wird beim Filtern ignoriert (`DdlResult.notesForPhase`).
Für den `SkippedObject` gilt diese Vererbung **nicht** — er muss seine Phase
selbst tragen, und daraus entsteht die Entscheidung.

**Der Kontrakt ist bereits festgelegt, nicht offen:**
`DdlModelTest.kt:104` („`SkippedObject` phase defaults to null") und
`DdlModelTest.kt:219` („`skippedObjectsForPhase` does not return null-phase
objects") schreiben fest, dass `null` **„nicht phasen-gebunden"** heißt und ein
solcher Eintrag in *keinem* der beiden Eimer auftaucht. Das ist gewollt und
gepinnt — kein Mangel, den dieser Slice zu suchen hätte.

Damit ist die Frage eng: **folgen die neuen Einträge dem Verhalten ihres
Blocks, oder brauchen die zwei Index-Fälle eine Ausnahme?**

- `AbstractDdlGenerator` vergibt die Phase über `tagNewSkips` **nach Block**,
  nicht nach Statement — und der Tabellen-/Index-Block (Zeile 41-61) liegt
  zwischen den beiden `tagNewSkips`-Aufrufen (Zeile 30 und Zeile 80) und wird
  **gar nicht getaggt**. Skipped Objects aus Tabellen, Constraints und Indizes
  stehen deshalb heute auf `phase = null`. Das ist der Status quo der neuen
  Einträge, und P1 soll ihn **nicht** aufbrechen: die UNIQUE-, PRIMARY-KEY-,
  Constraint- und W152-Fälle bleiben `null`, wie alles andere aus diesem Block.
- Die zwei **Spatial-/Volltext**-Fälle sind die Ausnahme, um die es geht: ihre
  Statements tragen ausdrücklich `POST_DATA` (`toNote(DdlPhase.POST_DATA)`,
  `phase = DdlPhase.POST_DATA`), und `tagNewSkips` erreicht sie nicht, weil sie
  im PRE_DATA-Block *erzeugt* werden. Ein `null`-Skip fiele damit aus beiden
  Eimern, obwohl sein Statement in einem steht. Ob sie `POST_DATA` tragen
  sollen — `ManualActionRequired.toSkipped(phase)` kann das — ist die
  Entscheidung, die P1a trifft und im Code begründet.

**DoD:** Die Entscheidung steht als Kommentar an der Stelle und als Test: für
die zwei POST_DATA-Index-Fälle ist die Phase explizit gepinnt, für die
übrigen neuen Einträge bleibt sie `null` wie im Rest des Blocks. Ein Test
prüft beides — sonst ist in einem halben Jahr nicht mehr erkennbar, ob das
`null` Absicht oder Vergessen war.

**Bestehende Zusicherungen — nachgesehen, keine kippt.**
`OracleDdlGeneratorObjectsTest.kt:222` (`shouldBeEmpty()`) sitzt auf einer
View-Fixture ohne LOB-Schlüssel, ebenso die drei `shouldHaveSize 1` in
`OracleActionRequiredSkippedObjectsTest`. Die Prüfung steht hier, weil eine
`shouldBeEmpty`-Zusicherung auf einem Pfad mit neu gezählten Objekten genau
die Stelle wäre, an der P1 still scheitert.

### P2 — die Auslassung undarstellbar machen

Der Fix in P1 ist die vierte Runde derselben Klasse. Die Aufrufstelle ist das
Symptom. Die Ursache liegt eine Ebene tiefer: `ManualActionRequired` bietet
**zwei getrennt aufrufbare** Mapper an —

```kotlin
fun toNote(phase: DdlPhase? = null): TransformationNote
fun toSkipped(phase: DdlPhase? = null): SkippedObject
```

— und wo beide einzeln dastehen, kann man einen vergessen. Genau das ist an
sieben Stellen passiert (vier Generate-, drei Index-Aufrufstellen). Ein Test
wäre die Bitte, es künftig nicht zu vergessen; die Naht macht es
**undarstellbar**.

### P2a — die Naht: eine Aufrufstelle statt zweier

Ein Objekt, das nicht in die Ausgabe kommt, wird ab jetzt in **einem** Zug
gemeldet:

```kotlin
// ManualActionRequired
fun record(
    notes: MutableList<TransformationNote>,
    skipped: MutableList<SkippedObject>?,
    phase: DdlPhase? = null,
)
```

Wer `record(...)` benutzt, kann die Notiz ohne den Skip nicht mehr
hinschreiben. Die Umstellung ist mechanisch: an jeder der heute korrekten
Stellen (`MssqlColumnConstraintHelper`, `OracleColumnConstraintHelper` E053/E054,
`MysqlIndexPartitionDdlHelper`) werden die zwei Zeilen zu einer.

**Und die eine Ausnahme, die es geben muss.**
`MssqlColumnConstraintHelper.buildForeignKeyClause` (Zeile 483) emittiert
**E057 allein als Notiz** — zu Recht: der Fremdschlüssel wird als `NO ACTION`
gerendert, das Objekt *ist* in der DDL. Derselbe Code trägt auf demselben
Dialekt also zwei verschiedene Pflichten. Diese Stelle behaelt `toNote()`
einzeln und bekommt einen Kommentar, der sagt warum — sichtbar als bewusste
Ausnahme, nicht als die nächste vergessene Zeile.

### P2b — warum es **keinen** allgemeinen Invariantentest gibt

Der naheliegende Test — „für jedes Statement ohne SQL mit einer
`ACTION_REQUIRED`-Notiz existiert ein `SkippedObject`" — wurde geprüft und
**verworfen**: er wäre am gemeldeten Befund gruen vorbeigelaufen.

`OracleDdlGenerator.generateTable` endet mit `listOf(DdlStatement(sql, notes))`.
Die Notiz für `uq_customer_external_ref` hängt am **CREATE TABLE** — einem
Statement **mit** SQL. Der Vordersatz ist für sie falsch, die Implikation
greift nicht. Dasselbe gilt für E053 (CHECK, berechnete Spalte) und MSSQLs
LOB-Schluessel. Der Test faende nur die drei Index-Stellen — also gerade nicht
die gemeldete Klasse.

Die allgemeine Form ist auch nicht billig zu retten: „jede
`ACTION_REQUIRED`-Notiz braucht einen Skip" ist **falsch** (die
Cascade-Neutralisierung oben ist das Gegenbeispiel), und „Statement ohne SQL"
ist zu schwach. Was die beiden Faelle unterscheidet, ist allein die
**Anwesenheit des Objekts in der DDL** — und die per Namensabgleich im
gerenderten SQL zu suchen wäre eine Heuristik, die selbst Fehlalarme
produziert.

### P2c — die Abdeckung statt der Invariante

An die Stelle des Invariantentests tritt, was P1 ohnehin liefert: für **jeden**
Skip-Pfad eine Fixture, die ihn auslöst, und die Zusicherung, dass ein
`SkippedObject` mit passendem Code und Objektnamen entsteht. Je Dialekt, im
vorhandenen `*ActionRequiredSkippedObjectsTest` — damit läuft es im normalen
Build.

Optional obendrauf: dasselbe Schema durch alle fuenf Generatoren in
`test/e2e-cli` (das alle fuenf Treiber auf dem Testpfad hat). Das Modul ist
hinter `-PintegrationTests` gated (`test/e2e-cli/build.gradle.kts`, gesetzt nur
in `make/native.mk`); ohne die Property überspringt Gradle die Tasks und
meldet trotzdem `BUILD SUCCESSFUL`. **Eine Absicherung, die im normalen Build
nicht läuft, ist keine** — deshalb ist das ein Zusatz, nicht der Ort der
Prüfung.

**DoD:** Die Naht existiert und ist überall benutzt; keine Stelle ruft
`toNote()` und `toSkipped()` mehr als Paar. Die eine Ausnahme
(Cascade-Neutralisierung) ist kommentiert. Jede der sieben Skip-Stellen hat
eine Fixture, die ohne den Fix fällt.

**Vorbehalt beim Umsetzen:** Geprüft ist das nur für Oracle. Faellt eine
Fixture für MSSQL, MySQL, PostgreSQL oder SQLite, ist das **ein Befund, kein
Testfehler** — dort dieselbe Lücke, nur bisher unbemerkt. Nicht weichklopfen,
sondern die Stelle aufnehmen.

### P3 — MCP: Format des `schemaRef` erkennen

Die Heuristik wandert als **eine** Funktion zu `SchemaFileResolver` (dort
stehen `codecForFormat`/`detectFormat` schon); die private Kopie in
`SchemaValidateWiring.kt:45` entfällt:

```kotlin
fun sniffFormat(bytes: ByteArray): String                     // { / [ → json, sonst yaml
fun sniffFormat(input: InputStream): String                   // mark/reset, verbraucht nichts
```

`SchemaContentLoader` nimmt `format: String?`, puffert den Strom
(`openRangeRead` bleibt ein Strom — nichts geht in den Speicher) und lässt
bei `null` erkennen. Explizites `format` gewinnt weiterhin. Der **Inline**-Pfad
sniffed nicht: er hat bereits ein `JsonObject` und übergibt weiterhin
ausdrücklich `"json"`, damit eine Regression dort nicht hinter der Heuristik
verschwindet.

**Die dritte `codecForFormat`-Stelle bleibt bewusst stehen.**
`SchemaStagingFinalizer.kt:122` (Upload-Finalizer) parst ebenfalls mit einem
`format` — dort aber **deklariert der Client** die Kodierung seines eigenen
Uploads, es gibt nichts zu erkennen. Sie ist damit kein Fall für die
Heuristik und wird nicht angefasst; sie steht hier, damit die Aussage „die
private Kopie entfällt" nicht als „nur noch eine Stelle" gelesen wird.

**DoD:** `schema_reverse_start` → `schema_generate` mit `schemaRef` läuft
**ohne** `format`; dasselbe für `schema_compare`. Ein falsches explizites
`format` bleibt ein benannter `VALIDATION_ERROR`. Die Tool-Schemata und
`spec/mcp-server.md` sagen, dass `format` die Kodierung des referenzierten
Artefakts benennt und die Antwort JSON bleibt.

### P4 — View-Vergleich: Spalten und Semikolon

```kotlin
// SchemaComparator.compareView — nur was beide Seiten tragen, und nur der Name
columns = if (left.columns == null || right.columns == null) null
          else valueChangeOrNull(left.columns.map { it.name }, right.columns.map { it.name })
```

Der Typ ist Dialekt-Schreibweise (`text`/`nvarchar`/`character varying`);
Vorbild ist `engine` (Arbeitspaket 3 des Compare-Slices). Aus dem Boolean wird
eine `ValueChange<List<String>>?` — `ViewDiff.columnsChanged` hat außer
`hasChanges()` keinen Produktionsnutzer, die Umformung ist eng.

**`ViewDiff.hasChanges()` (Zeile 11) muss mit.** Es prüft heute
`columnsChanged`; bleibt es dabei, während das Feld die Form wechselt, wird
der Fund **gar nicht mehr gemeldet** — ein stiller Verlust, und damit die
schlechtere der beiden Richtungen. Der Punkt gehört in denselben Commit wie
die Umformung, nicht in einen Nachtrag.

Zweitens wird der Unterschied **sichtbar**: `ViewChangeView`
(`SchemaCompareProjection.kt:141`), `projectViewDiff`
(`SchemaCompareHelpers.kt:168`), die drei CLI-Renderer und der MCP-Fund
(`SchemaCompareHandler.kt:260`).

Für den MCP-Fund ist die Form **vorgegeben**: `compareDetailsSchema()`
(`McpToolSchemas.kt:611`) ist auf `additionalProperties: false` mit genau
`before`/`after` (Strings) festgelegt. Die Spalten-Differenz gehört deshalb
als gerenderte Namensliste in diese zwei **vorhandenen** Felder
(`before: "order_id, email"` / `after: "order_id"`) — kein neues Feld, keine
Schemaänderung, kein `make golden-update`. Wird doch ein eigenes Feld
gewünscht, sind `compareDetailsSchema()` **und** `make golden-update` Teil des
Pakets; die Tool-Schema-Snapshots (`src/test/resources/golden/**`) fallen
sonst.

Drittens das Semikolon in `RawTextFolding.canonicalViewQuery`:

```kotlin
.trim().removeSuffix(";").trim()   // der Server haengt ihn an, der Autor nicht
```

Nur das **abschließende** — `;` in einem Literal bleibt stehen.

**DoD:** PG-Round-Trip meldet die Sicht nicht mehr; der Harness fällt von 1 auf
0. `ViewQueryCanonicalisationTest` pinnt Semikolon (gleich) und eine
Klammer-Differenz (ungleich).

### P5 — CHECK-Quoting kanonisieren

Nach der Literal-Extraktion, vor der Whitespace-Normalisierung, in
`ConstraintDiffContract.canonicalForm()`:

```kotlin
.replace(Regex("\"([A-Za-z_][A-Za-z0-9_]*)\""), "$1")    // ANSI
.replace(Regex("`([A-Za-z_][A-Za-z0-9_]*)`"), "$1")      // MySQL
.replace(Regex("\\[([A-Za-z_][A-Za-z0-9_]*)]"), "$1")    // T-SQL
```

Nur ein **einfacher Bezeichner** wird entpackt (`"my col"` bleibt), die
Literal-Extraktion läuft vorher, und die **Schreibweise bleibt** — `"Quantity"`
und `quantity` sind danach weiterhin verschieden. Gleich werden genau die
Paare, die der `OracleIdentifierRequoter` erzeugt. Wirkung nur auf dem
Compare-Pfad, weil `canonicallyEqual` ausschließlich bei gesetztem
`canonicalizeRawExpressions` gerufen wird; `schema migrate` bleibt unberührt.

**Die zweite, gleichnamige Funktion in derselben Datei wird *nicht* angefasst.**
`ConstraintDiffContract` trägt zwei Kanonisierer, und die Namen liegen eng
beieinander:

| Funktion | Wirkung | Pfad |
| -------- | ------- | ---- |
| `canonicalForm()` (privat) | die volle Kanonisierung | über `canonicallyEqual`, **nur** `schema compare` |
| `canonicalRawSqlExpression()` (privat) | nur Zeilenenden und `trim()` | über `comparable()`, auch `MigrationFingerprint` — **migrate** |

Geändert wird ausschließlich die erste. Wer die zweite erwischt, ändert
still den Fingerabdruck des Migrate-Pfads — das ist teurer zu übersehen als
die drei Regexe, deshalb steht die Abgrenzung hier als eigene Zeile.

Der `[...]`-Zweig ist der riskanteste der drei: die Spec weigert sich
ausdrücklich, `[` als Quoting-Marker zu lesen („`[` steht ebenso in
JSON-Pfaden und Array-Ausdrücken"). Der View-Kanonisierer setzt den
Präzedenzfall, hier ist er aber neu für CHECK-Ausdrücke — deshalb **zwei**
Grenzen gepinnt, nicht eine.

**DoD:** Oracle-Reverse mit `CHECK ("quantity" > 0)` gegen PG-Reverse mit
`CHECK (quantity > 0)` ergibt keinen `TABLE_CONSTRAINT_CHANGED`;
`ExpressionCanonicalisationTest` pinnt beide Grenzen — `"Quantity"` gegen
`quantity` bleibt ein Unterschied (die Schreibweise wird nicht gefaltet), und
ein Klammer-Ausdruck wie `data[a]` gegen `data['a']` bleibt einer (ein
Literal wird nicht zum Bezeichner). `spec/cli-spec.md` und der CHANGELOG
nennen Quoting im Kanonisierungsumfang.

## Akzeptanzkriterien (Slice)

1. Oracle `skipped_objects` steigt auf der Harness-Fixture von **0 auf 1**;
   die Sonde, aus der die Tabelle im Befund stammt, meldet 3 statt 1 — beide
   Zahlen sind an ihre Fixture gebunden, keine gilt „allgemein".
2. **Und der Ausgang kippt mit.** Auf der Harness-Fixture läuft Oracle heute
   auf **Exit 0**, obwohl `uq_customer_external_ref` wegfällt (gemessen:
   `action_required: 1`, `skipped_objects: 0`). Nach P1 trägt derselbe Lauf
   Exit **8** — die v1.5.0-Regel („der Ausgang hängt an `SkippedObject`"),
   also gewollt. Das ist die einzige **nach außen sichtbare** Folge des Slices
   außer den Zahlen und gehört als solche in den CHANGELOG-Eintrag: ein
   Konsument, dessen Oracle-`schema generate` heute mit 0 durchläuft, bekommt
   danach 8. Ohne den Satz liest es sich später wie eine Regression.
3. `schema_generate`/`schema_compare` mit einem `schemaRef` auf ein
   Reverse-Artefakt laufen ohne explizites `format`.
4. Der PostgreSQL-Round-Trip im Harness meldet 0 Funde (heute 1).
5. Die zwei Konsumenten-Sichten, die dieser Slice adressiert, sind mit
   präparierten Dateien nachgestellt und melden nichts: **SQL Server**
   (beiderseits Spalten, gleiche Namen, `text`/`nvarchar`) und **Oracle**
   (Spalten einseitig, Query nur im Quoting verschieden). Der **MySQL**-Fall
   meldet weiterhin — erwartet, s. Abgrenzung.
6. Ein CHECK-Ausdruck, der sich nur im Identifier-Quoting unterscheidet, ist
   keine Änderung — auch über die Dialektgrenze.
7. Unterscheidet eine Sicht sich **wirklich**, nennt der Fund das Feld — in
   der CLI und im MCP-`details` — statt wie heute nur „geändert" zu sagen.
8. Jeder Test fällt nachweislich, wenn man seinen Fix zurücknimmt.

## Verifikation

1. **Sabotage je Paket:** Fix zurücknehmen, Fehlschlag sehen, zurücksetzen.
2. `make docker-check MODULES=":adapters:driven:driver-oracle"` (P1),
   `:adapters:driving:mcp` (P3), `:hexagon:core` (P4, P5).
3. **Einmal ohne `MODULES`** — die `ValueChange`-Umformung an `ViewDiff`
   berührt geteilte Signaturen, und `MODULES=` lässt die
   `test/integration-*`-Module aus.
4. **DDL-Goldens:** P1 ändert die Ausgabe nicht (nur die Zählung) — bleiben
   sie grün, ist das die Bestätigung; schlagen sie fehl, ist das ein Befund.
5. **MCP-Tool-Schema-Goldens:** nur nötig, wenn P4 doch ein eigenes
   `details`-Feld bekommt statt `before`/`after` zu nutzen — dann
   `make golden-update` und die `src/test/resources/golden/**`-Diff mitlesen.
   Bleibt es bei `before`/`after`, darf hier **nichts** wandern; ein
   unerwarteter Golden-Diff wäre das Signal, dass die Form doch angefasst
   wurde.
6. `make mcp-e2e-roundtrip` als Abnahme; danach die Tabelle in
   `examples/mcp-e2e/README.md` nachziehen.
7. `make docs-check` (P3 und P5 fassen Spec/CHANGELOG an) und
   `make solid-suppression-gate`.

## Hinweis zu den Zeilennummern

Alle `Datei:Zeile`-Angaben sind Anker auf den Stand dieses Entwurfs.
`docs-check` prüft, dass der **Pfad** existiert, nicht die Zeile — und P1
verschiebt genau die Nummern, die P1 selbst zitiert
(`OracleColumnConstraintHelper.kt`). Beim Umsetzen über den Symbolnamen
suchen, nicht über die Zahl.
