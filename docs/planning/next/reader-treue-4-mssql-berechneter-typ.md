# Reader-Treue 4: der Typ einer berechneten SQL-Server-Spalte (P13)

> **Status:** Entwurf mit Scope (Schnitt 2026-09-17 aus dem ungeschnittenen
> Reader-Slice; Befunde aus Plan-Review und Architektur-Prüfung eingearbeitet,
> Anker gegen `90c6c234f` nachgemessen). **Die drei P13-Messungen sind
> eingetragen (2026-09-29)** — sie tragen Variante a, lösen aber den
> Stopp-Fall F5 aus; die Variante bleibt beim Eigner offen. Teil des Umbrellas
> [`reader-treue.md`](../in-progress/reader-treue.md); dort stehen Nenner, Belegart, Regeln
> der Abnahme, Doku-Pflichten und die Code-Tabelle.
> **Vorbedingung / Gate:** P12 aus [Plan 1](../done/reader-treue-1-matrix-abnahme.md) ist geliefert — die Rückführung
> arbeitet auf normalisiertem Text. Die Entscheidungsregel ist vom Eigner
> bestätigt (2026-09-17). Kein ADR: die Regel „ohne Typ" steht in
> `spec/ddl-generation-rules.md` 3.2a und ist eine Spec-Regel.
> **Aktivierung:** Move nach `../in-progress/` beim ersten
> Implementierungs-Commit dieses Plans; die Messungen allein sind noch kein
> Implementierungs-Commit.
> **Abhängigkeit:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md) (P12, Nulllinie von `:test:integration-mssql`).
> Plan 2 und Plan 3 sind keine Vorbedingung; der Plan kommt nach Eigner-Reihenfolge
> zuletzt.

## Befund

### D1 — SQL Server führt für eine berechnete Spalte keinen deklarierten Typ (nachgemessen)

SQL Server leitet den Typ einer berechneten Spalte aus dem Ausdruck ab. Der
Reverse liest den abgeleiteten Typ — `decimal(23,2)` für
`quantity * unit_price` bei zwei `decimal(12,2)`-Spalten —, das Soll sagt
`decimal(14,2)`. Gemessen im Compare-Slice als Fund in PostgreSQL ↔ SQL Server
und SQL Server ↔ MySQL, schon mit 1.7.1. In der Matrix ist der Posten
unsichtbar: beide Seiten sind Reverses und tragen denselben abgeleiteten Typ.
Sichtbar ist er im Roundtrip-Harness (Datei gegen Reverse): die Tabelle für SQL
Server nennt „abgeleiteter Typ der berechneten Spalte"
([`examples/mcp-e2e/README.md`](../../../examples/mcp-e2e/README.md), Zeile
139).

### Der Generator sagt es nicht (im Code und in der Spec geprüft)

`spec/ddl-generation-rules.md` 3.2a schreibt für SQL Server
`<quoted_name> AS (<expression>) [PERSISTED]` vor, „ohne Typ" — ein Typ davor
ist ein Syntaxfehler. Der Generator meldet nicht, dass der deklarierte Typ
damit wegfällt: spec-konform, aber still.

### Die CAST-Hülle und ihr Haken (im Code geprüft)

Eine Hülle `CAST(<ausdruck> AS <typ>)` gäbe der Spalte den Solltyp. SQL
Server legt `CAST` aber als `CONVERT` ab; der Kommentar am Computed-Zweig in
`MssqlSchemaReader` hält es fest (gemessen auf 2025:
„`([qty]*[price])`, aus `CAST` wird `CONVERT`"). `CONVERT(<typ>, …)` ist auf
PostgreSQL und MySQL kein gültiger Ausdruck, und `E053` erkennt ihn nicht.
Ohne Rückführung im Reader wäre D1 gelöst und ein neuer D6-Fall entstanden.

**Der Präzedenzfall steht in der Spec:** der SQL-Server-Reverse führt einen
Default `CONVERT([date],getdate())` auf `current_date` zurück
(`spec/type-mapping.md` 6.2, „Defaults").

### Architektur-Prüfung

- **Kein ADR:** 3.2a ist eine Spec-Regel.
- **Die Begründung für Variante a** (für den Spec-Nachtrag): in PostgreSQL
  und MySQL **ist** der deklarierte Typ einer berechneten Spalte die
  implizite Umwandlung des Ausdrucks. Eine äußerste `CAST`-Hülle auf genau
  diesen Typ drückt in SQL Server dasselbe aus; sie beim Lesen zu entfernen
  ist bedeutungserhaltend.
- **Die Messungen bleiben im Plan.** Die Spec begründet die Regel aus der
  Sache, nicht mit einem Verweis auf diese Messungen.

### F5 — Herkunft (Architektur-Prüfung, übertragen)

Variante a ändert den erzeugten Ausdruck (mit Hülle) und den gelesenen
(ohne). Für die Herkunftsplanung von `schema migrate`
([ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
übernommene Entscheidung 2) ist zu prüfen, ob ein Artefakt aus der Zeit vor P13
eine `AlterColumnGeneration` auslöst — die SQL Server nicht in place kann (3.2a).

## Ziel

Der deklarierte Typ einer berechneten Spalte geht auf dem Weg über SQL Server
nicht still verloren: entweder bleibt er erhalten (Variante a) oder sein
Wegfall ist benannt (Variante b). Welche Variante, entscheidet die Messung
nach der bestätigten Regel.

Begründet gegen [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) und
[`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004).

## Abgrenzung

- **Keine Änderung an den anderen Dialekten**; sie tragen den Typ schon.
- **Keine Portabilitätsprüfung mit Herkunft**
  ([`../open/ausdrucks-portabilitaet-mit-herkunft.md`](../open/ausdrucks-portabilitaet-mit-herkunft.md)).
  Ein `CONVERT`, das die Rückführung nicht trifft, bleibt dort.
- **Kein SQL-Server-Seed in der Matrix für D1**, solange die Rückführung
  nicht belegt ist (Plan 1, Abgrenzung). Bei Variante a darf P13 ihn anlegen.

## Arbeitspakete

### P13 — messen, dann nach der Regel bauen (D1)

**Modul:** `:adapters:driven:driver-mssql` — der Generator für berechnete
Spalten (`MssqlColumnConstraintHelper`) und der Computed-Zweig in
`MssqlSchemaReader`. Messung in `:test:integration-mssql`.

**Messung zuerst** (SQL Server 2025, derselbe Stand wie die Matrix; die
Ergebnisse stehen danach in diesem Plan):
1. Was `sys.computed_columns.definition` und der Spaltentyp für
   `AS (CAST(<ausdruck> AS decimal(14,2))) PERSISTED` liefern — und für
   `varchar`, `int` und `datetime2` als Zieltyp.
2. Ob der Reader die **äußerste** Hülle `CONVERT(<typ>, <ausdruck>)` genau
   dann eindeutig zurückführen kann, wenn `<typ>` der Spaltentyp ist, so dass
   Hin- und Rückweg Typ und Ausdruck unverändert liefern. Dazu die
   Gegenproben: ein Autor schreibt selbst eine äußerste `CAST`-Hülle auf einen
   **anderen** Typ (sie bleibt); ein Autor schreibt eine Hülle auf **denselben**
   Typ (ihre Entfernung ändert den Autorentext — ist das hinnehmbar?); ein
   `CONVERT` mit Stilargument (bleibt).
3. Was der Vergleich daraus macht (`W137`, Entscheidbarkeit), was der
   Roundtrip-Harness für SQL Server meldet (die Fixture trägt den Fall:
   `line_total` als `decimal(14,2)` über `quantity * unit_price`) und was
   `schema migrate` mit einem Artefakt aus der Zeit vor P13 plant (F5).

**Die drei Messungen** (SQL Server 2025, `2025-latest`, dieselbe Fassung wie
die Matrix; gemessen 2026-09-29 über eine Wegwerf-Spec in
`:test:integration-mssql`, die absichtlich rot endet, damit die Werte in der
Ausgabe stehen — nach der Messung entfernt):

*Messung 1 — was der Server ablegt.* `CAST` liegt wörtlich als `CONVERT` ab,
den Typnamen **geklammert**, den ganzen Ausdruck in eine zusätzliche Klammer.
Die Tabelle führt jeden Fall mit seinem wörtlichen Katalogtext; Quelle sind
zwei `decimal(12,2)`-Spalten `quantity`/`unit_price` und `created_at
datetime2(7)`:

| Spalte | Autoren-DDL | `sys.computed_columns.definition` | Spaltentyp |
| --- | --- | --- | --- |
| `cast_decimal` | `CAST(quantity * unit_price AS decimal(14,2)) PERSISTED` | `(CONVERT([decimal](14,2),[quantity]*[unit_price]))` | `decimal(14,2)` |
| `raw_decimal` | `quantity * unit_price` | `([quantity]*[unit_price])` | `decimal(25,4)` |
| `cast_varchar` | `CAST(unit_price AS varchar(30))` | `(CONVERT([varchar](30),[unit_price]))` | `varchar(30)` |
| `cast_int` | `CAST(unit_price AS int) PERSISTED` | `(CONVERT([int],[unit_price]))` | `int` |
| `cast_datetime2` | `CAST(created_at AS datetime2(3)) PERSISTED` | `(CONVERT([datetime2](3),[created_at]))` | `datetime2(3)` |
| `cast_bare_varchar` | `CAST(unit_price AS varchar)` | `(CONVERT([varchar],[unit_price]))` | `varchar(30)` |
| `convert_style` | `CONVERT(varchar(30), created_at, 126)` | `(CONVERT([varchar](30),[created_at],(126)))` | `varchar(30)` |
| `cast_same` | `CAST(quantity AS decimal(12,2))` | `(CONVERT([decimal](12,2),[quantity]))` | `decimal(12,2)` |
| `cast_nested` | `CAST(CAST(id AS varchar(10)) AS varchar(12))` | `(CONVERT([varchar](12),CONVERT([varchar](10),[id])))` | `varchar(12)` |
| `cast_nested_diff` | `CAST(CAST(quantity * unit_price AS decimal(14,2)) AS decimal(12,2)) PERSISTED` | `(CONVERT([decimal](12,2),CONVERT([decimal](14,2),[quantity]*[unit_price])))` | `decimal(12,2)` |

Gemessen darin, Fall für Fall:

- Mit Hülle leitet SQL Server den **Solltyp** ab (`decimal(14,2)`, nicht den
  Ausdruckstyp), ohne Hülle den Ausdruckstyp: zwei `decimal(12,2)`-Quellen
  geben `decimal(25,4)` (p1+p2+1, s1+s2) — dieselbe Ableitung, die in der
  Harness-Fixture (`integer` × `decimal(12,2)`) `decimal(23,2)` liefert.
- Der Server schreibt `decimal`, nicht `numeric`, und füllt einen ohne Länge
  geschriebenen Typen nicht auf: `CAST(unit_price AS varchar)` bleibt
  `CONVERT([varchar],…)`, die Spalte trägt trotzdem `varchar(30)`.
- Das Stilargument überlebt und steht eingeklammert: `(126)`.
- `is_computed`/`is_persisted` aus `sys.computed_columns` lesen sauber
  (`true`/`true` bei `PERSISTED`, `true`/`false` daneben).
  `INFORMATION_SCHEMA.COLUMNS` hat auf 2025 **kein** `IS_COMPUTED` mehr
  (gemessen: `Invalid column name 'IS_COMPUTED'`).
- Der heutige Reader liest die Hülle in neutraler Schreibweise
  (`CONVERT(decimal(14,2),quantity*unit_price)`) und den Spaltentyp als
  `decimal(14,2)`: die Hülle kommt bis ins Modell, der Solltyp dennoch
  abgeleitet.

*Messung 2 — die Rückführung.* Geprüft wurde die Kandidatenregel: die
äußerste `CONVERT(<typ>, <ausdruck>)`-Hülle entfernen, **genau dann** wenn
`<typ>` dem Spaltentyp entspricht, genau zwei Argumente stehen und nach der
Hülle nichts folgt.

- **Auf dem rohen Katalogtext funktioniert sie nicht** — der Typname ist
  geklammert (`[decimal](14,2)`), der Typ-Token matcht den Spaltentyp nicht.
  Die Rückführung muss auf dem normalisierten Text arbeiten (P12), dort ist
  sie in allen Fällen eindeutig:

| Probe | normalisiert (Reader) | Ergebnis |
| --- | --- | --- |
| `cast_decimal` | `CONVERT(decimal(14,2),quantity*unit_price)` | entfernt (Typ = Spaltentyp) → `quantity*unit_price` |
| `raw_decimal` | `quantity*unit_price` | bleibt (kein `CONVERT`) |
| `cast_varchar` / `cast_int` / `cast_datetime2` | `CONVERT(<typ>,<spalte>)` | entfernt (Typ = Spaltentyp) |
| `cast_bare_varchar` | `CONVERT(varchar,unit_price)` | bleibt (`varchar` ≠ `varchar(30)`) |
| `convert_style` | `CONVERT(varchar(30),created_at,(126))` | bleibt (Stilargument = drittes Argument) |
| `cast_same` | `CONVERT(decimal(12,2),quantity)` | entfernt (Autorenhülle auf denselben Typ) → `quantity` |
| `cast_nested` | `CONVERT(varchar(12),CONVERT(varchar(10),id))` | äußere entfernt, innere bleibt |
| `cast_nested_diff` | `CONVERT(decimal(12,2),CONVERT(decimal(14,2),quantity*unit_price))` | äußere entfernt, Autorenhülle `decimal(14,2)` bleibt |

- **Gegenprobe (a) — Autoren-`CAST` auf einen anderen Typ:** als innere
  Hülle realisiert (`cast_nested_diff`): nach dem Entfernen der äußersten
  Hülle bleibt `CONVERT(decimal(14,2),…)` stehen — gemessen.
- **Gegenprobe (c) — `CONVERT` mit Stilargument:** bleibt; das Stilargument
  steht eingeklammert als drittes Argument, die Regel erkennt es daran —
  gemessen.
- **Gegenprobe (d) — innere Hülle:** bleibt, gemessen (`cast_nested`,
  `cast_nested_diff`).
- **Gegenprobe (b) — Autoren-`CAST` auf denselben Typ:** die Hülle ist
  textuell **nicht unterscheidbar** von einer Generatorhülle — beide liegen
  als `CONVERT([decimal](12,2),[quantity])` ab. Ihre Entfernung ändert den
  Autorentext im Modell (der Reverse liefert `quantity`); die DDL des
  Hin- und Rückwegs bliebe identisch, denn Variante a hüllt beim Generieren
  auf den deklarierten Typ wieder ein, und ein Vergleichsfund entsteht an
  dieser Stelle ohnehin nicht (`W137`). Was sich ändert, ist allein der
  Ausdruckstext im Modell — derselbe Textverlust, den der Reader an jeder
  Serverform hin nimmt (P12).

*Messung 3 — Vergleich und Migrate.* Gemessen gegen zwei Reverses
differierender abgeleiteter Typen (Soll: Reverse ohne Hülle, `decimal(25,4)`,
`PERSISTED`; Ziel: Reverse mit Hülle, `decimal(14,2)`, `PERSISTED`):

- **Compare:** der einzige Fund ist die **Typ**-Änderung
  (`decimal(25,4) -> decimal(14,2)`); der Ausdrucksunterschied wird gefaltet
  (`ColumnDiff.generation` bleibt leer) und als `W137` gesagt. Der Typfund
  ist damit **entschieden** — `W137` betrifft nur den Ausdruck.
- **Roundtrip-Harness** (nach `make docker-build` neu gefahren): die
  SQL-Server-Zelle meldet 5 Funde, darunter
  `line_total: decimal(14,2) -> decimal(23,2)` und den `W137` zum Ausdruck;
  das erzeugte DDL rendert `[line_total] AS (quantity * unit_price)
  PERSISTED`, noch immer **ohne** Typ.
- **F5:** Soll = Reverse heutigen Stands (abgeleiteter Typ, Ausdruck ohne
  Hülle), Ziel = Datenbank mit Hülle: `schema migrate` (ohne `--execute`)
  plant **keine** `AlterColumnGeneration` — der Ausdrucksunterschied bleibt
  unentscheidbar und wird gefaltet —, aber eine gerenderte
  **`AlterColumnType`** an der berechneten Spalte
  (`AUTOMATIC_WITH_DATA_RISK`): `ALTER TABLE [orders] ALTER COLUMN
  [line_total] DECIMAL(25,4) NULL;` — gegen eine **berechnete** Spalte, die
  T-SQL nicht in place ändern kann (3.2a). Die Gegenprobe (Soll gegen die
  eigene Datenbank) bleibt `no_op` mit 0 Operationen.

**Regelhafte Bewertung (2026-09-29):**

- **Messung 1 ist deterministisch:** in allen vier Zieltypen liegt `CAST`
  wörtlich als `CONVERT` ab (geklammerter Typname, eingeklammertes
  Stilargument); bei Hülle leitet der Server den Solltyp ab, ohne Hülle den
  Ausdruckstyp. Kein Fall war mehrdeutig.
- **Messung 2: der Rückweg ist auf normalisiertem Text exakt.** Die drei
  Gegenproben (Hülle auf anderen Typ, Stilargument, innere Hülle) blieben
  stehen, die Hüllen auf den Spaltentyp wurden restlos entfernt; nur der
  typenlose `varchar` bleibt als Textform liegen — er ist sicher (der
  Generator erzeugte ihn nie) und regelkonform behalten.
- **Die „hinnnehmbar“-Frage (Gegenprobe b) beantwortet die Messung:** eine
  Autorenhülle auf denselben Typ ist ununterscheidbar von der
  Generatorhülle, ihre Entfernung ist typidentisch bedeutungserhaltend und
  die DDL des Hin- und Rückwegs bleibt stabil; ein Vergleichsfund entsteht
  nicht. Das Ausschlusskriterium für Variante a hebt sie nicht.
- **Messung 3 löst den Stopp-Fall aus** — präzisiert ihn: geplant ist
  **keine** `AlterColumnGeneration` (der Ausdrucksunterschied wird gefaltet),
  aber eine gerenderte **Typänderung** an der berechneten Spalte — dieselbe
  In-place-Falle (3.2a). Der Fall ist heute schon erreichbar (Autorenhülle);
  Variante a würde ihn zum Normalfall machen, denn dann trägt jede generierte
  SQL-Server-Datenbank die Hülle und jedes ältere Artefakt den abgeleiteten
  Typ.
- **Fazit nach der Regel:** Messung 1 und 2 tragen Variante a, aber der
  gemessene Stopp-Fall hält das Paket vor dem Bau an — ob die geplante
  Typänderung für bestehende Artefakte hinnnehmbar ist (sie bildet den
  realen Drift ab) oder als Fehlalarm unterbleiben muss, ist die
  **Eigner-Frage**. Die Variante bleibt deshalb offiziell offen; nichts
  gebaut.

**Regel (Eigner, 2026-09-17):**
- **Variante a** — der Generator hüllt den Ausdruck in `CAST(… AS <typ>)`,
  der Reader führt die äußerste `CONVERT`-Hülle zurück —, **nur** wenn
  Messung 1 und 2 deterministisch sind und der Rückweg nachweislich exakt ist.
- **Variante b** — sonst bleibt der Generator bei „ohne Typ" und meldet den
  Wegfall mit `W164`: der deklarierte Typ fällt weg, SQL Server leitet ihn
  aus dem Ausdruck ab, ein Reverse liest den abgeleiteten Typ.
- Ergibt Messung 3 bei Variante a eine geplante `AlterColumnGeneration` für
  bestehende Artefakte, hält das Paket vor dem Bau an; der Ausweg ist eine
  Eigner-Frage.

**DoD:**
1. Die drei Messungen stehen in diesem Plan, samt der gewählten Variante und
   ihrer Begründung nach der Regel.
2. **Variante a:** ein SQL-Server-Reverse liefert `decimal(14,2)` und den
   Ausdruck ohne Hülle; die Gegenproben aus Messung 2 halten; der
   Hash-Partitions-Pfad (`MssqlHashPartitionRecognition`) ist unberührt.
   **Variante b:** jede berechnete Spalte mit deklariertem Typ trägt beim
   Generieren nach SQL Server `W164`, auf Generate und Migrate.
3. Test je Pfad; **Sabotage:** a — Rückführung weg → Reverse-Test rot, Hülle
   weg → Generate-Test rot; b — Note weg → Test rot.
4. **Doku (Spec):** `spec/ddl-generation-rules.md` 3.2a — a: die Regel „ohne
   Typ" wird „mit `CAST` auf den deklarierten Typ", mit der Begründung aus der
   Architektur-Prüfung; b: die Regel bekommt den Code.
   `spec/type-mapping.md` 6.2 (die Rückführung neben der
   `CONVERT([date],getdate())`-Regel, bei a) und 6.3 (bei b: der Reverse
   liest den abgeleiteten Typ). Keine Messung und kein Plan als Beleg in der
   Spec.
5. **Doku (Code, bei b):** die fünf Registrierungsorte für `W164`
   (Umbrella), Ledger-Datei `ledger/warn-code-ledger-1.1.0.yaml`.
6. **Doku (Anwender):** Anwenderhandbuch 3.23 („Eine Spalte aus anderen
   Spalten berechnen lassen", `docs/user/anwenderhandbuch.md:2494`) — a: SQL
   Server behält den deklarierten Typ; b: `W164` und was er bedeutet.
   CHANGELOG „Changed" (a: anderes DDL, anderer Reverse) bzw. „Added" (b).
7. **Roundtrip (a):** der Typfund `decimal(23,2)` gegen `decimal(14,2)` für
   SQL Server verschwindet; die README-Tabelle des Harness zieht nach.
8. **Matrix:** SQL Server → PostgreSQL und → MySQL messen weiter — bei a der
   Beleg, dass die Rückführung greift, denn ohne sie stünde dort `CONVERT(…)`
   und die Zelle wäre wieder `APPLY-FAIL`; P13 legt dann den
   SQL-Server-Seed mit `CAST` an. Bei b steht `W164` in `GEN_CODES_*` jeder
   Zelle mit Ziel SQL Server, deren Quelle die Berechnung behält (PostgreSQL,
   MySQL, SQLite → SQL Server). Betroffen sind nach der Regel des Umbrellas
   alle Zellen mit Quelle oder Ziel SQL Server.

**Abnahme:** `:test:integration-mssql` (Nulllinie mit Plan 1 gemessen),
`make mcp-e2e-roundtrip` (SQL Server), Matrix.

## Akzeptanzkriterien

1. Für D1 stehen die drei Messungen im Plan, und die Variante ist nach der
   bestätigten Regel gewählt und gebaut.
2. Bei a trägt ein SQL-Server-Reverse den deklarierten Typ und einen
   Ausdruck ohne Hülle; bei b ist der Wegfall mit `W164` benannt.
3. Kein neuer `CONVERT`-Text erreicht ein anderes Ziel: SQL Server →
   PostgreSQL und → MySQL messen weiter.
4. Spec, gegebenenfalls Ledger, Anwenderhandbuch und CHANGELOG sind
   nachgezogen; `make docs-check` ohne Befund.
5. Der Fix fällt nachweislich mit zurückgenommenem Fix.

## Verifikation

1. **Nulllinie:** `:test:integration-mssql` ist mit Plan 1 gemessen.
2. **Messort:** `:test:integration-mssql` für die Messungen 1 und 2; der
   Roundtrip-Harness und `schema migrate` für Messung 3.
3. **Neu-Pin:** ein Commit, die Schlüssel in der Nachricht (Umbrella).
4. **Gates:** Umbrella, „Gates je Commit".
5. **Was gemessen ist und was nicht:** D1 ist gemessen; dass SQL Server
   `CAST` als `CONVERT` ablegt, ist für `date` gemessen (Default-Pfad) und
   seit 2026-09-29 für den Berechnungsausdruck nachgemessen (Messung 1, vier
   Zieltypen samt Stilargument); die Rückführbarkeit der äußersten Hülle ist
   auf normalisiertem Text gemessen (Messung 2), die Frage nach dem
   Stopp-Fall F5 entschieden — gemessen, Eigner-Frage (Messung 3).

## Offen

- **Messung 3, Herkunft — gemessen (2026-09-29):** geplant ist **keine**
  `AlterColumnGeneration` (der Ausdrucksunterschied bleibt unentscheidbar),
  aber eine gerenderte `AlterColumnType` an der berechneten Spalte — dieselbe
  In-place-Falle (3.2a). Ob diese Typänderung für bestehende Artefakte
  hinnnehmbar ist (sie bildet den realen Drift ab) oder als Fehlalarm
  unterbleiben muss, ist die Eigner-Frage, die den Bau stoppt.
- **Autorentext mit eigener Hülle auf denselben Typ** (Messung 2) — gemessen
  (2026-09-29): von der Generatorhülle ununterscheidbar, ihre Entfernung
  bedeutungserhaltend, die DDL des Hin- und Rückwegs stabil; die Messung
  hebt das Ausschlusskriterium für Variante a nicht.
